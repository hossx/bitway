package com.coinport.bitway.actors.crypto_network

import com.coinport.bitway.common._
import com.coinport.bitway.data._

import org.slf4s.Logging
import scala.collection.mutable.{ ListBuffer, ArrayBuffer, Map, Set }

import Constants._

object BlockContinuityEnum extends Enumeration {
  type BlockContinuity = Value
  val SUCCESSOR, GAP, REORG, OTHER_BRANCH, DUP, BAD = Value
}

class BlockchainDataManager(supportedCurrency: Currency, maintainedChainLength: Int) extends Manager[TBlockchainState] with Logging {
  import CryptoCurrencyAddressType._

  val blockIndexes = ArrayBuffer.empty[BlockIndex]
  private[bitway] val addresses: Map[CryptoCurrencyAddressType, Set[String]] = Map(
    CryptoCurrencyAddressType.list.map(_ -> Set.empty[String]): _*)
  val addressStatus = Map.empty[String, AddressStatus]
  val addressUidMap = Map.empty[String, Long]
  // TODO(c): remove confirmed tx
  val sigIdsSinceLastBlock = Set.empty[String]
  var lastAlive: Long = -1
  private[bitway] val privateKeysBackup = Map.empty[String, String]
  private[bitway] val addressTTL = Map.empty[String, Long]
  var lockForWithdrawal: Long = 0L
  val succeededLocks = ListBuffer.empty[AccountTransfer]
  val failLocks = ListBuffer.empty[AccountTransfer]
  val MIN_RESERVE_INTERNAL_AMOUNT = 100 * 1000L
  val merchant2CreateInvoice = Map.empty[Long, collection.mutable.ListBuffer[InvoiceData]]
  val CREATE_INVOICE_LIMIT_REFER_TIME = 24 * 3600 * 1000L // 1 Day

  final val SPECIAL_ACCOUNT_ID: Map[CryptoCurrencyAddressType, Long] = Map(
    CryptoCurrencyAddressType.Hot -> HOT_UID,
    CryptoCurrencyAddressType.Cold -> COLD_UID
  )

  val FAUCET_THRESHOLD: Double = 0.5
  val INIT_ADDRESS_NUM: Int = 100

  def getSnapshot = TBlockchainState(
    supportedCurrency,
    getFiltersSnapshot,
    blockIndexes.toList,
    addresses.map(kv => (kv._1 -> kv._2.clone)),
    addressStatus.map(kv => (kv._1 -> kv._2.toThrift)),
    lastAlive,
    addressUidMap.clone,
    sigIdsSinceLastBlock.clone,
    privateKeysBackup = if (privateKeysBackup.size > 0) Some(privateKeysBackup.clone) else None,
    addressTTL = if (addressTTL.size > 0) Some(addressTTL.clone) else None,
    Some(lockForWithdrawal),
    Some(merchant2CreateInvoice.map(kv => kv._1 -> kv._2.toList))
  )

  def loadSnapshot(s: TBlockchainState) {
    blockIndexes.clear
    blockIndexes ++= s.blockIndexes.to[ArrayBuffer]
    addresses.clear
    addresses ++= s.addresses.map(kv => (kv._1 -> (Set.empty[String] ++ kv._2)))
    addressStatus.clear
    addressStatus ++= s.addressStatus.map(kv => (kv._1 -> AddressStatus(kv._2)))
    lastAlive = s.lastAlive
    loadFiltersSnapshot(s.filters)
    addressUidMap.clear
    addressUidMap ++= s.addressUidMap
    sigIdsSinceLastBlock.clear
    sigIdsSinceLastBlock ++= s.sigIdsSinceLastBlock
    if (s.privateKeysBackup.isDefined) {
      privateKeysBackup.clear
      privateKeysBackup ++= s.privateKeysBackup.get
    }
    if (s.addressTTL.isDefined) {
      addressTTL.clear
      addressTTL ++= s.addressTTL.get
    }
    lockForWithdrawal = s.lockForWithdrawal.getOrElse(0L)
    if (s.merchant2CreateInvoice.isDefined) {
      merchant2CreateInvoice.clear()
      merchant2CreateInvoice ++= s.merchant2CreateInvoice.get.map {
        kv => kv._1 -> (collection.mutable.ListBuffer.empty[InvoiceData] ++= kv._2)
      }
    }
  }

