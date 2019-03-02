package com.coinport.bitway.actors.payment

import com.coinport.bitway.util.Base58Coder
import com.coinport.bitway.actors.invoice._
import com.coinport.bitway.actors.payment.transfer.CryptoCurrencyTransferBase
import com.coinport.bitway.api.model.CurrencyConversion
import com.coinport.bitway.common._
import com.coinport.bitway.data._

import org.slf4s.Logging
import scala.collection.mutable.{ Map, ListBuffer }

class PaymentManager(forTest: Boolean = false) extends Manager[TInvoiceState] with Logging {

  import InvoiceStatus._
  val MIN_BTC_PRICE = 0.0001

  val rand = new scala.util.Random

  val TIME_TO_EXPIRATION_IN_MINUTE = 15
  val TIME_TO_INVALID_IN_MINUTE = 90
  val priceFloatingPercentage = 0.005
  val LOWEST_REASONABLE_PRICE = 10.0

  val invoices = Map.empty[String, Invoice]
  val fsm = new InvoiceFSM()

  var bidRates: collection.Map[Currency, ExchangeRate] = Map.empty
  var askRates: collection.Map[Currency, ExchangeRate] = Map.empty

  private var lastTransferId = 1E12.toLong
  private var lastTransferItemId = 6E12.toLong
  private var lastBlockHeight = Map.empty[Currency, Long]
  private val transferMapInnner = Map.empty[TransferType, Map[Long, CryptoCurrencyTransferItem]]
  private val succeededMapInnner = Map.empty[TransferType, Map[Long, CryptoCurrencyTransferItem]]
  private val sigId2MinerFeeMapInnner = Map.empty[TransferType, Map[String, Long]]
  private var transferHandlerObjectMap = Map.empty[TransferType, CryptoCurrencyTransferBase]

  private var invoiceUpdates = ListBuffer.empty[Option[InvoiceUpdate]]
  private var invoiceNotifications = ListBuffer.empty[Option[InvoiceNotification]]
  private var updateOriginals = Map.empty[String, Invoice]

  def getSnapshot = {
    transferMapInnner.clear()
    succeededMapInnner.clear()
    sigId2MinerFeeMapInnner.clear()
    transferHandlerObjectMap.keys foreach {
      txType =>
        val handler = transferHandlerObjectMap(txType)
        transferMapInnner.put(txType, handler.getTransferItemsMap())
        succeededMapInnner.put(txType, handler.getSucceededItemsMap())
        sigId2MinerFeeMapInnner.put(txType, handler.getSigId2MinerFeeMap())
    }

    TInvoiceState(
      getFiltersSnapshot,
      invoices.clone,
      lastTransferId,
      lastTransferItemId,
      lastBlockHeight,
      transferMapInnner,
      succeededMapInnner,
      sigId2MinerFeeMapInnner
    )
  }

  def loadSnapshot(snapshot: TInvoiceState) = {
    loadFiltersSnapshot(snapshot.filters)
    invoices.clear
    invoices ++= snapshot.invoices
    lastTransferId = snapshot.lastTransferId
    lastTransferItemId = snapshot.lastTransferItemId
    lastBlockHeight ++= snapshot.lastBlockHeight
    transferHandlerObjectMap.keys foreach {
      txType =>
        val handler = transferHandlerObjectMap(txType)
        handler.loadSnapshotItems(snapshot.transferMap.get(txType))
        handler.loadSnapshotSucceededItems(snapshot.succeededMap.get(txType))
        handler.loadSigId2MinerFeeMap(snapshot.sigId2MinerFeeMapInnner.get(txType))
    }
  }

  def setRates(bidRates: Map[Currency, ExchangeRate], askRates: Map[Currency, ExchangeRate]) {
    this.bidRates = bidRates
    this.askRates = askRates
  }

  def createInvoice(data: InvoiceData, merchantId: Long, merchantName: Option[String], paymentAddress: String,
    defaultBidRates: collection.Map[Currency, ExchangeRate], ts: Long = System.currentTimeMillis, pid: Option[String] = None): Option[(Option[Invoice], Option[(ErrorCode, String)])] = {
    calculateBtcPrice(data.price, data.currency, defaultBidRates) map {
      pr =>
        log.info(s"${data.currency} ${data.price} =  ${pr._1} btc, rate = ${pr._2}")
        pr match {
          case (btcAmount, marketPrice) if btcAmount > 0.0 && marketPrice > 0.0 =>
            val expirationTime = ts + TIME_TO_EXPIRATION_IN_MINUTE * 60 * 1000
            val invalidTime = expirationTime + TIME_TO_INVALID_IN_MINUTE * 60 * 1000
            (Some(Invoice(merchantId, merchantName, if (pid.isDefined) pid.get else getNextId, InvoiceStatus.New, data, pr._1, ts, expirationTime, invalidTime, ts,
              paymentAddress, rate = Some(pr._2))), None)
          case _ =>
            (None, Some((ErrorCode.CalculatePriceFail, "Can't calculate price")))
        }
    }
  }

  def addInvoice(invoice: Invoice) {
    invoices += invoice.id -> invoice
  }

  def removeInvoice(invoice: Invoice) {
    invoices -= invoice.id
  }

  def initState() {
    invoiceUpdates.clear()
    updateOriginals.clear()
    invoiceNotifications.clear()
  }

