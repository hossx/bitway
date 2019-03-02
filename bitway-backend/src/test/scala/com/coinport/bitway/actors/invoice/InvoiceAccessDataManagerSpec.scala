package com.coinport.bitway.actors.invoice

import com.coinport.bitway.common.EmbeddedMongoForTestWithBF
import com.coinport.bitway.data._

class InvoiceAccessDataManagerSpec extends EmbeddedMongoForTestWithBF with InvoiceAccessHandler {

  val coll = database("InvoiceAccessDataManagerSpec")
  "InvoiceDataStateSpec" should {

    "can insert and query invoiceAccess " in {
      coll.drop()
      coll.size should be(0)
      val invoiceSeq = (0 to 3).map(i =>
        Invoice(
          merchantId = i,
          id = i.toString,
          btcPrice = i.toDouble,
          data = InvoiceData(price = i.toDouble, currency = Currency.Btc, displayPrice = i.toDouble, displayCurrency = Currency.Btc),
          status = InvoiceStatus.get(i).get,
          invoiceTime = i.toLong,
          updateTime = i.toLong,
          expirationTime = i.toLong,
          paymentAddress = ""))
      invoiceSeq.foreach(i => addItem(i))

      coll.size should be(4)

      val query = QueryInvoice(merchantId = Some(2), skip = 0, limit = 10)

      countItems(query) should be(1)
      getItems(query).map(_.id) should equal(Seq("2"))
    }

    "can update data" in {
      coll.drop()
      coll.size should be(0)

      val invoice = Invoice(
        id = 1.toString,
        btcPrice = 1.toDouble,
        merchantId = 1,
        data = InvoiceData(price = 1.toDouble, currency = Currency.Btc, displayCurrency = Currency.Btc, displayPrice = 1.toDouble),
        status = InvoiceStatus.Expired,
        invoiceTime = 1.toLong,
        updateTime = 1.toLong,
        expirationTime = 1.toLong,
        btcPaid = Some(1),
        paymentAddress = "")

      addItem(invoice)
      var rv = getItemById(1.toString)(0)

      rv.id should be("1")
      rv.status should be(InvoiceStatus.Expired)
      rv.btcPaid should be(Some(1))
      rv.lastPaymentBlockHeight should be(None)

      val invoiceUpdate = InvoiceUpdate(id = 1.toString, status = InvoiceStatus.Complete, btcPaid = None, lastPaymentBlockHeight = Some(100))
      updateItem(invoiceUpdate)
      rv = getItemById(1.toString)(0)

      rv.status should be(InvoiceStatus.Complete)
      rv.btcPaid should be(Some(1))
      rv.lastPaymentBlockHeight should be(Some(100))
    }
  }
}