  def isDryUp = addresses(Unused).size == 0 || addresses(User).size > addresses(Unused).size * FAUCET_THRESHOLD

  def allocateAddress: (Option[String], Boolean /* need fetch from bitway */ ) = {
    if (addresses(Unused).isEmpty) {
      (None, true)
    } else {
      val validAddress = addresses(Unused).headOption
      if (isDryUp)
        (validAddress, true)
      else
        (validAddress, false)
    }
  }

  def addressAllocated(uid: Long, address: String, expiredTime: Option[Long] = None) {
    assert(addresses(Unused).contains(address))
    addresses(Unused).remove(address)
    addresses(User).add(address)
    addressUidMap += (address -> uid)
    if (expiredTime.isDefined) {
      addressTTL += (address -> expiredTime.get)
    }
  }

  def faucetAddress(cryptoCurrencyAddressType: CryptoCurrencyAddressType, addrs: Set[CryptoAddress]) {
    addresses(cryptoCurrencyAddressType) ++= addrs.map(_.address)
    addressStatus ++= addrs.map(i => (i.address -> AddressStatus()))
    if (SPECIAL_ACCOUNT_ID.contains(cryptoCurrencyAddressType))
      addressUidMap ++= addrs.map(_.address -> SPECIAL_ACCOUNT_ID(cryptoCurrencyAddressType))
    val privateKeys = addrs.filter(_.privateKey.isDefined)
    if (privateKeys.size > 0)
      privateKeysBackup ++= Map(privateKeys.map(i => (i.address -> i.privateKey.get)).toSeq: _*)
  }

  def updateLastAlive(ts: Long) {
    lastAlive = ts
  }

  def getSupportedCurrency = supportedCurrency

  def getBlockIndexes: Option[ArrayBuffer[BlockIndex]] = Option(blockIndexes)

  def getCurrentBlockIndex: Option[BlockIndex] = {
    if (blockIndexes.size > 0)
      Some(blockIndexes.last)
    else None
  }

  def unlockWithdrawal(amount: Long) {
    lockForWithdrawal -= amount
    if (lockForWithdrawal < 0)
      lockForWithdrawal = 0
  }

  def lockWithdrawal(amount: Long): Boolean = {
    (getTotalReserveAmount >= amount + lockForWithdrawal + MIN_RESERVE_INTERNAL_AMOUNT) match {
      case true =>
        lockForWithdrawal += amount
        true
      case false =>
        false
    }
  }

  def getTransferType(inputs: Set[String], outputs: Set[String]): Option[TransferType] = {
    object AddressSetEnum extends Enumeration {
      type AddressSet = Value
      val UNUSED, USER, HOT, COLD = Value
    }

    import AddressSetEnum._

    def getIntersectSet(set: Set[String]): ValueSet = {
      var enumSet = ValueSet.empty
      if ((set & addresses(Unused)).nonEmpty)
        enumSet += UNUSED
      if ((set & addresses(User)).nonEmpty)
        enumSet += USER
      if ((set & addresses(Hot)).nonEmpty)
        enumSet += HOT
      if ((set & addresses(Cold)).nonEmpty)
        enumSet += COLD
      return enumSet
    }

    // Transfer will disable someone withdrawal to his deposit address.
    // Which means one CryptoCurrencyTransaction can't has two types: Deposit as well as Withdrawal
    val inputsMatched = getIntersectSet(inputs)
    val outputsMatched = getIntersectSet(outputs)
    if (inputsMatched.contains(USER) || inputsMatched.contains(HOT) || inputsMatched.contains(UNUSED)) {
      if (!outputsMatched.contains(USER) && !outputsMatched.contains(UNUSED))
        Some(TransferType.Withdrawal)
      else
        Some(TransferType.Unknown)
    } else if (outputsMatched.contains(USER)) {
      Some(TransferType.Deposit)
    } else if (inputsMatched.nonEmpty || outputsMatched.nonEmpty) {
      Some(TransferType.Unknown)
    } else {
      None
    }
  }

  import BlockContinuityEnum._

