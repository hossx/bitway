package com.coinport.bitway.actors.payment.transfer

import akka.event.LoggingAdapter

import com.coinport.bitway.actors.payment.PaymentManager
import com.coinport.bitway.common.mongo.SimpleJsonMongoCollection
import com.coinport.bitway.data._
import com.coinport.bitway.data.TransferStatus._
import com.coinport.bitway.data.TransferType._

import scala.collection.mutable.Map
import scala.collection.mutable.ListBuffer

trait CryptoCurrencyTransferBase {
  val sigId2MinerFeeMap: Map[String, Long] = Map.empty[String, Long]
  val id2HandlerMap: Map[Long, CryptoCurrencyTransferHandler] = Map.empty[Long, CryptoCurrencyTransferHandler]
  val succeededId2HandlerMap: Map[Long, CryptoCurrencyTransferHandler] = Map.empty[Long, CryptoCurrencyTransferHandler]
  val msgBoxMap: Map[Long, CryptoCurrencyTransferItem] = Map.empty[Long, CryptoCurrencyTransferItem]

  var manager: PaymentManager = null
  var transferHandler: SimpleJsonMongoCollection[AccountTransfer, AccountTransfer.Immutable] = null
  var transferItemHandler: SimpleJsonMongoCollection[CryptoCurrencyTransferItem, CryptoCurrencyTransferItem.Immutable] = null
  var logger: LoggingAdapter = null
  var succeededRetainNum = collection.immutable.Map.empty[Currency, Int]

  def getNoneSigId = "NONE_SIGID_" + System.currentTimeMillis().toString

  implicit var env: TransferEnv = null

  def setEnv(env: TransferEnv): CryptoCurrencyTransferBase = {
    this.env = env
    manager = env.manager
    transferHandler = env.transferHandler
    transferItemHandler = env.transferItemHandler
    logger = env.logger
    succeededRetainNum = env.succeededRetainNum
    this
  }

  def init(): CryptoCurrencyTransferBase = {
    msgBoxMap.clear()
    this
  }

  def handleTx(currency: Currency, tx: BitcoinTx, timestamp: Option[Long], includedBlockHeight: Option[Long] = None) {
    refreshLastBlockHeight(currency, includedBlockHeight)
    innerHandleTx(currency, tx, timestamp, includedBlockHeight)
  }

  def handleBackcoreFail(info: CryptoCurrencyTransferInfo, currency: Currency, timestamp: Option[Long], error: Option[ErrorCode]) {
    if (id2HandlerMap.contains(info.id)) {
      handleFailed(id2HandlerMap(info.id).setTimeStamp(timestamp), error)
    } else {
      logger.warning(s"""${"~" * 50} ${currency.toString} bitway Fail not match existing item : id2HandleMap.size = ${id2HandlerMap.size}, info = ${info.toString}""")
    }
  }

  protected def innerHandleTx(currency: Currency, tx: BitcoinTx, timestamp: Option[Long], includedBlockHeight: Option[Long] = None) {}

  def checkConfirm(currency: Currency, timestamp: Option[Long], confirmNum: Option[Int]) {
    val lastBlockHeight: Long = manager.getLastBlockHeight(currency)
    id2HandlerMap.values filter (_.item.currency == currency) foreach {
      handler =>
        handler.setTimeStamp(timestamp).setConfirmNum(confirmNum)
        if (handler.checkConfirm(lastBlockHeight) && handler.item.status.get == Succeeded) {
          handleSucceeded(handler.item.id)
        }
    }
    succeededId2HandlerMap.values filter (_.item.currency == currency) foreach {
      handler =>
        if (handler.checkRemoveSucceeded(lastBlockHeight)) {
          succeededId2HandlerMap.remove(handler.item.id)
        }
    }
  }

  def loadSnapshotItems(snapshotItems: Option[collection.Map[Long, CryptoCurrencyTransferItem]]) {
    id2HandlerMap.clear()
    if (snapshotItems.isDefined)
      snapshotItems.get.values map {
        item => id2HandlerMap.put(item.id, newHandlerFromItem(item))
      }
  }

  def loadSnapshotSucceededItems(snapshotItems: Option[collection.Map[Long, CryptoCurrencyTransferItem]]) {
    succeededId2HandlerMap.clear()
    if (snapshotItems.isDefined)
      snapshotItems.get.values map {
        item => succeededId2HandlerMap.put(item.id, newHandlerFromItem(item))
      }
  }

  def loadSigId2MinerFeeMap(snapshotMap: Option[collection.Map[String, Long]]) {
    sigId2MinerFeeMap.clear()
    if (snapshotMap.isDefined)
      sigId2MinerFeeMap ++= snapshotMap.get
  }

  def getTransferItemsMap(): Map[Long, CryptoCurrencyTransferItem] = {
    id2HandlerMap map {
      kv => kv._1 -> kv._2.item.copy()
    }
  }

