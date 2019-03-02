package com.coinport.bitway.actors.payment

import akka.actor._
import akka.actor.Cancellable
import akka.event.{ LoggingAdapter, LoggingReceive }
import akka.pattern.ask
import akka.persistence._
import akka.util.Timeout

import com.coinport.bitway.LocalRouters
import com.coinport.bitway.actors.notification.MandrillMailHandler
import com.coinport.bitway.api.model.CurrencyConversion._
import com.coinport.bitway.api.model.CurrencyWrapper
import com.coinport.bitway.actors.invoice.InvoiceConfig
import com.coinport.bitway.actors.payment.transfer._
import com.coinport.bitway.common._
import com.coinport.bitway.data._
import com.coinport.bitway.data.Currency.Btc
import com.coinport.bitway.data.ErrorCode._
import com.coinport.bitway.data.TransferStatus._
import com.mongodb.casbah.Imports._
import com.twitter.util.Eval

import java.io.InputStream
import org.apache.commons.io.IOUtils
import scala.concurrent.duration._
import scala.util.{ Failure, Success }

import TransferType._
import InvoiceStatus._

/**
 * This actor should be a persistent actor.
 *
 * Responsible for:
 * - generate new invoices
 * - keeps invoices that could change status in memory
 * - update invoice status
 * - notify InvoiceAccessActor new invoice states
 *
 */