  def getBlockContinuity(blockMsg: CryptoCurrencyBlockMessage): BlockContinuity = {
    getBlockIndexes match {
      case None => SUCCESSOR
      case Some(indexList) if indexList.size > 0 =>
        blockMsg.reorgIndex match {
          case None =>
            if (blockMsg.block.prevIndex == indexList.last)
              if (blockMsg.block.index.height.get - 1 == indexList.last.height.get)
                SUCCESSOR
              else
                BAD
            else if (indexList.exists(i => i.id == blockMsg.block.index.id) ||
              blockMsg.block.index.height.get < indexList.head.height.get)
              DUP
            else
              GAP
          case Some(ri @ BlockIndex(Some(id), Some(h))) =>
            if (ri == indexList.last)
              if (blockMsg.block.index.height.get - 1 == indexList.last.height.get)
                SUCCESSOR
              else
                BAD
            else if (indexList.exists(i => i.id == blockMsg.block.index.id) ||
              blockMsg.block.index.height.get < indexList.head.height.get)
              DUP
            else if (indexList.exists(i => i.id == ri.id))
              REORG
            else
              BAD
          case Some(BlockIndex(None, _)) => OTHER_BRANCH
        }
      case _ => SUCCESSOR
    }
  }

  def completeTransferInfos(infos: Seq[CryptoCurrencyTransferInfo],
    isHotToCold: Boolean = false): (List[CryptoCurrencyTransferInfo], Boolean /* isFail */ ) = {
    if (isHotToCold) {
      if (addresses(Cold).isEmpty) {
        (infos.toList, true)
      } else {
        (infos.map(info => info.copy(amount = setAmount(info.amount, info.internalAmount),
          to = Some(addresses(Cold).head))).toList, false)
      }
    } else {
      (infos.map(info => info.copy(amount = setAmount(info.amount, info.internalAmount))).toList, false)
    }
  }

  def completeCryptoCurrencyTransaction(
    tx: CryptoCurrencyTransaction,
    prevBlock: Option[BlockIndex] = None,
    includedBlock: Option[BlockIndex] = None): Option[CryptoCurrencyTransaction] = {
    val CryptoCurrencyTransaction(_, _, _, inputs, outputs, _, _, _, status, _, _, _) = tx
    if (!inputs.isDefined || !outputs.isDefined) {
      None
    } else {
      val txType = getTransferType(Set.empty[String] ++ inputs.get.map(_.address),
        Set.empty[String] ++ outputs.get.map(_.address))
      if (txType.isDefined) {
        val regularizeInputs = inputs.map(_.map(i => i.copy(
          internalAmount = i.amount.map(internalValue(_)),
          userId = addressUidMap.get(i.address))))
        val regularizeOutputs = outputs.map(_.map(i => i.copy(
          internalAmount = i.amount.map(internalValue(_)),
          userId = addressUidMap.get(i.address))))
        val sumInput = regularizeInputs.get.map(i => i.internalAmount.getOrElse(0L)).sum
        val sumOutput = regularizeOutputs.get.map(i => i.internalAmount.getOrElse(0L)).sum
        val minerFee = if (sumInput > sumOutput) {
          Some(sumInput - sumOutput)
        } else {
          None
        }

        Some(tx.copy(inputs = regularizeInputs, outputs = regularizeOutputs,
          prevBlock = if (prevBlock.isDefined) prevBlock else getCurrentBlockIndex,
          includedBlock = includedBlock, txType = txType, minerFee = minerFee))
      } else {
        None
      }
    }
  }

  def extractTxsFromBlock(block: CryptoCurrencyBlock): List[CryptoCurrencyTransaction] = {
    val CryptoCurrencyBlock(index, prevIndex, txsInBlock) = block
    val filteredTxs = txsInBlock.map(completeCryptoCurrencyTransaction(_, Some(prevIndex), Some(index))).filter(
      _.isDefined).map(_.get)
    if (filteredTxs.isEmpty) {
      List(CryptoCurrencyTransaction(prevBlock = Some(prevIndex), includedBlock = Some(index),
        status = TransferStatus.Confirming))
    } else {
      filteredTxs.toList
    }
  }

  def updateBlock(startIndex: Option[BlockIndex], block: CryptoCurrencyBlock) {
    appendBlockChain(List(block.index), startIndex)
    if (startIndex.isDefined && startIndex.get.height.isDefined) clearAmountAfterHeight(startIndex.get.height.get)
    updateAddressStatus(block.txs, block.index.height)
  }

  def getNetworkStatus: CryptoCurrencyNetworkStatus = {
    getCurrentBlockIndex match {
      case None => CryptoCurrencyNetworkStatus(heartbeatTime = if (lastAlive != -1) Some(lastAlive) else None,
        queryTimestamp = Some(System.currentTimeMillis))
      case Some(index) => CryptoCurrencyNetworkStatus(index.id, index.height,
        if (lastAlive != -1) Some(lastAlive) else None, Some(System.currentTimeMillis))
    }
  }

