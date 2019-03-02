package com.coinport.bitway.actors.payment.transfer

import akka.actor.Actor._
import akka.event.LoggingAdapter

import com.coinport.bitway.api.model.CurrencyWrapper
import com.coinport.bitway.actors.payment.PaymentManager
import com.coinport.bitway.common.mongo.SimpleJsonMongoCollection
import com.coinport.bitway.data._
import com.coinport.bitway.data.Fee
import com.coinport.bitway.serializers.ThriftEnumJson4sSerialization
import com.mongodb.casbah.Imports._

import org.json4s._
import scala.collection.mutable.{ Map, ListBuffer }
import com.coinport.bitway.data.Currency.Btc

import TransferType._
import TransferStatus._
import com.coinport.bitway.data.TransferStatus.Locked

trait CryptoCurrencyTransferBehavior {

  val db: MongoDB
  implicit val manager: PaymentManager
  implicit val logger: LoggingAdapter

  val transferHandlerObjectMap = Map.empty[TransferType, CryptoCurrencyTransferBase]
  var succeededRetainNum = collection.Map.empty[Currency, Int]

  def setSucceededRetainNum(succeededRetainNum: collection.Map[Currency, Int]) = {
    this.succeededRetainNum ++= succeededRetainNum
  }

  def intTransferHandlerObjectMap() {
    val env = new TransferEnv(manager, transferHandler, transferItemHandler, logger, succeededRetainNum)
    transferHandlerObjectMap += Withdrawal -> CryptoCurrencyTransferWithdrawalHandler.setEnv(env)
    manager.setTransferHandlers(transferHandlerObjectMap)
    CryptoCurrencyTransferUnknownHandler.setEnv(env)
  }

  def isTransferByBitway(currency: Currency, transferConfig: Option[TransferConfig]): Boolean = {
    currency == Currency.Btc && (transferConfig.isEmpty || !(transferConfig.get.manualCurrency.getOrElse(Set.empty[Currency]).contains(currency)))
  }

  def updateState: Receive = {
    case e: Invoice => manager.addInvoice(e)
    case e: BitcoinTxSeen =>
      logger.info(s">>>>>>>>>>>>>>>>>>>>> updateState  => ${e.toString}")
      manager.initState() // Clear msg box, avoid send message again
      e.newTx.txType match {
        case Some(TransferType.Deposit) =>
          manager.update(e)
        case Some(TransferType.Withdrawal) =>
          handleTx(e)
        case _ =>
      }
    case e: BitcoinBlockSeen =>
      logger.info(s">>>>>>>>>>>>>>>>>>>>> updateState  => ${e.toString}")
      manager.initState()
      manager.update(e)
      handleBlock(e)
    case MerchantSettle(t) =>
      transferHandler.put(t)
      manager.setLastTransferId(t.id)
    case DoPaymentRequest(t: AccountTransfer) =>
      transferHandler.put(t)
      manager.setLastTransferId(t.id)
    case DoSendCoin(ts) =>
      ts.foreach {
        tr =>
          transferHandler.put(tr)
          if (manager.getTransferId - 1 < tr.id)
            manager.setLastTransferId(tr.id)
      }
    case MerchantRefund(t) =>
      transferHandler.put(t)
      manager.setLastTransferId(t.id)
    case LockCoinResult(trs, _) =>
      val handler = transferHandlerObjectMap(TransferType.Withdrawal).asInstanceOf[CryptoCurrencyTransferWithdrawalLikeBase]
      handler.init()
      trs.foreach {
        t =>
          t.`type` match {
            case Settle | SendCoin | Refund | Payment =>
              if (t.status == Locked) {
                val transferAmount = t.fee match {
                  // when Refund, the amount is merchant input coin amount, the fee is currency in law, which shouldn't be sub
                  case Some(withdrawalFee: Fee) if withdrawalFee.amount > 0 && t.`type` != Refund => t.amount - withdrawalFee.amount
                  case _ => t.amount
                }
                val to = CryptoCurrencyTransactionPort(t.address.get, Some(new CurrencyWrapper(transferAmount).externalValue(Btc)), Some(transferAmount), Some(t.merchantId))
                prepareBitwayMsg(t, None, Some(to), handler, t.updated)
              }
            case _ =>
          }
          transferHandler.put(t)
      }

    case AdminConfirmTransferSuccess(t) => transferHandler.put(t)

    case DoCancelSettle(t) => transferHandler.put(t)

    case DoCancelTransfer(t) => transferHandler.put(t)

    case AdminConfirmTransferFailure(t, _) => transferHandler.put(t)

    case mr @ MultiTransferCryptoCurrencyResult(currency, _, transferInfos, timestamp) =>
      logger.info(s">>>>>>>>>>>>>>>>>>>>> updateState  => ${mr.toString}")
      transferHandlerObjectMap.values foreach { _.init() }
      transferInfos.get.keys foreach {
        txType =>
          transferInfos.get.get(txType).get foreach {
            info =>
              transferHandlerObjectMap(txType).handleBackcoreFail(info, currency, timestamp, info.error)
          }
      }
    case InvoiceDebugRequest(id, st) =>
  }

