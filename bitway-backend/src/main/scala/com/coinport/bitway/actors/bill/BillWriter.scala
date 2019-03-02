package com.coinport.bitway.actors.bill

import akka.actor.ActorLogging
import akka.event.LoggingReceive
import akka.persistence.Persistent

import com.coinport.bitway.api.model.CurrencyWrapper
import com.coinport.bitway.common.{ Manager, ExtendedView }
import com.coinport.bitway.data._
import com.mongodb.casbah.Imports._

class BillWriter(db: MongoDB) extends ExtendedView with BillHandler with ActorLogging {
  override val processorId = "account"
  override val viewId = "bill_writer"

  val manager = new BillWriterManager()

  val coll = db("bills")

  def receive = LoggingReceive {
    case Persistent(InvoiceComplete(invoices), _) =>
      handleCompleteInvoice(invoices)

    case Persistent(AdminConfirmTransferSuccess(t: AccountTransfer), _) =>
      handleWithdrawal(t)

    case Persistent(CryptoTransferResult(multiTransfers, invoiceComplete), _) =>
      multiTransfers.values.foreach {
        tfs =>
          tfs.transfers.filter(t => (t.`type` == TransferType.Settle || t.`type` == TransferType.Refund || t.`type` == TransferType.Payment) && t.status == TransferStatus.Succeeded) foreach {
            handleWithdrawal(_)
          }
      }
      invoiceComplete.foreach(
        ic =>
          handleCompleteInvoice(ic.completes)
      )
  }

  private def handleWithdrawal(t: AccountTransfer) {
    val (cry, amt, wType, note) = t.`type` match {
      case TransferType.Settle =>
        (t.currency, t.amount, BillType.Settle, None)
      case TransferType.Refund =>
        (t.refundCurrency.get, t.refundInternalAmount.get, BillType.Refund, None)
      case TransferType.Payment =>
        (t.paymentData.get.currency.get, t.paymentData.get.amount.get, BillType.Payment, t.paymentData.get.note)
      case _ => (Currency.Btc, 0L, BillType.Invoice, None)
    }
    if (amt <= 0) return
    t.fee match {
      case Some(f) if (f.amount > 0) =>
        addItem(BillItem(manager.getNextId, t.merchantId, new CurrencyWrapper(amt - f.amount).externalValue(cry), cry, wType, t.updated.getOrElse(t.created.get), t.invoiceId, address = t.address, note = note))
        addItem(BillItem(manager.getNextId, t.merchantId, new CurrencyWrapper(f.amount).externalValue(cry), cry, BillType.Fee, t.updated.getOrElse(t.created.get), t.invoiceId, address = t.address, note = note))
      case _ =>
        addItem(BillItem(manager.getNextId, t.merchantId, new CurrencyWrapper(amt).externalValue(cry), cry, wType, t.updated.getOrElse(t.created.get), t.invoiceId, address = t.address, note = note))
    }
  }

  private def handleCompleteInvoice(completes: Seq[Invoice]) {
    completes.filter(_.status == InvoiceStatus.Confirmed) foreach {
      i =>
        if (i.data.fee > 0.0) {
          addItem(BillItem(manager.getNextId, i.merchantId, i.data.price, i.data.currency, BillType.Invoice, i.updateTime, Some(i.id), invoice = Some(i)))
          addItem(BillItem(manager.getNextId, i.merchantId, i.data.fee, i.data.currency, BillType.Fee, i.updateTime, Some(i.id)))
        } else {
          addItem(BillItem(manager.getNextId, i.merchantId, i.data.price, i.data.currency, BillType.Invoice, i.updateTime, Some(i.id), invoice = Some(i)))
        }
    }
  }
}

class BillWriterManager extends Manager[TBillWriterState] {
  private var billId: Long = 1E12.toLong
  def getSnapshot = TBillWriterState(
    getFiltersSnapshot,
    billId
  )

  def loadSnapshot(s: TBillWriterState) {
    loadFiltersSnapshot(s.filters)
    billId = s.billId
  }

  def getNextId(): Long = {
    billId += 1
    billId
  }
}