  def getAddressStatus(t: CryptoCurrencyAddressType): Map[String, AddressStatusResult] = {
    Map(addresses(t).filter(d =>
      addressStatus.contains(d) && addressStatus(d) != AddressStatus()).toSeq.map(address =>
      (address -> addressStatus(address).getAddressStatusResult(getCurrentHeight))
    ): _*)
  }

  def getAsset: Map[CryptoCurrencyAddressType, Double] = getReserveAmounts.map { kv =>
    kv._1 -> externalValue(kv._2)
  }

  def getTotalReserveAmount: Long = getReserveAmounts.values.reduce(_ + _)

  def getTotalReserveExternalAmount: Double = externalValue(getTotalReserveAmount)

  def getLocked: Double = externalValue(lockForWithdrawal)

  def getReserveAmounts: Map[CryptoCurrencyAddressType, Long] = Map(
    User -> getReserveAmount(User),
    Hot -> getReserveAmount(Hot),
    Cold -> getReserveAmount(Cold),
    Unused -> getReserveAmount(Unused)
  )

  def notProcessed(tx: CryptoCurrencyTransaction): Boolean = {
    tx.sigId.isDefined && !sigIdsSinceLastBlock.contains(tx.sigId.get)
  }

  def rememberTx(tx: CryptoCurrencyTransaction) {
    if (tx.sigId.isDefined)
      sigIdsSinceLastBlock += tx.sigId.get
  }

  def canAdjustAddressAmount(address: String, adjustAmount: Long): Boolean = {
    (getAddressAmount(address) + adjustAmount) >= 0
  }

  def adjustAddressAmount(address: String, adjustAmount: Long) {
    val status = addressStatus.getOrElse(address, AddressStatus())
    addressStatus += (address -> status.updateBook(Some(-1), Some(adjustAmount)))
  }

  def getAddressAmount(address: String): Long = {
    val status = addressStatus.getOrElse(address, AddressStatus())
    status.getAmount(getCurrentHeight, 1)
  }

  def includeWithdrawalToBadAddress(transferType: TransferType, infos: Seq[CryptoCurrencyTransferInfo]): Boolean = {
    if (transferType == TransferType.Withdrawal) {
      infos.exists(info => (info.to.isDefined && (addresses(User).contains(info.to.get) ||
        addresses(Hot).contains(info.to.get) || addresses(Cold).contains(info.to.get)
      )))
    } else {
      false
    }
  }

  def syncPrivateKeys(keys: List[CryptoAddress]) {
    privateKeysBackup.clear
    privateKeysBackup ++= Map(keys.map(i => (i.address -> i.privateKey.getOrElse("no-priv-key"))): _*)
  }

  def getPubKeys() = privateKeysBackup.keySet

  def syncHotAddresses(addrs: Set[CryptoAddress]) {
    val origAddresses = addresses.getOrElse(Hot, Set.empty[String])
    val unseenAddresses = addrs.filter(i => !origAddresses.contains(i.address))
    if (unseenAddresses.size > 0)
      faucetAddress(Hot, unseenAddresses)
  }

  def syncColdAddresses(addrs: List[String]) {
    val origAddresses = addresses.getOrElse(Cold, Set.empty[String])
    val unseenAddresses = addrs.filter(i => !origAddresses.contains(i))
    if (unseenAddresses.size > 0)
      faucetAddress(Cold, Set.empty[CryptoAddress] ++ unseenAddresses.map(CryptoAddress(_)))
  }

  def cleanBlockChain() {
    blockIndexes.clear
  }

  def convertToBitcoinTxPort(port: CryptoCurrencyTransactionPort) = BitcoinTxPort(port.address, port.amount)

  def convertToBitcoinTx(cctx: CryptoCurrencyTransaction) = BitcoinTx(
    sigId = cctx.sigId,
    txid = cctx.txid,
    inputs = cctx.inputs.map(i => i.map(convertToBitcoinTxPort(_))),
    outputs = cctx.outputs.map(i => i.map(convertToBitcoinTxPort(_))),
    minerFee = cctx.minerFee,
    timestamp = cctx.timestamp.getOrElse(System.currentTimeMillis),
    status = Option(cctx.status),
    txType = cctx.txType,
    theoreticalMinerFee = Some(internalValue(cctx.theoreticalMinerFee.getOrElse(0.0001))),
    ids = cctx.ids)

