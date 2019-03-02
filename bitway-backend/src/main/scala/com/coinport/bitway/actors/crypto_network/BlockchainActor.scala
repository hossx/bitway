package com.coinport.bitway.actors.crypto_network

import akka.actor._
import akka.actor.Actor.Receive
import akka.event.{ LoggingAdapter, LoggingReceive }
import akka.persistence._

import com.coinport.bitway.LocalRouters
import com.coinport.bitway.common._
import com.coinport.bitway.data._
import com.coinport.bitway.serializers._
import com.redis._
import com.redis.serialization.Parse.Implicits.parseByteArray

import scala.concurrent.duration._
import scala.collection.mutable.Set

import Constants._

/**
 * This actor should be a persistent actor.
 *
 * Responsible for:
 * - generate new address
 * - monitor related tx and blocks
 * - monitor re-orgs
 * - connect to bitcoind
 * - delete unused keys  (0 balance in 6 block)
 * - periodically update L2Manager regarding total platform balances
 */

class BlockchainActor(routers: LocalRouters, supportedCurrency: Currency, bcconfig: BlockchainConfig)
    extends ExtendedProcessor with EventsourcedProcessor with BlockchainDataManagerBehavior with ActorLogging {

  import BlockContinuityEnum._
  lazy implicit val logger: LoggingAdapter = log

  var sendGetMissedBlockTime = 0L
  val RESEND_GET_MISSED_BLOCK_TIMEOUT = 30 * 1000L

  val ADDRESS_EXPIRED_TIME = 1000 * 3600 * 24

  val serializer = new ThriftBinarySerializer()
  val client: Option[RedisClient] = try {
    println(">>>" * 40 + bcconfig.toString)
    Some(new RedisClient(bcconfig.ip, bcconfig.port))
  } catch {
    case ex: Throwable => None
  }

  val delayinSeconds = 4
  override def processorId = "blockchain_btc"

  val manager = new BlockchainDataManager(supportedCurrency, bcconfig.maintainedChainLength)

  override def preStart() = {
    super.preStart
    scheduleTryPour()
    scheduleSyncHotAddresses()
  }

  override def identifyChannel: PartialFunction[Any, String] = {
    case btr: BitwayTransferRequest => "pm"
    case btxs: LockCoinRequest => "pm"
  }

  lazy val paymentActorPath = routers.paymentActor.path
  lazy val channelToPayment = createChannelTo("invoice")
  lazy val channelToTrader = createChannelTo("trader")

  def receiveRecover = PartialFunction.empty[Any, Unit]

  def receiveCommand = LoggingReceive {

    case m: CreateInvoice =>
      println("BlockchainActor: " + m)
      if (manager.getMerchantCreateInvoiceTimes(m.merchantId) > bcconfig.createInvoiceTimesLimit) {
        sender ! InvoiceCreationFailed(ErrorCode.ExceedMaxTimes, "Create invoice too much times, exceed limit")
      } else {
        val (address, needFetch) = manager.allocateAddress
        if (needFetch) sender ! FetchAddresses(supportedCurrency)

        val ts = Some(System.currentTimeMillis)
        persist(m.copy(invoiceData = m.invoiceData.copy(created = Some(System.currentTimeMillis())),
          paymentAddress = if (address.isDefined) address else Some(""),
          expiredTime = ts.map(t => t + ADDRESS_EXPIRED_TIME))) {
          event =>
            updateState(event)
            channelToPayment forward Deliver(Persistent(event), paymentActorPath)
        }
      }

    case m: SendRawTransaction => {
      println(m.toString)
      sendTransactionRequest(m)
    }

    case m: TryFetchAddresses =>
      if (recoveryFinished && manager.isDryUp) {
        self ! FetchAddresses(supportedCurrency)
      } else {
        scheduleTryPour()
      }

    case m: TrySyncHotAddresses =>
      if (recoveryFinished) {
        self ! SyncHotAddresses(supportedCurrency)
      } else {
        scheduleSyncHotAddresses()
      }

    case FetchAddresses(currency) =>
      if (client.isDefined) {
        client.get.rpush(getRequestChannel, serializer.toBinary(BitwayRequest(
          BitwayRequestType.GenerateAddress, currency, generateAddresses = Some(
            GenerateAddresses(bcconfig.batchFetchAddressNum)))))
      }
    case SyncHotAddresses(currency) =>
      if (client.isDefined) {
        client.get.rpush(getRequestChannel, serializer.toBinary(BitwayRequest(
          BitwayRequestType.SyncHotAddresses, currency, syncHotAddresses = Some(
            SyncHotAddresses(supportedCurrency)))))
      }
    case m @ CleanBlockChain(currency) =>
      persist(m) {
        event =>
          updateState(event)
      }
    case SyncPrivateKeys(currency, None) =>
      if (client.isDefined) {
        client.get.rpush(getRequestChannel, serializer.toBinary(BitwayRequest(
          BitwayRequestType.SyncPrivateKeys, currency, syncPrivateKeys = Some(
            SyncPrivateKeys(supportedCurrency, Some(manager.getPubKeys()))))))
      }

    case m @ AdjustAddressAmount(currency, address, adjustAmount) =>
      if (manager.canAdjustAddressAmount(address, adjustAmount)) {
        persist(m) {
          event =>
            updateState(event)
            sender ! AdjustAddressAmountResult(
              supportedCurrency, ErrorCode.Ok, address, Some(manager.getAddressAmount(address)))
        }
      } else {
        sender ! AdjustAddressAmountResult(supportedCurrency, ErrorCode.InvalidAmount, address)
      }

    case m @ AllocateNewAddress(currency, _, _) =>
      val (address, needFetch) = manager.allocateAddress
      if (needFetch) self ! FetchAddresses(currency)
      if (address.isDefined) {
        persist(m.copy(assignedAddress = address)) {
          event =>
            updateState(event)
            sender ! AllocateNewAddressResult(supportedCurrency, ErrorCode.Ok, address)
        }
      } else {
        sender ! AllocateNewAddressResult(supportedCurrency, ErrorCode.NotEnoughAddressInPool, None)
      }

    case p @ ConfirmablePersistent(m: BitwayTransferRequest, _, _) =>
      confirm(p)
      sendMultiTransferRequest(m.transfers)
      unlockTransfers(m.unlocks)

    case m: BitwayTransferRequest =>
      sendMultiTransferRequest(m.transfers)
      unlockTransfers(m.unlocks)

    case p @ ConfirmablePersistent(m: LockCoinRequest, _, _) =>
      confirm(p)
      lockTransfer(m)

    case m: LockCoinRequest =>
      lockTransfer(m)

    case m: QueryBlockChainAmount =>
      sender ! QueryBlockChainAmountResult(manager.getTotalReserveExternalAmount, manager.getLocked)

    case m @ BitwayMessage(currency, Some(res), None, None, None, None) =>
      if (res.error == ErrorCode.Ok) {
        persist(m) {
          event =>
            updateState(event)
        }
      } else {
        log.error("error occur when fetch addresses: " + res)
      }
    case m @ BitwayMessage(currency, None, Some(tx), None, None, None) =>
      val txWithTime = tx.copy(timestamp = Some(System.currentTimeMillis))
      if (tx.status == TransferStatus.Failed) {
        persist(m.copy(tx = Some(txWithTime))) {
          event =>
            channelToPayment forward Deliver(Persistent(BitcoinTxSeen(manager.convertToBitcoinTx(event.tx.get))),
              paymentActorPath)
        }
      } else {
        manager.completeCryptoCurrencyTransaction(txWithTime) match {
          case None => log.debug("unrelated tx received")
          case Some(completedTx) =>
            if (manager.notProcessed(completedTx)) {
              persist(m.copy(tx = Some(completedTx))) {
                event =>
                  updateState(event)
                  channelToPayment forward Deliver(Persistent(BitcoinTxSeen(manager.convertToBitcoinTx(event.tx.get))),
                    paymentActorPath)
              }
            }
        }
      }
    case m @ BitwayMessage(currency, None, None, Some(blockMsg), None, None) =>
      val continuity = manager.getBlockContinuity(blockMsg)
      // log.info("receive new block: " + blockMsg + " which recognizied as " + continuity)
      // log.info("maintained index list is: " + manager.getBlockIndexes)
      continuity match {
        case DUP => log.info("receive block which has been seen: " + blockMsg.block.index)
        case SUCCESSOR | REORG =>
          sendGetMissedBlockTime = 0
          val blockMsgWithTime = blockMsg.copy(timestamp = Some(System.currentTimeMillis))
          persist(m.copy(blockMsg = Some(blockMsgWithTime))) {
            event =>
              updateState(event)
              val relatedTxs = manager.extractTxsFromBlock(blockMsg.block)
              if (relatedTxs.nonEmpty) {
                val reorgIndex = if (continuity == REORG) {
                  log.info("reorg to index: " + blockMsg.reorgIndex)
                  log.info("maintained index list: " + manager.getBlockIndexes)
                  log.info("new block index: " + blockMsg.block.index)
                  blockMsg.reorgIndex
                } else None
                channelToPayment forward Deliver(Persistent(BitcoinBlockSeen(BitcoinBlock(blockMsg.block.index.height.get,
                  relatedTxs.map(manager.convertToBitcoinTx(_)).filter(_.txid.isDefined), blockMsgWithTime.timestamp.get),
                  reorgIndex.flatMap(i => if (i.height == null) None else i.height))), paymentActorPath)
              }
          }
        case GAP =>
          if (client.isDefined && System.currentTimeMillis - sendGetMissedBlockTime > RESEND_GET_MISSED_BLOCK_TIMEOUT) {
            sendGetMissedBlockTime = System.currentTimeMillis
            client.get.rpush(getRequestChannel, serializer.toBinary(BitwayRequest(
              BitwayRequestType.GetMissedBlocks, currency, getMissedCryptoCurrencyBlocksRequest = Some(
                GetMissedCryptoCurrencyBlocks(manager.getBlockIndexes.get, blockMsg.block.index)))))
          }
        case OTHER_BRANCH =>
          throw new RuntimeException("The crypto currency seems has multi branches: " + currency)
        case BAD =>
          log.info("bad block received: " + blockMsg)
          log.info("maintained index list: " + manager.getBlockIndexes)
      }
    case m @ BitwayMessage(currency, None, None, None, Some(res), None) =>
      if (res.error == ErrorCode.Ok) {
        persist(m) {
          event =>
            updateState(event)
        }
      } else {
        log.error("error occur when sync hot addresses: " + res)
      }
    case m @ BitwayMessage(currency, None, None, None, None, Some(res)) =>
      if (res.error == ErrorCode.Ok) {
        persist(m) {
          event =>
            updateState(event)
        }
      } else {
        log.error("error occur when sync private keys: " + res)
      }
  }

  private def sendMultiTransferRequest(m: Option[MultiTransferCryptoCurrency]) {
    if (client.isDefined && m.isDefined && m.get.transferInfos.nonEmpty) {
      val MultiTransferCryptoCurrency(currency, transferInfos) = m.get
      val passedInfos = collection.mutable.Map.empty[TransferType, Seq[CryptoCurrencyTransferInfo]]
      val failedInfos = collection.mutable.Map.empty[TransferType, Seq[CryptoCurrencyTransferInfo]]
      var hotAmount: Option[Long] = None
      // handle withdrawal before hotToCold by sorting transfers, avoid unnecessary hotToCold
      transferInfos.toSeq.sortBy(_._1.value).foreach {
        kv =>
          val (ps, fs, hm) = partitionInfos(kv._1, kv._2, hotAmount)
          hotAmount = hm
          if (ps.nonEmpty)
            passedInfos += kv._1 -> ps.toSeq
          if (fs.nonEmpty)
            failedInfos += kv._1 -> fs.toSeq
      }
      if (passedInfos.size > 0) {
        if (failedInfos.size > 0) {
          sender ! MultiTransferCryptoCurrencyResult(currency, ErrorCode.PartiallyFailed, Some(failedInfos))
        } else {
          sender ! MultiTransferCryptoCurrencyResult(currency, ErrorCode.Ok, None)
        }
        client.get.rpush(getRequestChannel, serializer.toBinary(BitwayRequest(BitwayRequestType.MultiTransfer, currency,
          multiTransferCryptoCurrency = Some(m.get.copy(transferInfos = passedInfos)))))
      } else {
        sender ! MultiTransferCryptoCurrencyResult(currency, ErrorCode.AllFailed, Some(failedInfos))
      }
    }
  }

  private def sendTransactionRequest(m: SendRawTransaction) {
    println(client.toString)
    if (client.isDefined) {
      val SendRawTransaction(signedHex) = m
      if (signedHex.length > 0 && signedHex.matches("^[0-9A-Fa-f]+$")) {
        client.get.rpush(getRequestChannel, serializer.toBinary(BitwayRequest(
          BitwayRequestType.SendRawTransaction, supportedCurrency, rawTransaction = Some(m))))
        sender ! SendRawTransactionResult(ErrorCode.Ok)
      } else {
        sender ! SendRawTransactionResult(ErrorCode.InvalidSignedTx)
      }

    }
  }

  private def partitionInfos(transferType: TransferType, infos: Seq[CryptoCurrencyTransferInfo], hotAmount: Option[Long] = None): (List[CryptoCurrencyTransferInfo], List[CryptoCurrencyTransferInfo], Option[Long]) = {
    import TransferType._

    val passedInfos = collection.mutable.ListBuffer.empty[CryptoCurrencyTransferInfo]
    val failedInfos = collection.mutable.ListBuffer.empty[CryptoCurrencyTransferInfo]
    val (resInfos, isFail) = manager.completeTransferInfos(infos, transferType == HotToCold)
    var hotAmountRes: Option[Long] = hotAmount

    def subHotAmount(info: CryptoCurrencyTransferInfo) {
      val tm = hotAmountRes.getOrElse(manager.getTotalReserveAmount)
      (info.internalAmount.get + manager.MIN_RESERVE_INTERNAL_AMOUNT) > tm match {
        case true =>
          failedInfos.append(info.copy(error = Some(ErrorCode.InsufficientHot)))
        case _ =>
          passedInfos.append(info)
          hotAmountRes = Some(tm - info.internalAmount.get)
      }
    }

    transferType match {
      case HotToCold =>
        isFail match {
          case true => failedInfos ++= resInfos
          case _ => resInfos foreach subHotAmount
        }
      case Withdrawal =>
        resInfos foreach {
          resInfo =>
            manager.isWithdrawalToBadAddress(resInfo) match {
              case true =>
                failedInfos.append(resInfo.copy(error = Some(ErrorCode.WithdrawalToBadAddress)))
              case false =>
                subHotAmount(resInfo)
            }
        }
      case _ =>
        passedInfos ++= resInfos
    }

    (passedInfos.toList, failedInfos.toList, hotAmountRes)
  }

  private def unlockTransfers(unlocks: Option[Seq[CryptoCurrencyTransferInfo]]) {
    unlocks match {
      case Some(uls) if uls.toList.filter(i => i.internalAmount.isDefined && i.internalAmount.get > 0).nonEmpty =>
        persist(UnlockCoinRequest(unlocks.get.filter(i => i.internalAmount.isDefined && i.internalAmount.get > 0))) {
          event =>
            updateState(event)
        }
      case _ =>
    }
  }

  private def lockTransfer(lockRequest: LockCoinRequest) {
    if (lockRequest.lockInfos.nonEmpty) {
      persist(lockRequest) {
        event =>
          updateState(event)
          manager.succeededLocks.nonEmpty match {
            case true =>
              manager.failLocks.isEmpty match {
                case true =>
                  channelToPayment forward Deliver(Persistent(LockCoinResult(manager.succeededLocks, Some(ErrorCode.Ok))), paymentActorPath)
                case false =>
                  channelToPayment forward Deliver(Persistent(LockCoinResult(manager.succeededLocks ++ manager.failLocks, Some(ErrorCode.PartiallyFailed))), paymentActorPath)
              }
            case false =>
              channelToPayment forward Deliver(Persistent(LockCoinResult(manager.failLocks, Some(ErrorCode.AllFailed))), paymentActorPath)
          }
      }
    } else {
      log.error(s"LockCoinRequest.lockInfos is empty : $lockRequest")
    }
  }

  private def scheduleTryPour() = {
    context.system.scheduler.scheduleOnce(delayinSeconds seconds, self, TryFetchAddresses(0))(context.system.dispatcher)
  }

  private def scheduleSyncHotAddresses() = {
    context.system.scheduler.scheduleOnce(delayinSeconds seconds, self, TrySyncHotAddresses(0))(context.system.dispatcher)
  }

  private def getRequestChannel = bcconfig.requestChannelPrefix + supportedCurrency.value.toString
}

