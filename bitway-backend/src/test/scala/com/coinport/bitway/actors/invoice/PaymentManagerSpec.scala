/**
 * Copyright 2014 Coinport Inc. All Rights Reserved.
 * Author: c@coinport.com (Chao Ma)
 */

package com.coinport.bitway.actors.invoice

import org.specs2.mutable._

import com.coinport.bitway.data.Currency._
import com.coinport.bitway.data._
import com.coinport.bitway.actors.payment.PaymentManager

class PaymentManagerSpec extends Specification {
  import InvoiceStatus._

  private val defaultBidRates: collection.Map[Currency, ExchangeRate] = Map(Cny -> ExchangeRate(Map(5000.0 -> 1856.0, 10000.0 -> 1852.0, 50000.0 -> 1845.0, 100000.0 -> 1835.0)),
    Usd -> ExchangeRate(Map(500.0 -> 304.0, 1000.0 -> 303.0, 5000.0 -> 300.0, 10000.0 -> 295.0)))

  private def createTxEvent(txid: String, address: String, amount: Double, ts: Long, minerFee: Long = 0L,
    theoreticalMinerFee: Long = 0L) = BitcoinTxSeen(BitcoinTx(
    txid = Some(txid), outputs = Some(List(BitcoinTxPort(address, Some(amount)))),
    timestamp = ts,
    txType = Some(TransferType.Deposit),
    minerFee = Some(minerFee),
    theoreticalMinerFee = Some(theoreticalMinerFee)))

  private def createBlockEvent(txid: String, address: String, amount: Double, height: Long, ts: Long,
    reorgHeight: Option[Long]) = BitcoinBlockSeen(BitcoinBlock(height, List(BitcoinTx(
    txid = Some(txid), outputs = Some(List(BitcoinTxPort(address, Some(amount)))), timestamp = ts)),
    timestamp = ts), reorgHeight)

  private def updateManager(manager: PaymentManager, event: AnyRef): (List[Option[InvoiceUpdate]], List[Option[InvoiceNotification]]) = {
    manager.update(event)
    manager.getUpdateRes()
  }

