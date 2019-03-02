/**
 * Copyright (C) 2014 Coinport Inc. <http://www.coinport.com>
 *
 */

package com.coinport.bitway.actors.notification

import com.coinport.bitway.data._

import akka.actor._
import akka.event.LoggingReceive

class EmailNotificationActor(invoiceUrlBase: String, handler: MandrillMailHandler) extends Actor with ActorLogging {

  def receive = LoggingReceive {
    case NotifyMerchants(ens) => ens.foreach { en => notify(en.invoice) }
    case NotifyPayments(ppns) => ppns.foreach(ppn => notifyPayment(ppn.payment))

    case e: Invoice => notify(e)
  }

  def notify(invoice: Invoice) = {
    invoice.data.notificationEmail foreach {
      email =>
        if (invoice.status == InvoiceStatus.Refunded || invoice.status == InvoiceStatus.RefundFailed) {
          handler.sendMail(email, "invoice_" + invoice.status.name.toLowerCase,
            Seq(
              "ID" -> invoice.data.orderId.getOrElse(invoice.id),
              "REFUND_AMOUNT" -> String.valueOf(invoice.refundAccAmount.getOrElse(0.0),
                "URL" -> (invoiceUrlBase + invoice.id))
            ))
          log.info(s"Sent email to ${email} for invoice ${invoice.id} with status ${invoice.status}")
        } else if (invoice.data.fullNotifications || invoice.status == InvoiceStatus.Confirmed) {
          handler.sendMail(email, "invoice_" + invoice.status.name.toLowerCase,
            Seq(
              "ID" -> invoice.data.orderId.getOrElse(invoice.id),
              "URL" -> (invoiceUrlBase + invoice.id)
            ))
          log.info(s"Sent email to ${email} for invoice ${invoice.id} with status ${invoice.status}")
        }
    }
  }

  def notifyPayment(payment: AccountTransfer) = {
    payment.paymentData.get.notificationEmail foreach {
      email =>
        if (payment.status == TransferStatus.Succeeded) {
          handler.sendMail(email, "payment_" + payment.status.name.toLowerCase(),
            Seq(
              "ID" -> String.valueOf(payment.id),
              "ADDRESS" -> payment.address.getOrElse(""),
              "AMOUNT" -> String.valueOf(payment.externalAmount.getOrElse(0.0)),
              "NOTE" -> payment.paymentData.get.note.getOrElse(""),
              "TXID" -> payment.txid.getOrElse("")
            ))
          log.info(s"Sent email to ${email} for payment ${payment.id} with status ${payment.status}")
        } else if (payment.status.name.toLowerCase().indexOf("fail") != -1) {
          handler.sendMail(email, "payment_" + "failed",
            Seq(
              "ID" -> String.valueOf(payment.id),
              "ADDRESS" -> payment.address.getOrElse(""),
              "AMOUNT" -> String.valueOf(payment.externalAmount.getOrElse(0.0)),
              "NOTE" -> payment.paymentData.get.note.getOrElse(""),
              "FAIL_REASON" -> payment.status.name
            ))
          log.info(s"Sent email to ${email} for payment ${payment.id} with status ${payment.status}")
        }
    }
  }
}