  def update(event: AnyRef, isDebug: Boolean = false, debugInvoices: List[Invoice] = List.empty) {
    val invoiceActions: List[(Option[Invoice], Option[InvoiceUpdate], Option[InvoiceNotification])] =
      isDebug match {
        case false => invoices.values.toList.map(i => fsm.action(event, i))
        case true if debugInvoices.nonEmpty => debugInvoices.map(i => fsm.action(event, i))
      }
    invoiceUpdates ++= invoiceActions.map(t => t._2).filter(_.isDefined)
    invoiceNotifications ++= invoiceActions.map(t => t._3).filter(_.isDefined)
    // store original invoices before update
    updateOriginals ++= Map(invoiceUpdates.filter(_.isDefined).map(i => invoices.get(i.get.id)).filter(_.isDefined).map(i => i.get.id -> i.get.copy()): _*)
    if (!isDebug)
      updateInvoices(invoiceActions.map(t => t._1))
  }

  def getUpdateRes(): (List[Option[InvoiceUpdate]], List[Option[InvoiceNotification]], Map[String, Invoice]) = {
    (invoiceUpdates.toList, invoiceNotifications.toList, updateOriginals)
  }

  def getInvoice(id: String): Option[Invoice] = invoices.get(id)

  def setTransferHandlers(transferHandlerObjectMap: Map[TransferType, CryptoCurrencyTransferBase]) {
    this.transferHandlerObjectMap = transferHandlerObjectMap
  }

  def getTransferId = lastTransferId + 1

  def setLastTransferId(id: Long) = { lastTransferId = id }

  def getLastTransferItemId = lastTransferItemId

  def getNewTransferItemId = {
    lastTransferItemId += 1
    lastTransferItemId
  }

  def getLastBlockHeight(currency: Currency): Long = lastBlockHeight.getOrElse(currency, 0L)

  def setLastBlockHeight(currency: Currency, height: Long) = { lastBlockHeight.put(currency, height) }

  def getCoinAmount(currency: Currency, refundAmount: Double, defaultAskRates: collection.Map[Currency, ExchangeRate]): (Boolean, Double) = {
    tryGetRates(currency, askRates, defaultAskRates) match {
      case Some(rates) if rates.piecewisePrice.nonEmpty =>
        rates.piecewisePrice.filter(kv => (kv._1 * kv._2) >= refundAmount) match {
          case validPieces if validPieces.nonEmpty =>
            (true, CurrencyConversion.roundExternal(refundAmount / validPieces.values.toList.sorted.head, Currency.Btc))
          case _ => (false, 0.0)
        }
      case _ => (false, 0.0)
    }
  }

  val testAskPrice1 = ExchangeRate(Map(1.0 -> 2000.0, 2.0 -> 3000.0, 3.0 -> 4000.0, 4.0 -> 5000.0))
  def getTestCoinAmount(currency: Currency, refundAmount: Double): (Boolean, Double) = {
    testAskPrice1.piecewisePrice.filter(kv => (kv._1 * kv._2) >= refundAmount) match {
      case validPieces if validPieces.nonEmpty => (true, CurrencyConversion.roundExternal(refundAmount / validPieces.values.toList.sorted.head, Currency.Btc))
      case _ => (false, 0.0)
    }
  }

  private def updateInvoices(newInvoices: List[Option[Invoice]]) {
    newInvoices foreach {
      case Some(ni) =>
        invoices -= ni.id
        if (ni.status != Complete && ni.status != Expired && ni.status != Invalid)
          invoices += ni.id -> ni
      case None =>
    }
  }

  val forTestPrice = Some(ExchangeRate(Map(5000.0 -> 3800.0, 10000.0 -> 3700.0, 50000.0 -> 3600.0, 100000.0 -> 3500.0)))

  private def calculateBtcPrice(price: Double, currency: Currency, defaultBidRates: collection.Map[Currency, ExchangeRate]): Option[(Double, Double)] = {
    val priceOption: Option[(Double, Double)] = currency match {
      case Currency.Btc => Some((price, 1.0))
      case Currency.Cny | Currency.Usd =>
        val ratesToUse = if (forTest) forTestPrice else tryGetRates(currency, bidRates, defaultBidRates)
        log.info(s">>>>>>>>>>>>>>>calculateBtcPrice $price $currency $ratesToUse")
        ratesToUse map { r =>
          r.piecewisePrice.filter(_._2 > LOWEST_REASONABLE_PRICE).keys.toList.sorted.find(price < _) match {
            case Some(piece) => (CurrencyConversion.roundExternal(price / r.piecewisePrice(piece), Currency.Btc), r.piecewisePrice(piece))
            case None => (-1.0, -1.0)
          }
        }
      case _ => Some((-1.0, -1.0))
    }

    priceOption.map { p =>
      log.info("calculated price: " + p)
      (BigDecimal(if (currency == Currency.Btc) p._1 else p._1 * (1 + priceFloatingPercentage)).setScale(4, BigDecimal.RoundingMode.CEILING).toDouble, p._2)
    }.filter(p => forTest || p._1 >= MIN_BTC_PRICE)
  }

  private def getNextId = {
    Base58Coder.encode(Math.abs(rand.nextLong)) + Base58Coder.encode(Math.abs(rand.nextLong))
  }

  private def tryGetRates(currency: Currency, quantRates: collection.Map[Currency, ExchangeRate], defaultRates: collection.Map[Currency, ExchangeRate]): Option[ExchangeRate] = {
    quantRates.get(currency) match {
      case Some(rates) if (rates.piecewisePrice.exists(_._2 > LOWEST_REASONABLE_PRICE)) =>
        Some(rates)
      case _ =>
        val default = defaultRates.get(currency)
        log.error(s"Try to use default Rates for $currency, $default")
        default
    }
  }
}