  def batchAccountMessage(currency: Currency): Map[String, AccountTransfersWithMinerFee] = {
    val multiAccountTransfers = Map.empty[String, AccountTransfersWithMinerFee]
    transferHandlerObjectMap.keys map {
      key =>
        val sigId2AccountTransferMap: Map[String, (ListBuffer[AccountTransfer], Option[Long])] = transferHandlerObjectMap(key).getMsgToAccount(currency)
        if (!sigId2AccountTransferMap.isEmpty) {
          sigId2AccountTransferMap.keys map {
            sigId =>
              val tansfersWithMinerFee = sigId2AccountTransferMap(sigId)
              if (!tansfersWithMinerFee._1.isEmpty) {
                multiAccountTransfers.put(sigId, AccountTransfersWithMinerFee(tansfersWithMinerFee._1.toList, tansfersWithMinerFee._2))
              }
          }
        }
    }
    multiAccountTransfers
  }

  def batchBitwayTransfers(currency: Currency): Option[MultiTransferCryptoCurrency] = {
    val multiCryptoCurrencyTransfers = Map.empty[TransferType, List[CryptoCurrencyTransferInfo]]
    transferHandlerObjectMap.keys map {
      key =>
        val infos = transferHandlerObjectMap(key).getMsgToBitway(currency)
        if (infos.nonEmpty)
          multiCryptoCurrencyTransfers.put(key, infos)
    }
    multiCryptoCurrencyTransfers.nonEmpty match {
      case false => None
      case true => Some(MultiTransferCryptoCurrency(currency, multiCryptoCurrencyTransfers))
    }
  }

  def batchInvoicesAccess(currency: Currency): List[InvoiceUpdate] = {
    val updates = ListBuffer.empty[InvoiceUpdate]
    transferHandlerObjectMap.keys map {
      key =>
        val ups = transferHandlerObjectMap(key).getMsgToInvoiceAccess(currency)
        if (ups.nonEmpty)
          updates ++= ups
    }
    updates.toList
  }

  def batchBitwayUnlocks(currency: Currency): Option[List[CryptoCurrencyTransferInfo]] = {
    val unlockTransfers = ListBuffer.empty[CryptoCurrencyTransferInfo]
    transferHandlerObjectMap.keys map {
      key =>
        val infos = transferHandlerObjectMap(key).getUnlockTransfers(currency)
        if (infos.nonEmpty)
          unlockTransfers ++= infos
    }
    unlockTransfers.nonEmpty match {
      case false => None
      case true => Some(unlockTransfers.toList)
    }
  }

  def batchPayments(currency: Currency): List[AccountTransfer] = {
    val updates = ListBuffer.empty[AccountTransfer]
    transferHandlerObjectMap.keys map {
      key =>
        val ups = transferHandlerObjectMap(key).getMsgToPayment(currency)
        if (ups.nonEmpty)
          updates ++= ups
    }
    updates.toList
  }

  def prepareBitwayMsg(transfer: AccountTransfer, from: Option[CryptoCurrencyTransactionPort],
    to: Option[CryptoCurrencyTransactionPort], handler: CryptoCurrencyTransferWithdrawalLikeBase, timestamp: Option[Long]) {
    handler.newHandlerFromAccountTransfer(transfer, from, to, timestamp)
  }

  private def handleTx(msg: BitcoinTxSeen) = {
    transferHandlerObjectMap.values foreach {
      _.init()
    }
    handleTxCommon(msg.newTx, msg.newTx.timestamp)
  }

  def handleBlock(msg: BitcoinBlockSeen) {
    transferHandlerObjectMap.values foreach { _.init() }
    if (msg.newBlock.txs.isEmpty) { // Refresh blockHeight when no  txs
      transferHandlerObjectMap.values.foreach(_.refreshLastBlockHeight(Btc, Some(msg.newBlock.height)))
    } else {
      msg.newBlock.txs foreach { handleTxCommon(_, msg.newBlock.timestamp, Some(msg.newBlock.height)) }
    }
    transferHandlerObjectMap.values foreach {
      _.checkConfirm(Btc, Some(msg.newBlock.timestamp), msg.confirmNum)
    }

    if (manager.getLastBlockHeight(Btc) > 0) msg.reorgHeight foreach {
      reOrgBlockHeight =>
        transferHandlerObjectMap.values foreach {
          _.reOrganize(Btc, reOrgBlockHeight, manager, Some(msg.newBlock.timestamp))
        }
    }
  }