trait BlockchainDataManagerBehavior extends {
  val manager: BlockchainDataManager
  implicit val logger: LoggingAdapter

  def updateState: Receive = {
    case m: CreateInvoice =>
      if (m.paymentAddress != Some(""))
        manager.addressAllocated(PAYMENT_ID, m.paymentAddress.get, m.expiredTime)
      manager.addMerchantCreateInvoice(m.merchantId, m.invoiceData)

    case AllocateNewAddress(currency, uid, Some(address)) => manager.addressAllocated(uid, address)

    case AdjustAddressAmount(currency, address, adjustAmount) => manager.adjustAddressAmount(address, adjustAmount)

    case BitwayMessage(currency, Some(res), None, None, None, None) =>
      if (res.addressType.isDefined && res.addresses.isDefined && res.addresses.get.size > 0)
        manager.faucetAddress(res.addressType.get, Set.empty[CryptoAddress] ++ res.addresses.get)

    case BitwayMessage(currency, None, Some(tx), None, None, None) =>
      if (tx.timestamp.isDefined) manager.updateLastAlive(tx.timestamp.get)
      manager.rememberTx(tx)

    case BitwayMessage(currency, None, None,
      Some(CryptoCurrencyBlockMessage(startIndex, block, timestamp)), None, None) =>
      if (timestamp.isDefined) {
        manager.updateLastAlive(timestamp.get)
        manager.recycleAddresses(timestamp.get)
      }
      manager.updateBlock(startIndex, block)

    case BitwayMessage(currency, None, None, None, Some(res), None) =>
      if (res.addresses.size > 0)
        manager.syncHotAddresses(Set.empty[CryptoAddress] ++ res.addresses)

    case BitwayMessage(currency, None, None, None, None, Some(res)) =>
      manager.syncPrivateKeys(res.addresses.toList)

    case CleanBlockChain(currency) =>
      manager.cleanBlockChain()

    case UnlockCoinRequest(unlocks) =>
      unlocks.foreach {
        l =>
          l.internalAmount match {
            case Some(amount) if amount > 0 =>
              manager.unlockWithdrawal(amount)
            case _ =>
          }
      }
    case LockCoinRequest(lockInfos) =>
      manager.succeededLocks.clear
      manager.failLocks.clear
      lockInfos.foreach {
        info =>
          info.innerType match {
            case Some(TransferType.Withdrawal) if info.amount > 0 =>
              manager.lockWithdrawal(info.amount) match {
                case true =>
                  manager.succeededLocks += info
                case false =>
                  manager.failLocks += info.copy(reason = Some(ErrorCode.InsufficientFund))
              }
            case _ =>
              manager.failLocks += info.copy(reason = Some(ErrorCode.UnsupportTransferType))
              logger.error(s"LockCoinRequest is invalid at info : ${info.toString}")
          }
      }
  }
}

class BlockchainReceiver(routers: LocalRouters, supportedCurrency: Currency, bcconfig: BlockchainConfig)
    extends Actor with ActorLogging {
  implicit val executeContext = context.system.dispatcher

  val serializer = new ThriftBinarySerializer()
  val client: Option[RedisClient] = try {
    Some(new RedisClient(bcconfig.ip, bcconfig.port))
  } catch {
    case ex: Throwable => None
  }

  override def preStart = {
    super.preStart
    listenAtRedis(10)
  }

  def receive = LoggingReceive {
    case e: ListenAtRedis =>
      if (client.isDefined) {
        client.get.blpop[String, Array[Byte]](1, getResponseChannel) match {
          case Some(s) =>
            val response = serializer.fromBinary(s._2, classOf[BitwayMessage.Immutable])
            routers.blockchainActor ! response
          // listenAtRedis()
          case None => None
          // listenAtRedis(5)
        }
        listenAtRedis()
      }
  }

  def getResponseChannel = bcconfig.responseChannelPrefix + supportedCurrency.value.toString

  private def listenAtRedis(timeout: Long = 0) {
    context.system.scheduler.scheduleOnce(timeout seconds, self, ListenAtRedis(0))(context.system.dispatcher)
  }
}