  def getReserveAmount(t: CryptoCurrencyAddressType) = getAddressStatus(t).values.map(_.confirmedAmount).sum

  def isWithdrawalToBadAddress(info: CryptoCurrencyTransferInfo): Boolean = {
    info.to.isDefined && (addresses(User).contains(info.to.get) ||
      addresses(Hot).contains(info.to.get) || addresses(Cold).contains(info.to.get)
    )
  }

  def recycleAddresses(timestamp: Long) {
    val deprecatedAddresses = addressTTL.filter(kv => kv._2 < timestamp)
    if (deprecatedAddresses.nonEmpty) {
      log.info("recycle addresses: " + deprecatedAddresses)
      deprecatedAddresses foreach { kv =>
        addresses(User).remove(kv._1)
        addresses(Unused).add(kv._1)
        addressUidMap -= kv._1
        addressTTL -= kv._1
      }
    }
  }

  private def getCurrentHeight: Option[Long] = {
    blockIndexes.lastOption match {
      case None => None
      case Some(index) => index.height
    }
  }

  private[bitway] def updateAddressStatus(txs: Seq[CryptoCurrencyTransaction], h: Option[Long]) {
    txs.foreach {
      case CryptoCurrencyTransaction(_, Some(txid), _, Some(inputs), Some(outputs), _, _, _, _, _, _, _) =>
        def updateAddressStatus_(ports: Seq[CryptoCurrencyTransactionPort], isDeposit: Boolean) {
          ports.filter(port => addressStatus.contains(port.address)).foreach { port =>
            val addrStatus = addressStatus.getOrElse(port.address, AddressStatus())
            val newAddrStatus = addrStatus.updateTxid(Some(txid)).updateHeight(h).updateBook(h,
              port.amount.map(internalValue(_) * (if (isDeposit) 1 else -1)))
            addressStatus += (port.address -> newAddrStatus)
          }
        }

        updateAddressStatus_(inputs, false)
        updateAddressStatus_(outputs, true)
      case _ => None
    }
  }

  private def clearAmountAfterHeight(h: Long) {
    addressStatus.keys.foreach { addr =>
      addressStatus.update(addr, addressStatus(addr).clearBookAfterHeight(h))
    }
  }

  private[bitway] def appendBlockChain(chain: Seq[BlockIndex], startIndex: Option[BlockIndex] = None) = {
    val reorgPos = blockIndexes.indexWhere(Option(_) == startIndex) + 1
    if (reorgPos <= 0 && startIndex.isDefined) {
      log.warn("try to append non-successor block. startIndex: " + startIndex + ", chain: " + chain)
      log.warn("the maintained index list: " + blockIndexes)
    }
    if (reorgPos > 0)
      blockIndexes.remove(reorgPos, blockIndexes.length - reorgPos)
    blockIndexes ++= chain
    if (blockIndexes.length > maintainedChainLength)
      blockIndexes.remove(0, blockIndexes.length - maintainedChainLength)
  }

  private def externalValue(value: Long): Double = {
    (BigDecimal(value) / 100000000).toDouble
  }
  private def internalValue(value: Double): Long = {
    (BigDecimal(value) * 100000000).toLong
  }

  private def setAmount(amount: Option[Double], internalAmount: Option[Long]): Option[Double] = {
    if (amount.isDefined)
      amount
    else
      internalAmount.map(externalValue(_))
  }

  def getMerchantCreateInvoiceTimes(merchantId: Long): Int = {
    merchant2CreateInvoice.getOrElse(merchantId, collection.mutable.ListBuffer.empty[InvoiceData]).filter(i => i.created.isDefined && System.currentTimeMillis() - i.created.get < CREATE_INVOICE_LIMIT_REFER_TIME).size
  }

  def addMerchantCreateInvoice(merchantId: Long, data: InvoiceData) {
    merchant2CreateInvoice.getOrElseUpdate(merchantId: Long, collection.mutable.ListBuffer.empty[InvoiceData]) += data
    merchant2CreateInvoice.values.foreach {
      ds =>
        ds.foreach {
          i =>
            if (i.created.isEmpty || System.currentTimeMillis() - i.created.get > CREATE_INVOICE_LIMIT_REFER_TIME)
              ds -= i
        }
    }
  }

}
