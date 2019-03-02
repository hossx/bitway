package com.coinport.bitway.actors.invoice

import akka.actor._
import akka.event.{ LoggingAdapter, LoggingReceive }
import akka.persistence._

import com.coinport.bitway.common._
import com.coinport.bitway.data._
import com.mongodb.casbah.MongoDB
import com.coinport.bitway.LocalRouters

/**
 * This actor should be a persistent actor.
 *
 * Responsible for:
 * - read from HBase events and update MongoDB
 * - answers all queries
 * TODO(c): 此处使用channel有个问题：channel发出的消息可能会发多次，而且无法保证顺序。需要考虑fix。
 *          不过因为对于支付来说，有顺序要求的消息间隔时间都较长；再加上此处所有写数据库的操作都是幂等的。所以可以降低fix这个问题的优先级。
 */

class InvoiceAccessActor(routers: LocalRouters, db: MongoDB) extends ExtendedActor with InvoiceAccessHandler with ActorLogging {

  val coll = db("invoices")
  val orderColl = db("orders")
  implicit val loggerInner: LoggingAdapter = log

  def receive = LoggingReceive {
    // ============ The following events are from frontend:
    case e: QueryInvoiceById =>
      sender ! QueryInvoiceResult(getItemById(e.id).map(updateInvoice), 1)

    case e: QueryInvoiceByIds =>
      val items = getItemByIds(e.ids)
      sender ! QueryInvoiceResult(items.map(updateInvoice), items.size)

    case e: QueryInvoice =>
      sender ! QueryInvoiceResult(getItems(e).map(updateInvoice), countItems(e).toInt)

    // Deprecated: sum all invoice's price will not get merchant's balance
    case e: QueryMerchantBalance =>
      val balanceMap = e.currencyList.map(c => c -> getBalance(e.merchantId, c)).toMap.filter(_._2 > 0.0)
      sender ! QueryMerchantBalanceResult(merchantId = e.merchantId, balanceMap = balanceMap)

    case p @ ConfirmablePersistent(m: InvoiceCreated, _, _) =>
      log.info("=========: invoiceAccessActor: " + m)
      p.confirm()
      addItem(m.invoice)
      sender ! InvoiceCreated(m.invoice.copy(currentTime = Some(System.currentTimeMillis)))

    case p @ ConfirmablePersistent(m: InvoiceUpdated, _, _) =>
      log.info("=========: invoiceAccessActor: " + m)
      p.confirm()
      m.invoiceUpdates.foreach(queryUpdateItem)

    case m: PaymentReceived =>
      log.info("=========: invoiceAccessActor: " + m)
      addPayment(m.id, m.payment)

    case m: InvoicePartialUpdate =>
      log.info("=========: invoiceAccessActor: " + m)
      val resInvoices = (m.invoiceUpdates map queryUpdateItem).filter(_.isDefined).map(_.get)
      sender ! QueryInvoiceResult(resInvoices, resInvoices.size)

    case m: OrderLogisticsUpdated =>
      log.info("=========: invoiceAccessActor: " + m)
      sender ! OrderLogisticsUpdatedResult(updateLogistics(m.orderUpdates))
  }

  private def updateInvoice(invoice: Invoice) = {
    var i = invoice

    if (invoice.status == InvoiceStatus.New && System.currentTimeMillis >= invoice.expirationTime)
      i = i.copy(status = InvoiceStatus.Expired, updateTime = System.currentTimeMillis)

    i.copy(currentTime = Some(System.currentTimeMillis))
  }
}