  def handleTxCommon(tx: BitcoinTx, timestamp: Long, includedBlockHeight: Option[Long] = None) {
    tx.txType match {
      case None =>
        logger.warning(s"Unexpected tx meet : ${tx.toString}")
        CryptoCurrencyTransferUnknownHandler.handleTx(Btc, tx, None)
      case Some(txType) =>
        transferHandlerObjectMap.contains(txType) match {
          case true =>
            transferHandlerObjectMap(txType).handleTx(Btc, tx, Some(timestamp), includedBlockHeight)
          case _ =>
            logger.warning(s"Unknown tx meet : ${tx.toString}")
        }
    }
  }

  implicit val transferHandler = new SimpleJsonMongoCollection[AccountTransfer, AccountTransfer.Immutable]() {
    lazy val coll = db("transfers")
    override implicit val formats: Formats = ThriftEnumJson4sSerialization.formats + new FeeSerializer() + new PaymentDataSerializer()
    def extractId(item: AccountTransfer) = item.id

    def getQueryDBObject(q: QueryTransfer): MongoDBObject = {
      var query = MongoDBObject()
      if (q.merchantId.isDefined) query ++= MongoDBObject(DATA + "." + AccountTransfer.MerchantIdField.name -> q.merchantId.get)
      if (q.currency.isDefined) query ++= MongoDBObject(DATA + "." + AccountTransfer.CurrencyField.name -> q.currency.get.name)
      if (q.types.nonEmpty) query ++= $or(q.types.map(t => DATA + "." + AccountTransfer.TypeField.name -> t.toString): _*)
      if (q.status.isDefined) {
        if (q.fromAdmin != Some(true)) { // From frontend query
          query ++= $or(inverseMapStatus(q.status.get).map(t => DATA + "." + AccountTransfer.StatusField.name -> t.toString): _*)
        } else {
          query ++= MongoDBObject(DATA + "." + AccountTransfer.StatusField.name -> q.status.get.name)
        }
      }
      if (q.spanCur.isDefined) query ++= (DATA + "." + AccountTransfer.CreatedField.name $lte q.spanCur.get.from $gte q.spanCur.get.to)
      query
    }
  }

  implicit val transferItemHandler = new SimpleJsonMongoCollection[CryptoCurrencyTransferItem, CryptoCurrencyTransferItem.Immutable]() {
    lazy val coll = db("transferitems")
    def extractId(item: CryptoCurrencyTransferItem) = item.id
  }

  protected def inverseMapStatus(origin: TransferStatus): List[TransferStatus] = {
    origin match {
      case Confirming => List(Confirming, Confirmed, Reorging, Processing, LockInsufficient, Locked, BitwayFailed)
      case Succeeded => List(ReorgingSucceeded, Succeeded)
      case Failed => List(Failed, Rejected, LockFailed, ProcessedFail, ConfirmBitwayFail, ReorgingFail, LockInsufficientFail, RefundTimeOut, RefundLockAccountErr, RefundInsufficientFail, UnableTradeFail)
      case st => List(st)
    }
  }

  protected def mapStatus(origin: TransferStatus): TransferStatus = {
    origin match {
      case TransferStatus.Confirmed => Confirming
      case Reorging => Confirming
      case ReorgingSucceeded => Succeeded
      case Rejected => Failed
      case Processing => Confirming
      case LockFailed => Failed
      case LockInsufficient => Confirming
      case Locked => Confirming
      case BitwayFailed => Confirming
      case ProcessedFail => Failed
      case ConfirmBitwayFail => Failed
      case ReorgingFail => Failed
      case LockInsufficientFail => Failed
      case RefundTimeOut => Failed
      case RefundLockAccountErr => Failed
      case RefundInsufficientFail => Failed
      case UnableTradeFail => Failed
      case st => st
    }
  }
}

class FeeSerializer(implicit man: Manifest[Fee.Immutable]) extends CustomSerializer[Fee](format => ({
  case obj: JValue => Extraction.extract(obj)(ThriftEnumJson4sSerialization.formats, man)
}, {
  case x: Fee => Extraction.decompose(x)(ThriftEnumJson4sSerialization.formats)
}))

class PaymentDataSerializer(implicit man: Manifest[PaymentData.Immutable]) extends CustomSerializer[PaymentData](format => ({
  case obj: JValue => Extraction.extract(obj)(ThriftEnumJson4sSerialization.formats, man)
}, {
  case x: PaymentData => Extraction.decompose(x)(ThriftEnumJson4sSerialization.formats)
}))

class TransferEnv(val manager: PaymentManager,
  val transferHandler: SimpleJsonMongoCollection[AccountTransfer, AccountTransfer.Immutable],
  val transferItemHandler: SimpleJsonMongoCollection[CryptoCurrencyTransferItem, CryptoCurrencyTransferItem.Immutable],
  val logger: LoggingAdapter,
  val succeededRetainNum: collection.immutable.Map[Currency, Int])