class PaymentActor(val routers: LocalRouters, val db: MongoDB, mailer: MandrillMailHandler) extends ExtendedProcessor
    with EventsourcedProcessor with CryptoCurrencyTransferBehavior with ActorLogging {
  override def processorId = "invoice"

  implicit val manager = new PaymentManager()
  lazy implicit val logger: LoggingAdapter = log

  lazy val invoiceAccessActorPath = routers.invoiceAccessActor.path
  lazy val tradingProxyPath = routers.tradingProxy.path
  lazy val accountActorPath = routers.accountActor.path
  lazy val blockchainActorPath = routers.blockchainActor.path

  lazy val channelToInvoiceAccess = createChannelTo("iaccess")
  lazy val channelToTrader = createChannelTo("trader")
  lazy val channelToAccount = createChannelTo("account")
  lazy val channelToBlockchain = createChannelTo("blockchain_btc")

  implicit val executionContext = context.system.dispatcher
  implicit val timeout = Timeout(2 seconds)

  val accountTransferConfig = loadConfig[AccountTransferConfig](context.system.settings.config.getString("akka.payment.transfer-path"))
  val invoiceConfig = loadConfig[InvoiceConfig](context.system.settings.config.getString("akka.invoice.config-path"))

  val transferConfig = TransferConfig(manualCurrency = Some(accountTransferConfig.manualCurrency), enableAutoConfirm = Some(accountTransferConfig.enableAutoConfirm))

  private var cancellable: Cancellable = null

  setSucceededRetainNum(accountTransferConfig.succeededRetainNum)
  intTransferHandlerObjectMap()

  override def preStart = {
    super.preStart
    // pingFEProxy
  }

  override def identifyChannel: PartialFunction[Any, String] = {
    case ci: CreateInvoice => "bca"
    case btxs: BitcoinTxSeen => "bca"
    case bbs: BitcoinBlockSeen => "bca"
    case lr: LockCoinResult => "bca"
    case st: MerchantSettle => "aa"
    case st: DoPaymentRequest => "aa"
  }

  def receiveRecover = PartialFunction.empty[Any, Unit]

  def receiveCommand = LoggingReceive {

    case m: PingFeProxy =>
      routers.wsDelegateActors ! "ping"
      pingFEProxy

    case "pong" =>
      log.info("connected with frontend proxy")
      if (cancellable != null && !cancellable.isCancelled) cancellable.cancel()

    case BestBtcRatesUpdated(bidRates, askRates) =>
      manager.setRates(scala.collection.mutable.Map.empty ++ bidRates, scala.collection.mutable.Map.empty ++ askRates)

    case p @ ConfirmablePersistent(m: CreateInvoice, _, _) =>
      confirm(p)
      if ((!m.paymentAddress.isDefined || m.paymentAddress.get.isEmpty) && !invoiceConfig.isTest) {
        sender ! InvoiceCreationFailed(ErrorCode.FetchAddressFail, "Failed to fetch payment address")
      } else if (m.invoiceData.price < 0.0) {
        sender ! InvoiceCreationFailed(ErrorCode.PriceIsNegative, "Price is negative")
      } else if (exceedPermitted(m.invoiceData.currency, m.invoiceData.price)) {
        sender ! InvoiceCreationFailed(ErrorCode.ExceedMaxPrice, "Price exceeds upper-limit")
      } else {
        manager.createInvoice(m.invoiceData, m.merchantId, m.merchantName, m.paymentAddress.get, invoiceConfig.defaultBidRates) match {
          case Some((Some(invoice), _)) =>
            persist(invoice) {
              event =>
                updateState(event)
                channelToInvoiceAccess forward Deliver(Persistent(InvoiceCreated(invoice)), invoiceAccessActorPath)
            }
          case None =>
            sender ! InvoiceCreationFailed(ErrorCode.CalculatePriceFail, "Can't calculate btc price")
          case res =>
            sender ! InvoiceCreationFailed(res.get._2.get._1, res.get._2.get._2)
        }
      }

    case p @ ConfirmablePersistent(m: BitcoinTxSeen, _, _) =>
      log.info("bitcoin tx seen: " + m)
      confirm(p)
      persist(m) {
        event =>
          updateState(event)
          sendAccountMsg(sendInvoiceUpdates(event, false, true))
      }

    case p @ ConfirmablePersistent(m: BitcoinBlockSeen, _, _) =>
      log.info("bitcoin block seen: " + m)
      confirm(p)
      persist(m.copy(confirmNum = accountTransferConfig.confirmNumMap.get(Btc))) {
        event =>
          updateState(event)
          sendAccountMsg(sendInvoiceUpdates(event, false, true))
          sendBitwayMsg()
          notifyRefunded()
          notifyPayment()
      }

    case p @ InvoiceDebugRequest(id, status) =>
      logger.info(s"id $id, status : ${status.name}")
      persist(p) {
        event =>
          manager.getInvoice(id) match {
            case Some(invoice) if invoice.paymentAddress.nonEmpty =>
              status match {
                case Paid | InvoiceStatus.Confirmed | Complete | Expired =>
                  composeAndSendMsg(invoice, status)
                case _ =>
                  logger.error(s"Invalid invoice status to debug ${status.name}")
              }
            case _ =>
              logger.error(s"Invoice not exists $id or paymentAddress invlid")
          }
      }

    case p: GetBestBidRates =>
      sender ! BestBidRates(manager.bidRates)

    case p: GetBestAskRates =>
      sender ! BestAskRates(manager.askRates)

    case p: GetCurrentExchangeRate =>
      sender ! getCurrencyExchangeRate(p.baseCurrency, p.targetCurrency, p.baseCurrencyAmount, p.targetCurrencyAmount)

    case p @ ConfirmablePersistent(MerchantSettle(w: AccountTransfer), _, _) =>
      confirm(p)
      w.`type` match {
        case Settle => // accept wait for admin accept
          val msg = MerchantSettle(w.copy(id = manager.getTransferId, updated = w.created))
          persist(msg) {
            event =>
              updateState(event)
          }
          sender ! RequestTransferSucceeded(msg.transfer) // wait for admin confirm
          sendSettleMail(w)
        case _ =>
          sender ! RequestTransferFailed(UnsupportTransferType)
      }

    case p @ ConfirmablePersistent(DoPaymentRequest(t: AccountTransfer), _, _) =>
      confirm(p)
      t.`type` match {
        case Payment =>
          val paymentCurrency = t.paymentData.get.currency.get
          val paymentAmount = t.paymentData.get.externalAmount.get
          val (canTrading, coinAmount) = isTransferByBitway(paymentCurrency, Some(transferConfig)) match {
            case true => (true, paymentAmount)
            case false => manager.getCoinAmount(paymentCurrency, paymentAmount, invoiceConfig.defaultAskRates)
          }
          canTrading match {
            case true =>
              val updated = t.copy(
                id = manager.getTransferId,
                currency = Currency.Btc,
                amount = new CurrencyWrapper(coinAmount).internalValue(Btc),
                externalAmount = Some(coinAmount),
                updated = Some(System.currentTimeMillis()),
                innerType = Some(Withdrawal),
                status = Accepted)
              persist(DoPaymentRequest(updated)) {
                event =>
                  updateState(event)
                  deliverToBitwayProcessor(LockCoinRequest(List(updated)))
                  if (!isTransferByBitway(paymentCurrency, Some(transferConfig))) {
                    // Payment currency is not btc, should buy btc from market
                    channelToTrader ! Deliver(Persistent(DoPaymentRequest(updated)), tradingProxyPath)
                  }
              }
              sender ! RequestTransferSucceeded(updated)
            case false =>
              // Release locked payment amount
              routers.accountActor ! AdminConfirmTransferFailure(t, ErrorCode.UnableTradeOnMarket)
              sender ! RequestTransferFailed(UnableTradeOnMarket)
          }
        case _ =>
          sender ! RequestTransferFailed(UnsupportTransferType)
      }

    case p @ DoSendCoin(trs) =>
      val updates = trs.filter(i => i.`type` == SendCoin && i.address.isDefined && i.address.get.nonEmpty && i.amount > 0).map {
        tr =>
          val trId = manager.getTransferId
          manager.setLastTransferId(trId)
          tr.copy(id = trId, updated = tr.created, innerType = Some(Withdrawal))
      }
      if (updates.nonEmpty) {
        val msg = DoSendCoin(updates)
        persist(msg) {
          event =>
            updateState(event)
            deliverToBitwayProcessor(LockCoinRequest(updates))
        }
      } else {
        logger.error(s"meet invalid sendCoin request : ${p.toString}")
      }

    case p @ MerchantRefund(w: AccountTransfer) =>
      //      println(s"WWWWWWWWWWWWWWWWWWWWWWW : ${w.toString}")
      w.`type` match {
        case Refund if w.address.isDefined && w.refundAmount.isDefined && w.refundAmount.get > 0.0 && w.invoiceId.isDefined =>
          val outerSender = sender
          routers.invoiceAccessActor ? QueryInvoiceById(w.invoiceId.get) onComplete {
            case Success(e: QueryInvoiceResult) if e.invoices.nonEmpty =>
              //              println(s">>>>>>>>>>>>>>>>>>>> Success QueryInvoiceResult ${e.toString}")
              if (w.merchantId == e.invoices.head.merchantId) {
                val invoiceCurrency = e.invoices.head.data.currency
                val (canTrading, coinAmount) = isTransferByBitway(invoiceCurrency, Some(transferConfig)) match {
                  case true => (true, w.refundAmount.get)
                  case false => manager.getCoinAmount(invoiceCurrency, w.refundAmount.get, invoiceConfig.defaultAskRates)
                }
                if (exceedPermitted(invoiceCurrency, w.refundAmount.get)) {
                  outerSender ! RequestTransferFailed(ErrorCode.ExceedMaxPrice)
                } else if (canTrading) {
                  // println(s">>>>>>>>>>>>>>>>>>>> canTrading $canTrading, coinAmount $coinAmount, invoiceCurrency $invoiceCurrency")
                  val timestamp = System.currentTimeMillis()
                  val rfIAm = new CurrencyWrapper(w.refundAmount.get).internalValue(invoiceCurrency)
                  val filledAmount = w.copy(
                    currency = Btc,
                    amount = new CurrencyWrapper(coinAmount).internalValue(Btc),
                    innerType = Some(Withdrawal),
                    externalAmount = Some(coinAmount),
                    refundCurrency = Some(invoiceCurrency),
                    refundInternalAmount = Some(rfIAm))
                  val withFee = computeRefundFee(filledAmount)
                  routers.accountActor ? LockAccountRequest(w.merchantId, withFee.refundCurrency.get, withFee.refundInternalAmount.get, false) onComplete {
                    case Success(e: LockAccountResult) if e.isSuccess == true =>
                      //                      println(s">>>>>>>>>>>>>>>>>>>> Success LockAccountResult ${e.toString}")
                      val updated = withFee.copy(
                        id = manager.getTransferId,
                        created = Some(timestamp),
                        updated = Some(timestamp))
                      val msg = MerchantRefund(updated)
                      persist(msg) {
                        event =>
                          updateState(event)
                      }
                      //                      println(s">>>>>>>>>>>>>>>>>>>> success ${msg.transfer.toString}")
                      outerSender ! RequestTransferSucceeded(msg.transfer)
                    case Failure(e) =>
                      //                      println(s">>>>>>>>>>>>>>>>>>>> Failure LockAccountResult ${e.toString}")
                      logger.error(s"Try lock refund amount failed : ${e.getStackTrace.toString}")
                      outerSender ! RequestTransferFailed(LockRefundAccountFail)
                    case _ =>
                      //                      println(s">>>>>>>>>>>>>>>>>>>> other LockAccountResult ${e.toString}")
                      outerSender ! RequestTransferFailed(InsufficientFund)
                  }
                } else {
                  //                  println(s">>>>>>>>>>>>>>>>>>>> UnableTradeOnMarket")
                  outerSender ! RequestTransferFailed(UnableTradeOnMarket)
                }
              } else {
                //                println(s">>>>>>>>>>>>>>>>>>>> MerchantNotValid")
                outerSender ! RequestTransferFailed(ErrorCode.MerchantNotValid)
              }
            case Failure(e) =>
              logger.error(s"Can't Found invoice to refund: ${e.getStackTrace.toString}")
              //              println(s">>>>>>>>>>>>>>>>>>>> Failure QueryInvoiceResult ${e.toString}")
              outerSender ! RequestTransferFailed(ErrorCode.Unknown)
            case _ =>
              //              println(s">>>>>>>>>>>>>>>>>>>> InvoiceNotExist")
              outerSender ! RequestTransferFailed(ErrorCode.InvoiceNotExist)
          }
        case _ =>
          //          println(s">>>>>>>>>>>>>>>>>>>> UnsupportTransferType")
          sender ! RequestTransferFailed(UnsupportTransferType)
      }

    case p @ ConfirmablePersistent(LockCoinResult(trs, error), _, _) =>
      confirm(p)
      val updates: Seq[Option[AccountTransfer]] = trs.map {
        tr =>
          transferHandler.get(tr.id) match {
            case Some(transfer) if tr.reason == None || tr.reason == Some(Ok) =>
              Some(transfer.copy(updated = Some(System.currentTimeMillis), status = Locked))
            case Some(transfer) if tr.reason.isDefined && tr.reason != Some(Ok) =>
              transfer.`type` match {
                case Settle | Refund | Payment =>
                  Some(transfer.copy(updated = Some(System.currentTimeMillis), status = LockInsufficient))
                case SendCoin =>
                  Some(transfer.copy(updated = Some(System.currentTimeMillis), status = LockFailed))
                case _ =>
                  None
              }
            case _ =>
              None
          }
      }
      persist(LockCoinResult(updates.filter(_.isDefined).map(_.get), error)) {
        event =>
          updateState(event)
          if (error != Some(ErrorCode.AllFailed)) {
            sendBitwayMsg() // Unlock
          }
      }

    case AdminConfirmTransferFailure(t, error) =>
      transferHandler.get(t.id) match {
        case Some(transfer) if transfer.status == Pending || transfer.status == Processing || transfer.status == LockInsufficient || transfer.status == BitwayFailed =>
          val toStatus = transfer.status match {
            case Pending => Rejected
            case Processing => ProcessedFail
            case LockInsufficient => LockFailed
            case BitwayFailed => ConfirmBitwayFail
            case _ => TransferStatus.Failed
          }
          val updated = transfer.copy(updated = Some(System.currentTimeMillis), status = toStatus, reason = Some(error))
          persist(AdminConfirmTransferFailure(updated, error)) {
            event =>
              transfer.`type` match {
                case Refund | Payment if toStatus == Rejected || toStatus == LockFailed || toStatus == ConfirmBitwayFail =>
                  deliverToAccountManager(event)
                  if (transfer.invoiceId.isDefined && transfer.`type` == Refund) {
                    notifyRefunded(Some(List(InvoiceUpdate(transfer.invoiceId.get, InvoiceStatus.RefundFailed))))
                  } else if (transfer.paymentData.isDefined && transfer.`type` == Payment) {
                    notifyPayment(Some(List(transfer)))
                  }
                case Settle =>
                  deliverToAccountManager(event)
                  sendSettleMail(updated)
                case _ =>
              }
              updateState(event)
              sender ! AdminCommandResult(Ok)
          }
        case Some(_) => sender ! AdminCommandResult(AlreadyConfirmed)
        case None => sender ! AdminCommandResult(TransferNotExist)
      }

    case DoCancelSettle(t) =>
      cancelTransfer(t)

    case DoCancelTransfer(t) =>
      cancelTransfer(t)

    case AdminConfirmTransferSuccess(t: AccountTransfer) =>
      transferHandler.get(t.id) match {
        case Some(transfer) if transfer.status == Pending || transfer.status == Processing || transfer.status == LockInsufficient || transfer.status == BitwayFailed =>
          if (isTransferByBitway(transfer.currency, Some(transferConfig))) {
            transfer.`type` match {
              case Settle | Payment if transfer.address.isDefined =>
                val updated = transfer.copy(updated = Some(System.currentTimeMillis), status = Accepted)
                persist(AdminConfirmTransferSuccess(updated)) {
                  event =>
                    updateState(event)
                    deliverToBitwayProcessor(LockCoinRequest(List(updated)))
                }
                sender ! AdminCommandResult(Ok)
                if (transfer.`type` == Settle) {
                  sendSettleMail(updated)
                }
              case Refund =>
                val timestamp = System.currentTimeMillis()
                transfer.status match {
                  case Pending =>
                    val (canTrading, checkedTimeout) = (timestamp - transfer.updated.get > accountTransferConfig.refundTimeOut) match {
                      case false => (true, transfer)
                      case true =>
                        isTransferByBitway(transfer.refundCurrency.get, Some(transferConfig)) match {
                          case true => (true, transfer)
                          case false =>
                            manager.getCoinAmount(transfer.refundCurrency.get, transfer.refundAmount.get, invoiceConfig.defaultAskRates) match {
                              case (true, coinAmount) =>
                                (true, transfer.copy(amount = new CurrencyWrapper(coinAmount).internalValue(Btc), externalAmount = Some(coinAmount), updated = Some(timestamp)))
                              case (false, _) =>
                                (false, transfer)
                            }
                        }
                    }
                    if (canTrading) {
                      val updated = checkedTimeout.copy(updated = Some(timestamp), status = Accepted)
                      persist(AdminConfirmTransferSuccess(updated)) {
                        event =>
                          updateState(event)
                          deliverToBitwayProcessor(LockCoinRequest(List(updated)))
                          if (!isTransferByBitway(updated.refundCurrency.get, Some(transferConfig))) {
                            // Refund currency is not btc, should buy btc from market
                            channelToTrader ! Deliver(Persistent(MerchantRefund(updated)), tradingProxyPath)
                          }
                      }
                    } else {
                      // keep status as pending for further confirm again
                      val updated = transfer.copy(updated = Some(System.currentTimeMillis), reason = Some(ErrorCode.UnableTradeOnMarket))
                      persist(AdminConfirmTransferSuccess(updated))(updateState)
                      sender ! AdminCommandResult(ErrorCode.UnableTradeOnMarket)
                    }
                  case LockInsufficient | BitwayFailed =>
                    val updated = transfer.copy(updated = Some(System.currentTimeMillis), status = Accepted)
                    persist(AdminConfirmTransferSuccess(updated)) {
                      event =>
                        updateState(event)
                        deliverToBitwayProcessor(LockCoinRequest(List(updated)))
                    }
                    sender ! AdminCommandResult(Ok)
                  case _ =>
                    sender ! AdminCommandResult(AlreadyConfirmed)
                }
              case _ =>
                sender ! AdminCommandResult(UnsupportTransferType)
            }
          } else {
            transfer.`type` match {
              case Settle if transfer.status == Pending =>
                val updated = transfer.copy(updated = Some(System.currentTimeMillis), status = Processing)
                persist(AdminConfirmTransferSuccess(updated)) {
                  event =>
                    updateState(event)
                }
                sender ! AdminCommandResult(Ok)
                sendSettleMail(updated)
              case _ if transfer.status == Processing && t.status == Processing => // check whether admin double clicked to send same msg with pending status
                val updated = transfer.copy(updated = Some(System.currentTimeMillis), status = Succeeded)
                persist(AdminConfirmTransferSuccess(updated)) {
                  event =>
                    deliverToAccountManager(event)
                    updateState(event)
                }
                sender ! AdminCommandResult(Ok)
                if (transfer.`type` == Settle) sendSettleMail(updated)
              case _ =>
                sender ! AdminCommandResult(UnsupportTransferType)
            }
          }
        case Some(_) => sender ! AdminCommandResult(AlreadyConfirmed)
        case None => sender ! AdminCommandResult(TransferNotExist)
      }

    case mr: MultiTransferCryptoCurrencyResult =>
      if (mr.error != ErrorCode.Ok && mr.transferInfos.isDefined && mr.transferInfos.get.nonEmpty) {
        persist(mr.copy(timestamp = Some(System.currentTimeMillis()))) {
          event =>
            updateState(event)
            sendAccountMsg()
            sendBitwayMsg() // Unlock
            notifyRefunded()
            notifyPayment()
        }
      }

    case q: QueryTransfer =>
      q.transferId match {
        case Some(tId) =>
          transferHandler.get(tId) match {
            case Some(t) => sender ! QueryTransferResult(List(prepareTransfer(t, q.fromAdmin == Some(true))), 1)
            case _ => sender ! QueryTransferResult(List.empty, 0)
          }
        case _ =>
          val query = transferHandler.getQueryDBObject(q)
          val count = transferHandler.count(query)
          val items = transferHandler.find(query, q.cur.skip, q.cur.limit)
          sender ! QueryTransferResult(items.map(prepareTransfer(_, q.fromAdmin == Some(true))), count)
      }

  }

  private def cancelTransfer(t: AccountTransfer) {
    transferHandler.get(t.id) match {
      case Some(transfer) if transfer.status == Pending =>
        if (t.merchantId == transfer.merchantId) {
          val updated = transfer.copy(updated = Some(System.currentTimeMillis), status = Cancelled, reason = Some(ErrorCode.MerchantCancelled))
          transfer.`type` match {
            case Settle =>
              persist(DoCancelSettle(updated)) {
                event =>
                  sender ! AdminCommandResult(Ok)
                  deliverToAccountManager(event)
                  updateState(event)
                  sendSettleMail(updated)
              }
            case Refund =>
              persist(DoCancelTransfer(updated)) {
                event =>
                  sender ! AdminCommandResult(Ok)
                  deliverToAccountManager(event)
                  updateState(event)
              }
            case _ =>
              sender ! AdminCommandResult(UnsupportTransferType)
          }
        } else {
          sender ! AdminCommandResult(MerchantAuthenFail)
        }
      case Some(_) => sender ! AdminCommandResult(AlreadyConfirmed)
      case None => sender ! AdminCommandResult(TransferNotExist)
    }
  }

  // isPendingAccount is true: InvoiceComplete should batch send with other message to account
  private def sendInvoiceUpdates(e: AnyRef, isDebug: Boolean = false, isBatchAccount: Boolean = false): Option[InvoiceComplete] = {
    var invoiceComplete: Option[InvoiceComplete] = None
    val (actions, notifications, originals) = manager.getUpdateRes
    if (actions.nonEmpty && !isDebug) {
      log.info("actions: " + actions)
      channelToInvoiceAccess ! Deliver(Persistent(InvoiceUpdated(actions.map(_.get))), invoiceAccessActorPath)
      val completeInvoices = actions.filter(i => takeAsConfirmed(i, originals)).map(i => manager.invoices.get(i.get.id)).filter(_.isDefined)
      if (completeInvoices.nonEmpty) {
        invoiceComplete = Some(InvoiceComplete(completeInvoices.map(_.get)))
        if (!isBatchAccount) {
          channelToAccount ! Deliver(Persistent(invoiceComplete.get), accountActorPath)
        }
      }
    }
    if (notifications.nonEmpty) {
      log.info("notifications : " + notifications)
      val notificationMsg = NotifyMerchants(notifications.map(_.get))
      routers.wsDelegateActors ! notificationMsg
      if (!isDebug)
        channelToTrader ! Deliver(Persistent(notificationMsg), tradingProxyPath)
      //      routers.wsDelegateActors ! notificationMsg
      routers.emailNotificationActor ! notificationMsg
    }
    invoiceComplete
  }

  private def takeAsConfirmed(action: Option[InvoiceUpdate], originals: collection.Map[String, Invoice]): Boolean = {
    if (action.isEmpty || !originals.contains(action.get.id))
      return false
    if (action.get.status == InvoiceStatus.Confirmed) {
      val original = originals(action.get.id)
      original.status match {
        // if original.data.transactionSpeed == 0 && original.lastPaymentBlockHeight.isEmpty
        case InvoiceStatus.New => true
        case InvoiceStatus.Paid => true // ReorgingConfirmed should not set confirmed
        case _ => false
      }
    } else {
      false
    }
  }

  private def pingFEProxy {
    cancellable = context.system.scheduler.scheduleOnce(5 seconds, self, PingFeProxy(0))(context.system.dispatcher)
  }

  private def exceedPermitted(currency: Currency, amount: Double): Boolean = {
    if (!invoiceConfig.maxPrice.contains(currency)) {
      true
    } else {
      amount > invoiceConfig.maxPrice(currency)
    }
  }

  private def getCurrencyExchangeRate(baseCurrency: Currency, targetCurrency: Currency, baseAmount: Option[Double], targetAmount: Option[Double]): CurrentExchangeRateResult = {
    if (baseCurrency != Currency.Btc || targetCurrency == Currency.Btc)
      return CurrentExchangeRateResult(ErrorCode.InvalidCurrency, None, None)
    if (baseAmount.isEmpty && targetAmount.isEmpty || baseAmount.nonEmpty && targetAmount.nonEmpty)
      return CurrentExchangeRateResult(ErrorCode.InvalidAmount, None, None)
    if (baseAmount.isDefined && exceedPermitted(baseCurrency, baseAmount.get) || targetAmount.isDefined && exceedPermitted(targetCurrency, targetAmount.get))
      return CurrentExchangeRateResult(ErrorCode.ExceedMaxPrice, None, None)

    def getPrices(givenRates: collection.Map[Currency, ExchangeRate], defaultRates: collection.Map[Currency, ExchangeRate], lawCurrency: Currency): collection.Map[Double, Double] = {
      givenRates.get(lawCurrency) match {
        case Some(rates) if rates.piecewisePrice.nonEmpty => rates.piecewisePrice
        case _ =>
          defaultRates.get(lawCurrency) match {
            case Some(defaults) if defaults.piecewisePrice.nonEmpty =>
              log.error(s"getCurrencyExchangeRate try to use default Rates for $lawCurrency, ${defaults.piecewisePrice}")
              defaults.piecewisePrice
            case _ => Map.empty
          }
      }
    }

    val askPrices = getPrices(manager.askRates, invoiceConfig.defaultAskRates, targetCurrency)
    val bidPrices = getPrices(manager.bidRates, invoiceConfig.defaultBidRates, targetCurrency)

    if (baseAmount.isDefined) {
      val buyingRate = askPrices.filter(kv => kv._1 > baseAmount.get) match { //buy btc
        case validPieces if validPieces.nonEmpty =>
          Some(validPieces.values.toList.sorted.head)
        case _ => None
      }
      val sellingRate = bidPrices.filter(kv => kv._1 / kv._2 >= baseAmount.get) match { // sell btc
        case validPieces if validPieces.nonEmpty =>
          Some(validPieces.values.toList.sorted.reverse.head)
        case _ => None
      }
      CurrentExchangeRateResult(ErrorCode.Ok, buyingRate, sellingRate)
    } else {
      val buyingRate = bidPrices.filter(kv => kv._1 > targetAmount.get) match { // sell btc, buy law currency
        case validPieces if validPieces.nonEmpty =>
          val validPrice = validPieces.values.toList.sorted.reverse.head
          Some(roundExternal(1 / validPrice, baseCurrency))
        case _ => None
      }
      val sellingRate = askPrices.filter(kv => kv._1 * kv._2 > targetAmount.get) match { // buy btc, sell law currency
        case validPieces if validPieces.nonEmpty =>
          Some(roundExternal(1 / validPieces.values.toList.sorted.head, baseCurrency))
        case _ => None
      }
      CurrentExchangeRateResult(ErrorCode.Ok, buyingRate, sellingRate)
    }
  }

  private def sendBitwayMsg() {
    val transfersToBitway = batchBitwayTransfers(Btc)
    val unlocksToBitway = batchBitwayUnlocks(Btc)
    if (transfersToBitway.nonEmpty || unlocksToBitway.nonEmpty) {
      deliverToBitwayProcessor(BitwayTransferRequest(transfersToBitway, unlocksToBitway))
    }
  }

  private def sendAccountMsg(invoiceComplete: Option[InvoiceComplete] = None) {
    val transfersToAccount: collection.Map[String, AccountTransfersWithMinerFee] = batchAccountMessage(Btc)
    if (transfersToAccount.nonEmpty || invoiceComplete.isDefined) {
      deliverToAccountManager(CryptoTransferResult(transfersToAccount, invoiceComplete))
    }
  }

  private def notifyRefunded(withUpdates: Option[List[InvoiceUpdate]] = None) {
    val updates: List[InvoiceUpdate] = withUpdates match {
      case Some(ups) => ups
      case _ => batchInvoicesAccess(Btc)
    }
    val succeeds = updates.filter(_.status != InvoiceStatus.RefundFailed)
    val fails = updates.filter(_.status == InvoiceStatus.RefundFailed)

    if (succeeds.nonEmpty) {
      routers.invoiceAccessActor ? InvoicePartialUpdate(succeeds) onComplete {
        case Success(e: QueryInvoiceResult) if e.invoices.nonEmpty =>
          notifyInvoices(e.invoices.map(_.copy(status = InvoiceStatus.Refunded)))
        case Failure(e) =>
          logger.error(s"update refunded invoices fail, ${succeeds}, with exception: ${e.getStackTrace.toString}")
        case _ =>
          logger.warning(s"InvoicePartialUpdate update Nothing: ${succeeds}")
      }
    }

    if (fails.nonEmpty) {
      routers.invoiceAccessActor ? QueryInvoiceByIds(fails map { i => i.id }) onComplete {
        case Success(e: QueryInvoiceResult) if e.invoices.nonEmpty =>
          notifyInvoices(e.invoices.map(_.copy(status = InvoiceStatus.RefundFailed)))
        case Failure(e) =>
          logger.error(s"Query RefundFailed invoices fail, ${succeeds}, with exception: ${e.getStackTrace.toString}")
        case _ =>
          logger.warning(s"Query RefundFailed invoice got nothing: ${succeeds}")
      }
    }
  }

  private def notifyInvoices(invoices: Seq[Invoice]) {
    val urlNotifications = invoices.filter(i => i.data.notificationURL.isDefined && i.data.notificationURL.get.nonEmpty)
      .map(i => InvoiceNotification(i.data.notificationURL.get, i))
    if (urlNotifications.nonEmpty)
      routers.wsDelegateActors ! NotifyMerchants(urlNotifications)
    val emailNotifications = invoices.filter(i => i.data.notificationEmail.isDefined && i.data.notificationEmail.get.nonEmpty)
      .map(i => InvoiceNotification("", i))
    if (emailNotifications.nonEmpty)
      routers.emailNotificationActor ! NotifyMerchants(emailNotifications)
  }

  private def notifyPayment(withPays: Option[List[AccountTransfer]] = None) {
    val pays = withPays match {
      case Some(ps) => ps
      case _ => batchPayments(Btc)
    }
    val succeeds = pays.filter(_.status == TransferStatus.Succeeded)
    val fails = pays.filter(_.status != TransferStatus.Succeeded)
    if (succeeds.nonEmpty) notifyPaymentTransfer(succeeds)
    if (fails.nonEmpty) notifyPaymentTransfer(fails)
  }

  private def notifyPaymentTransfer(pays: Seq[AccountTransfer]) {
    val urlNotifications = pays.filter(i => i.paymentData.isDefined && i.paymentData.get.notificationUrl.isDefined && i.paymentData.get.notificationUrl.get.nonEmpty)
      .map(i => PaymentNotification(i.paymentData.get.notificationUrl.get, i))
    if (urlNotifications.nonEmpty) {
      routers.wsDelegateActors ! NotifyPayments(urlNotifications)
    }
    val emailNotifications = batchPayments(Btc)
      .filter(i => i.paymentData.isDefined && i.paymentData.get.notificationEmail.isDefined && i.paymentData.get.notificationEmail.get.nonEmpty)
      .map(i => PaymentNotification("", i))
    if (emailNotifications.nonEmpty)
      routers.emailNotificationActor ! NotifyPayments(emailNotifications)
  }

  private def sendSettleMail(w: AccountTransfer) {
    mailer.sendMail("chunming@coinport.com;d@coinport.com", "pay-settle-notification",
      Seq(
        "MERCHANT_ID" -> String.valueOf(w.merchantId),
        "TIME" -> new java.text.SimpleDateFormat("YYYY-MM-dd HH:mm:ss").format(java.util.Calendar.getInstance().getTime),
        "STATUS" -> w.status.name,
        "ADDRESS" -> w.address.getOrElse(""),
        "AMOUNT" -> s"${w.externalAmount.getOrElse(0.0)}(${w.currency.name})"))
  }

  private def deliverToAccountManager(event: Any) = {
    log.info(s">>>>>>>>>>>>>>>>>>>>> deliverToAccountManager => event = ${event.toString}")
    if (!recoveryRunning) {
      channelToAccount ! Deliver(Persistent(event), accountActorPath)
    }
  }

  private def deliverToBitwayProcessor(event: Any) = {
    log.info(s">>>>>>>>>>>>>>>>>>>>> deliverToBitwayProcessor => event = ${event.toString}")
    if (!recoveryRunning) {
      channelToBlockchain ! Deliver(Persistent(event), blockchainActorPath)
    }
  }

  private def deliverToInvoicesAccess(event: Any) = {
    log.info(s">>>>>>>>>>>>>>>>>>>>> deliverToInvoiceAccessProcessor => event = ${event.toString}")
    if (!recoveryRunning) {
      channelToInvoiceAccess ! Deliver(Persistent(event), invoiceAccessActorPath)
    }
  }

  private def loadConfig[T](configPath: String): T = {
    val in: InputStream = this.getClass.getClassLoader.getResourceAsStream(configPath)
    (new Eval()(IOUtils.toString(in))).asInstanceOf[T]
  }

  private def computeRefundFee(transfer: AccountTransfer): AccountTransfer = {
    if (true) return transfer
    val DEFAULT_REFUND_RATE = 0.005
    if (transfer.refundCurrency.isDefined && transfer.refundAmount.isDefined && transfer.refundAmount.get > 0) {
      var feeAmount = accountTransferConfig.refundFeeRate.get(transfer.refundCurrency.get) match {
        case Some(rate) if rate > 0.0 => transfer.refundAmount.get * rate
        case _ => transfer.refundAmount.get * DEFAULT_REFUND_RATE
      }
      val feeInternalAmount = new CurrencyWrapper(feeAmount).internalValue(transfer.refundCurrency.get)
      if (feeInternalAmount == 0)
        return transfer
      feeAmount = new CurrencyWrapper(feeInternalAmount).externalValue(transfer.refundCurrency.get)
      val fee = Some(Fee(transfer.merchantId, None, transfer.refundCurrency.get, feeInternalAmount, None, Some(feeAmount)))
      val newRefundInternalAmount = transfer.refundInternalAmount.get + feeInternalAmount
      val newRefundAmount = new CurrencyWrapper(newRefundInternalAmount).externalValue(transfer.refundCurrency.get)
      transfer.copy(fee = fee, refundAmount = Some(newRefundAmount), refundInternalAmount = Some(newRefundInternalAmount))
    } else {
      transfer
    }
  }

  private def composeAndSendMsg(invoice: Invoice, status: InvoiceStatus) {
    manager.initState()
    val (newInvoice, msg): (Invoice, AnyRef) = status match {
      case Paid =>
        (invoice.copy(status = InvoiceStatus.New), composeTx(invoice))
      case Expired =>
        (invoice.copy(status = InvoiceStatus.New), composeBlock("fk_txid", 100, invoice.expirationTime + 1000))
      case InvoiceStatus.Confirmed =>
        val paidHeight = 100L
        val txid = "bk_ti"
        val ni = invoice.copy(status = InvoiceStatus.Paid, btcPaid = Some(invoice.btcPrice), txid = Some("bk_ti"), updateTime = System.currentTimeMillis(), lastPaymentBlockHeight = Some(paidHeight))
        val block = composeBlock(txid, (invoice.data.transactionSpeed + paidHeight).toInt, System.currentTimeMillis())
        (ni, block)
      case Complete =>
        val paidHeight = 100L
        val txid = "fk_txid"
        val ni = invoice.copy(status = InvoiceStatus.Confirmed, btcPaid = Some(invoice.btcPrice), txid = Some(txid), updateTime = System.currentTimeMillis(), lastPaymentBlockHeight = Some(paidHeight))
        val block = composeBlock(txid, (paidHeight + 5).toInt, System.currentTimeMillis())
        (ni, block)
      case _ =>
        logger.error(s"Invalid status ${status.name}")
        (invoice, null)
    }
    manager.update(msg, true, List(newInvoice))
    sendInvoiceUpdates(msg, true)
  }

  private def composeTx(invoice: Invoice): BitcoinTxSeen = {
    BitcoinTxSeen(BitcoinTx(Some("tx_sigId"), Some("tx_txid"), None, Some(List(BitcoinTxPort(invoice.paymentAddress, Some(invoice.btcPrice)))), None, System.currentTimeMillis(), Some(TransferStatus.Confirming), Some(TransferType.Deposit)))
  }

  private def composeBlock(txid: String, height: Int, blockTime: Long): BitcoinBlockSeen = {
    BitcoinBlockSeen(BitcoinBlock(height, List(BitcoinTx(Some("fk_si"), Some(txid), None, None, None, System.currentTimeMillis(), Some(TransferStatus.Confirming), Some(TransferType.Deposit))), blockTime))
  }

  private def prepareTransfer(transfer: AccountTransfer, isFromAdmin: Boolean = false): AccountTransfer = {
    val refundedTransfer = transfer.status match {
      case Pending if transfer.`type` == Refund =>
        isTransferByBitway(transfer.refundCurrency.get, Some(transferConfig)) match {
          case true => transfer
          case false => manager.getCoinAmount(transfer.refundCurrency.get, transfer.refundAmount.get, invoiceConfig.defaultAskRates) match {
            case (true, coinAmount) =>
              transfer.copy(externalAmount = Some(coinAmount), amount = new CurrencyWrapper(coinAmount).internalValue(Btc))
            case (false, _) =>
              transfer.copy(status = UnableTradeFail, externalAmount = Some(0.0), amount = 0L)
          }
        }
      case _ => transfer
    }

    isFromAdmin match {
      case true => refundedTransfer
      case false => refundedTransfer.copy(status = mapStatus(refundedTransfer.status))
    }
  }
}