  "InvoiceDataManagerSpec" should {
    "normal fsm transaction" in {
      val manager = new PaymentManager(true)
      val invoiceData = InvoiceData(400.0, Cny, notificationURL = Some("https://www.coinport.com"),
        transactionSpeed = 3, displayPrice = 1.0, displayCurrency = Btc)
      val invoice = manager.createInvoice(invoiceData, 100001L, None, "d123", defaultBidRates, 0, Some("1425")).get._1.get
      manager.addInvoice(invoice)
      val txEvent = createTxEvent("tx1", "d123", 1, invoice.invoiceTime + 1000 * 5, 7, 1)
      val (updates, notifications) = updateManager(manager, txEvent)
      updates.size mustEqual 1
      updates(0).get.copy(id = "") mustEqual InvoiceUpdate("", Paid, Some(1), None, Some("tx1"))
      notifications.size mustEqual 1

      notifications(0).get mustEqual InvoiceNotification("https://www.coinport.com", Invoice(100001, None, "1425", Paid,
        InvoiceData(400.0, Cny, None, Some("https://www.coinport.com"), 3, true, None, None, true, None, None, None,
          None, displayPrice = 1.0, displayCurrency = Btc), 0.1058, 0, 900000, 6300000, 5000, "d123", Some(1.0), None, Some("tx1"), None, Some(3800.0)))

      manager.getInvoice(invoice.id).get.copy(id = "", invoiceTime = 0, expirationTime = 0, invalidTime = 0,
        updateTime = 0) mustEqual Invoice(100001, None, "", Paid, InvoiceData(400.0, Cny, None,
          Some("https://www.coinport.com"), 3, true, None, None, true, None, None, None, None, displayPrice = 1.0, displayCurrency = Btc),
          0.1058, 0, 0, 0, 0, "d123", Some(1), None, Some("tx1"), None, Some(3800.0))
      manager.getInvoice(invoice.id).get.updateTime mustEqual txEvent.newTx.timestamp

      val blockEvent = createBlockEvent("tx1", "d123", 1, 1, 6000, None)
      updateManager(manager, blockEvent) mustEqual (List(Some(InvoiceUpdate("1425", Paid, None, Some(1)))), List())
      updateManager(manager, blockEvent) mustEqual (List(), List())
      updateManager(manager, blockEvent) mustEqual (List(), List())

      updateManager(manager, createBlockEvent("tx1", "d123", 1, 2, 6003, None)) mustEqual (List(), List())

      updateManager(manager, createBlockEvent("tx2", "d456", 1, 3, 7003, None)) mustEqual ((
        List(Some(InvoiceUpdate("1425", Confirmed, None, Some(1)))),
        List(Some(InvoiceNotification("https://www.coinport.com",
          Invoice(100001, None, "1425", Confirmed,
            InvoiceData(400.0, Cny, None, Some("https://www.coinport.com"), 3, true, None, None, true,
              None, None, None, None, displayPrice = 1.0, displayCurrency = Btc), 0.1053, 0, 900000, 6300000, 7003, "d123", Some(1.0), Some(1),
            Some("tx1"), None, Some(3800.0))
        )))))

      manager.getInvoice(invoice.id).get mustEqual Invoice(100001, None, "1425", Confirmed, InvoiceData(
        400.0, Cny, None, Some("https://www.coinport.com"), 3, true, None, None, true, None, None, None, None, displayPrice = 1.0, displayCurrency = Btc),
        0.1053, 0, 900000, 6300000, 7003, "d123", Some(1), Some(1), Some("tx1"), None, Some(3800.0))
      updateManager(manager, createBlockEvent("tx1", "d123", 1, 4, 8003, None)) mustEqual (List(), List())

      updateManager(manager, createBlockEvent("tx1", "d123", 1, 6, 9003, None)) mustEqual (
        (List(Some(InvoiceUpdate("1425", Complete, None, None))),
          List(Some(InvoiceNotification("https://www.coinport.com",
            Invoice(100001, None, "1425", Complete,
              InvoiceData(400.0, Cny, None, Some("https://www.coinport.com"), 3, true, None, None, true, None, None,
                None, None, displayPrice = 1.0, displayCurrency = Btc), 0.1053, 0, 900000, 6300000, 9003, "d123", Some(1.0), Some(1),
              Some("tx1"), None, Some(3800.0)))))))

      updateManager(manager, createBlockEvent("tx1", "d123", 1, 7, 10003, None)) mustEqual (List(), List())

      manager.getInvoice(invoice.id) mustEqual None
    }

    "stay in new state since not enough fee" in {
      val manager = new PaymentManager(true)
      val invoiceData = InvoiceData(400.0, Cny, notificationURL = Some("https://www.coinport.com"),
        transactionSpeed = 3, displayPrice = 1.0, displayCurrency = Btc)
      val invoice = manager.createInvoice(invoiceData, 100001L, None, "d123", defaultBidRates, 0, Some("1425")).get._1.get
      manager.addInvoice(invoice)
      val txEvent = createTxEvent("tx1", "d123", 1, invoice.invoiceTime + 1000 * 5, 1, 10)
      val (updates, notifications) = updateManager(manager, txEvent)
      updates.size mustEqual 0
      notifications.size mustEqual 0

      manager.getInvoice(invoice.id).get.copy(id = "", invoiceTime = 0, expirationTime = 0, invalidTime = 0,
        updateTime = 0) mustEqual Invoice(100001, None, "", New, InvoiceData(400.0, Cny, None,
          Some("https://www.coinport.com"), 3, true, None, None, true, None, None, None, None, displayPrice = 1.0, displayCurrency = Btc),
          0.1058, 0, 0, 0, 0, "d123", None, None, None, None, Some(3800.0))
    }

    "go to invalid state" in {
      val manager = new PaymentManager(true)
      val invoiceData = InvoiceData(3800.0, Cny, notificationURL = Some("https://www.coinport.com"),
        transactionSpeed = 3, displayPrice = 1.0, displayCurrency = Btc)
      val invoice = manager.createInvoice(invoiceData, 100001L, None, "d123", defaultBidRates, 0, Some("1425")).get._1.get
      manager.addInvoice(invoice)
      val txEvent = createTxEvent("tx1", "d123", 1, invoice.invoiceTime + 1000 * 5)
      val (updates, notifications) = updateManager(manager, txEvent)
      updates.size mustEqual 1
      updates(0).get.copy(id = "") mustEqual InvoiceUpdate("", Paid, Some(1), None, Some("tx1"))
      notifications.size mustEqual 1
      notifications(0).get mustEqual InvoiceNotification("https://www.coinport.com", Invoice(
        100001, None, "1425", Paid, InvoiceData(3800.0, Cny, None, Some("https://www.coinport.com"), 3, true, None, None,
          true, None, None, None, None, displayPrice = 1.0, displayCurrency = Btc), 1.0, 0, 900000, 6300000, 5000, "d123", Some(1.0), None, Some("tx1"),
        None, Some(3800.0)))

      manager.getInvoice(invoice.id).get.copy(id = "", invoiceTime = 0, expirationTime = 0, invalidTime = 0,
        updateTime = 0) mustEqual Invoice(100001, None, "", Paid, InvoiceData(3800.0, Cny, None,
          Some("https://www.coinport.com"), 3, true, None, None, true, None, None, None, None, displayPrice = 1.0, displayCurrency = Btc),
          1, 0, 0, 0, 0, "d123", Some(1), None, Some("tx1"), None, Some(3800.0))
      manager.getInvoice(invoice.id).get.updateTime mustEqual txEvent.newTx.timestamp

      val blockEvent = createBlockEvent("tx1", "d123", 1, 1, 6000, None)
      updateManager(manager, blockEvent) mustEqual (List(Some(InvoiceUpdate("1425", Paid, None, Some(1)))), List())
      updateManager(manager, blockEvent) mustEqual (List(), List())
      updateManager(manager, blockEvent) mustEqual (List(), List())

      updateManager(manager, createBlockEvent("tx1", "d123", 1, 2, 6003, None)) mustEqual (List(), List())
      updateManager(manager, createBlockEvent("tx2", "d456", 1, 2, 105 * 60 * 1000 + 1, None)) mustEqual ((
        List(Some(InvoiceUpdate("1425", Invalid, None, Some(1)))),
        List(Some(InvoiceNotification("https://www.coinport.com",
          Invoice(100001, None, "1425", Invalid, InvoiceData(3800.0, Cny, None, Some("https://www.coinport.com"), 3,
            true, None, None, true, None, None, None, None, displayPrice = 1.0, displayCurrency = Btc), 1.0, 0, 900000, 6300000, 6300001, "d123",
            Some(1.0), Some(1), Some("tx1"), None, Some(3800.0)))))))

      manager.getInvoice(invoice.id) mustEqual None
    }

    "go to expired state" in {
      val manager = new PaymentManager(true)
      val invoiceData = InvoiceData(3800.0, Cny, notificationURL = Some("https://www.coinport.com"),
        transactionSpeed = 3, displayPrice = 1.0, displayCurrency = Btc)
      val invoice = manager.createInvoice(invoiceData, 100001L, None, "d123", defaultBidRates, 0, Some("1425")).get._1.get
      manager.addInvoice(invoice)
      updateManager(manager, createBlockEvent("tx2", "d456", 1, 2, 15 * 60 * 1000 + 1, None)) mustEqual ((
        List(Some(InvoiceUpdate("1425", Expired, None, None))),
        List(Some(InvoiceNotification("https://www.coinport.com",
          Invoice(100001, None, "1425", Expired,
            InvoiceData(3800.0, Cny, None, Some("https://www.coinport.com"), 3, true, None, None, true,
              None, None, None, None, displayPrice = 1.0, displayCurrency = Btc), 1, 0, 900000, 6300000, 900001, "d123", None, None, None, None, Some(3800.0))
        )))))
      manager.getInvoice(invoice.id) mustEqual None
    }
  }
}