  def getSucceededItemsMap(): Map[Long, CryptoCurrencyTransferItem] = {
    succeededId2HandlerMap map {
      kv => kv._1 -> kv._2.item.copy()
    }
  }

  def getSigId2MinerFeeMap(): Map[String, Long] = {
    sigId2MinerFeeMap.clone()
  }

  def reOrganize(currency: Currency, reOrgBlockHeight: Long, manager: PaymentManager, timestamp: Option[Long]) {
    if (reOrgBlockHeight < manager.getLastBlockHeight(currency)) {
      id2HandlerMap.values.filter(_.item.currency == currency) foreach {
        _.setTimeStamp(timestamp).reOrgnize(reOrgBlockHeight)
      }
      succeededId2HandlerMap.values filter (_.item.currency == currency) foreach {
        handler =>
          if (handler.setTimeStamp(timestamp).reOrgnizeSucceeded(reOrgBlockHeight)) {
            succeededId2HandlerMap.remove(handler.item.id)
          }
      }
    } else {
      logger.warning(s"""${"~" * 50} ${currency.toString} reOrgnize() wrong reOrgnize height : [${manager.getLastBlockHeight(currency)}, ${reOrgBlockHeight}]""")
    }
  }

  def getMsgToBitway(currency: Currency): List[CryptoCurrencyTransferInfo] = {
    val resList = ListBuffer.empty[CryptoCurrencyTransferInfo]
    msgBoxMap.values.filter(_.currency == currency) foreach {
      item =>
        item2BitwayInfo(item) match {
          case Some(info) =>
            resList += info
          case _ =>
        }
    }
    resList.toList
  }

  def getMsgToInvoiceAccess(currency: Currency): List[InvoiceUpdate] = {
    val resList = ListBuffer.empty[InvoiceUpdate]
    msgBoxMap.values.filter(_.currency == currency) foreach {
      item =>
        item2InvoiceAccess(item) match {
          case Some(info) =>
            resList += info
          case _ =>
        }
    }
    resList.toList
  }

  def getMsgToPayment(currency: Currency): List[AccountTransfer] = {
    val resList = ListBuffer.empty[AccountTransfer]
    msgBoxMap.values.filter(_.currency == currency) foreach {
      item =>
        item2Payment(item) match {
          case Some(info) =>
            resList += info
          case _ =>
        }
    }
    resList.toList
  }

  def getUnlockTransfers(currency: Currency): List[CryptoCurrencyTransferInfo] = {
    val resList = ListBuffer.empty[CryptoCurrencyTransferInfo]
    msgBoxMap.values.filter(_.currency == currency) foreach {
      item =>
        item2UnlockInfo(item) match {
          case Some(info) =>
            resList += info
          case _ =>
        }
    }
    resList.toList
  }

  // currency, (sigId, (list[AccountTransfer], minerFee))
  def getMsgToAccount(currency: Currency): Map[String, (ListBuffer[AccountTransfer], Option[Long])] = {
    val resMap = Map.empty[String, (ListBuffer[AccountTransfer], Option[Long])]
    val noneSigId = getNoneSigId
    msgBoxMap.values foreach {
      item =>
        item2AccountTransfer(item) match {
          case Some(info) if item.currency == currency =>
            val sigIdForKey = item.sigId match {
              //Failed item will not contain sigId
              case Some(sigId) => sigId
              case _ => noneSigId
            }
            if (!resMap.contains(sigIdForKey)) {
              val minerFee = item.status match {
                // only count Succeed transactions minerFee
                case Some(Succeeded) if item.sigId.isDefined => sigId2MinerFeeMap.remove(sigIdForKey)
                case Some(Succeeded) if !item.sigId.isDefined =>
                  logger.error(s"""${"~" * 50} getMsgToAccount succeeded item without sigId defined : ${item.toString}""")
                  None
                case _ => None
              }
              resMap.put(sigIdForKey, (ListBuffer.empty[AccountTransfer], minerFee))
            }
            resMap(sigIdForKey)._1.append(info)
          case _ =>
        }
    }
    resMap
  }

  protected def item2BitwayInfo(item: CryptoCurrencyTransferItem): Option[CryptoCurrencyTransferInfo] = {
    item.status match {
      case Some(Confirming) =>
        item2CryptoCurrencyTransferInfo(item)
      case _ =>
        logger.warning(s"""${"~" * 50} item2BitwayInfo() get unexpected item : ${item.toString}""")
        None
    }
  }

  protected def item2InvoiceAccess(item: CryptoCurrencyTransferItem): Option[InvoiceUpdate] = {
    item.status match {
      case Some(Succeeded) =>
        item2InvoiceUpdate(item)
      case _ =>
        logger.warning(s"""${"~" * 50} item2InvoiceAccess() get unexpected item : ${item.toString}""")
        None
    }
  }

  protected def item2Payment(item: CryptoCurrencyTransferItem): Option[AccountTransfer] = {
    item.status match {
      case Some(Succeeded) =>
        item2PaymentInfo(item)
      case _ =>
        None
    }
  }

  protected def item2UnlockInfo(item: CryptoCurrencyTransferItem): Option[CryptoCurrencyTransferInfo] = { None }

  protected def item2CryptoCurrencyTransferInfo(item: CryptoCurrencyTransferItem): Option[CryptoCurrencyTransferInfo] = None

  protected def item2InvoiceUpdate(item: CryptoCurrencyTransferItem): Option[InvoiceUpdate] = None

  protected def item2PaymentInfo(item: CryptoCurrencyTransferItem): Option[AccountTransfer] = None

  protected def item2AccountTransfer(item: CryptoCurrencyTransferItem): Option[AccountTransfer] = {
    item.status match {
      case Some(Succeeded) => // batch Set miner fee before sent message to accountProcessor
        transferHandler.get(item.accountTransferId.get)
      case Some(Failed) if item.txType.get != UserToHot && item.txType.get != ColdToHot && item.txType.get != HotToCold => //UserToHot fail will do nothing
        transferHandler.get(item.accountTransferId.get)
      case _ =>
        logger.warning(s"""${"~" * 50} item2AccountTransfer() meet unexpected item : ${item.toString}""")
        None
    }
  }

  protected def newHandlerFromItem(item: CryptoCurrencyTransferItem): CryptoCurrencyTransferHandler

  protected def handleSucceeded(itemId: Long) {
    id2HandlerMap.remove(itemId) match {
      case Some(handler) =>
        handler.onSucceeded()
        succeededId2HandlerMap.put(itemId, handler)
        msgBoxMap.put(handler.item.id, handler.item)
      case _ =>
    }
  }

  protected def handleFailed(handler: CryptoCurrencyTransferHandler, error: Option[ErrorCode] = None) {}

  protected def updateSigId2MinerFee(tx: BitcoinTx) {
    tx.minerFee match {
      case Some(fee) if tx.sigId.isDefined =>
        if (tx.txType.isDefined && tx.txType.get != Deposit) { //Deposit should ignore minerFee
          tx.status match {
            case Some(Failed) => sigId2MinerFeeMap.remove(tx.sigId.get)
            case _ => sigId2MinerFeeMap.put(tx.sigId.get, fee)
          }
        }
      case Some(_) =>
        logger.error(s"""${"~" * 50} updateSigId2MinerFee minerFee defined without sigId defined : ${tx.toString}""")
      case None =>
    }
  }

  def refreshLastBlockHeight(currency: Currency, includedBlockHeight: Option[Long] = None) {
    val height = includedBlockHeight.getOrElse(0L)
    if (manager.getLastBlockHeight(currency) < height) manager.setLastBlockHeight(currency, height)
  }

}

trait CryptoCurrencyTransferWithdrawalLikeBase extends CryptoCurrencyTransferBase {

  def newHandlerFromAccountTransfer(t: AccountTransfer, from: Option[CryptoCurrencyTransactionPort], to: Option[CryptoCurrencyTransactionPort], timestamp: Option[Long]) {
    val handler = new CryptoCurrencyTransferWithdrawalLikeHandler(t, from, to, timestamp)
    msgBoxMap.put(handler.item.id, handler.item)
    id2HandlerMap.put(handler.item.id, handler)
  }

  override def newHandlerFromItem(item: CryptoCurrencyTransferItem): CryptoCurrencyTransferHandler = {
    new CryptoCurrencyTransferWithdrawalLikeHandler(item)
  }

  override def handleFailed(handler: CryptoCurrencyTransferHandler, error: Option[ErrorCode] = None) {
    val item = id2HandlerMap.remove(handler.item.id).get.item
    msgBoxMap.put(item.id, item)
  }

  override def innerHandleTx(currency: Currency, tx: BitcoinTx, timestamp: Option[Long], includedBlockHeight: Option[Long] = None) {
    tx.ids match {
      case Some(idList) if idList.size > 0 =>
        idList foreach {
          //every input corresponds to one tx
          id =>
            if (id2HandlerMap.contains(id)) {
              tx.status match {
                case Some(Failed) =>
                  handleFailed(id2HandlerMap(id).setTimeStamp(timestamp))
                case _ =>
                  id2HandlerMap(id).setTimeStamp(timestamp).onNormal(tx, includedBlockHeight)
              }
            } else {
              logger.warning(s"""${"~" * 50} ${currency.toString} innerHandlerTx() ${tx.txType.get.toString} item id ${id} not contained in id2HandlerMap : ${tx.toString}""")
            }
        }
        updateSigId2MinerFee(tx)
      case _ =>
        logger.warning(s"""${"~" * 50} ${currency.toString} innerHandlerTx() ${tx.txType.get.toString} tx not define ids : ${tx.toString}""")
    }
  }
}