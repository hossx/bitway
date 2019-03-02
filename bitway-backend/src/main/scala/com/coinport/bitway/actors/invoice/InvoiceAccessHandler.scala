package com.coinport.bitway.actors.invoice

import com.coinport.bitway.data._
import com.coinport.bitway.serializers.ThriftBinarySerializer
import com.mongodb.casbah.Imports._
import com.mongodb.WriteConcern._

import java.nio.ByteBuffer

import scalikejdbc._
import scalikejdbc.config.DBs
import akka.event.LoggingAdapter

trait InvoiceAccessHandler {
  val INVOICE_ID = "_id" //invoice access id
  val MERCHANT_ID = "mid" //merchant id
  val CREATED_TIME = "c@"
  val UPDATED_TIME = "u@"
  val NOTIFICATION_STATE = "nst"
  val NOTIFICATION_UPDATED = "nst@"
  val INVOICE_BINARY = "b"
  val INVOICE_UPDATE_BINARY = "ub"
  val INVOICE_STATUS = "is"
  val CURRENCY = "c"
  val PRICE = "p"
  val PAYMENT = "pm"
  val BTC_PRICE = "bp"
  val ORDER_ID = "oi"
  val ORDER_INVOICE_ID = "iid"
  val ORDER_ITEM_CODE = "oic"
  val ORDER_ITEM_DESC = "oid"
  val ORDER_PHYSICAL = "op"
  val ORDER_LOGISTICS_CODE = "olc"
  val ORDER_LOGISTICS_ORG_CODE = "oloc"
  val ORDER_LOGISTICS_ORG_NAME = "olon"

  val converter = new ThriftBinarySerializer

  def coll: MongoCollection
  def orderColl: MongoCollection
  implicit val loggerInner: LoggingAdapter

  DBs.setupAll()

  def addItem(item: Invoice) = {
    coll.insert(toBson(item))

    val i = item
    val invoiceSql = sql"""
        insert into invoice
        (id, merchantId, merchantName, status, price, currency, currencyCode, posData, notificationURL,
        transactionSpeed, fullNotifications, notificationEmail, redirectURL, physical, orderId,
        itemDesc, itemCode, buyer, merchant, displayPrice, displayCurrency, fee, tsSpeed, customTitle,
        customLogoUrl, theme, redirectMethod, created, btcPrice, invoiceTime, expirationTime, invalidTime,
        updateTime, paymentAddress, btcPaid, lastPaymentBlockHeight, txid, currentTime, rate, payments, refundAccAmount)
        values
        (${i.id}, ${i.merchantId}, ${optionalString(i.merchantName)}, ${i.status.getValue()}, ${i.data.price}, ${i.data.currency.getValue()}, ${i.data.currency.name.toUpperCase()}, ${optionalString(i.data.posData)}, ${optionalString(i.data.notificationURL)},
        ${i.data.transactionSpeed}, ${boolToInt(i.data.fullNotifications)}, ${optionalString(i.data.notificationEmail)}, ${optionalString(i.data.redirectURL)}, ${boolToInt(i.data.physical)}, ${optionalString(i.data.orderId)},
        ${optionalString(i.data.itemDesc)}, ${optionalString(i.data.itemCode)}, ${handleContact(i.id + "_b", i.data.buyer)}, ${handleContact(i.id + "_m", i.data.merchant)}, ${i.data.displayPrice}, ${i.data.displayCurrency.getValue()}, ${i.data.fee}, ${i.data.tsSpeed.getValue()}, ${optionalString(i.data.customTitle)},
        ${optionalString(i.data.customLogoUrl)}, ${optionalString(i.data.theme)}, ${optionalString(i.data.redirectMethod)}, ${optionalToTimeDate(i.data.created)}, ${i.btcPrice}, ${optionalToTimeDate(Some(i.invoiceTime))}, ${optionalToTimeDate(Some(i.expirationTime))}, ${optionalToTimeDate(Some(i.invalidTime))},
        ${optionalToTimeDate(Some(i.updateTime))}, ${i.paymentAddress}, ${i.btcPaid.getOrElse(0.0)}, ${i.lastPaymentBlockHeight.getOrElse(0L)}, ${optionalString(i.txid)}, ${optionalToTimeDate(i.currentTime)}, ${i.rate.getOrElse(0.0)}, ${handlePayments(i.payments)}, ${i.refundAccAmount.getOrElse(0.0)})
        """
    executeSql(invoiceSql)

    if (item.data.orderId.isDefined) {
      orderColl.insert(toOrderBson(item))
      val orderSql = sql"""
        insert into orders
        (id, invoiceId, merchantId, itemDesc, itemCode, physical, createTime, updateTime) values
        (${item.data.orderId.get}, ${item.id}, ${item.merchantId}, ${optionalString(item.data.itemDesc)}, ${optionalString(item.data.itemCode)},
        ${boolToInt(item.data.physical)}, ${optionalToTimeDate(Some(item.invoiceTime))}, ${optionalToTimeDate(Some(item.updateTime))})
        """
      executeSql(orderSql)
    }

  }

  // Deprecated (use queryUpdateItem instead)
  // TODO(chenxi): also change UPDATED_TIME
  //  def updateItem(item: InvoiceUpdate) =
  //    coll.update(MongoDBObject(INVOICE_ID -> item.id), $set(INVOICE_UPDATE_BINARY -> converter.toBinary(item), INVOICE_STATUS -> item.status.getValue()), false, false, ACKNOWLEDGED)

  def queryUpdateItem(item: InvoiceUpdate): Option[Invoice] = {
    coll.find(MongoDBObject(INVOICE_ID -> item.id)).map(toClass(_)).toSeq.headOption match {
      case Some(invoice) =>
        val timestamp = System.currentTimeMillis()
        val paidTime = if (item.status == InvoiceStatus.Confirmed) Some(timestamp) else invoice.paidTime
        val newInvoice = invoice.copy(
          status = item.status,
          btcPaid = if (item.btcPaid.isDefined) item.btcPaid else invoice.btcPaid,
          lastPaymentBlockHeight = if (item.lastPaymentBlockHeight.isDefined) item.lastPaymentBlockHeight else invoice.lastPaymentBlockHeight,
          txid = if (item.txid.isDefined) item.txid else invoice.txid,
          refundAccAmount = if (item.refundAccAmount.isDefined) Some(invoice.refundAccAmount.getOrElse(0.0) + item.refundAccAmount.get) else invoice.refundAccAmount,
          updateTime = timestamp,
          paidTime = paidTime)
        coll.update(MongoDBObject(INVOICE_ID -> item.id),
          $set(INVOICE_BINARY -> converter.toBinary(newInvoice), INVOICE_UPDATE_BINARY -> converter.toBinary(item), INVOICE_STATUS -> item.status.getValue(), UPDATED_TIME -> Some(timestamp)),
          false, false, ACKNOWLEDGED)
        val updateInvoiceSql = sql"""
          update invoice set
          status = ${newInvoice.status.getValue()},
          btcPaid = ${newInvoice.btcPaid.getOrElse(0.0)},
          lastPaymentBlockHeight = ${newInvoice.lastPaymentBlockHeight.getOrElse(0)},
          txid = ${newInvoice.txid.getOrElse("")},
          refundAccAmount = ${newInvoice.refundAccAmount.getOrElse(0.0)},
          updateTime = ${optionalToTimeDate(Some(timestamp))},
          paidTime = ${optionalToTimeDate(paidTime)}
          where id = ${invoice.id}
        """
        executeSql(updateInvoiceSql)
        Some(newInvoice)
      case _ =>
        None
    }
  }

  def addPayment(invoiceId: String, payment: ByteBuffer) = {
    val invoice = getItemById(invoiceId)
    if (invoice.length > 0) {
      val payments = invoice(0).payments.getOrElse(Seq.empty[ByteBuffer])
      val updatedBuffers = payments :+ payment
      val updated = updatedBuffers map { case m => { val bytes = new Array[Byte](m.remaining); m.get(bytes); bytes } }
      coll.update(MongoDBObject(INVOICE_ID -> invoiceId), $set(PAYMENT -> updated.toString), false, false)
      val paymentSql =
        sql"""
          update invoice set
          payments = ${handlePayments(Some(updatedBuffers))},
          updateTime = ${optionalToTimeDate(Some(System.currentTimeMillis))}
          where id = ${invoiceId}
        """
      executeSql(paymentSql)
    }
  }

  def updateLogistics(updates: Seq[OrderUpdate]): ErrorCode = {
    val timestamp = System.currentTimeMillis()
    val validUps = updates.filter(i => i.orderId.nonEmpty && i.merchantId > 0L && i.logisticsCode.isDefined && i.logisticsCode.get.nonEmpty)
    validUps.foreach {
      up =>
        orderColl.update(MongoDBObject(ORDER_ID -> up.orderId, MERCHANT_ID -> up.merchantId),
          $set(ORDER_LOGISTICS_CODE -> optionalString(up.logisticsCode), ORDER_LOGISTICS_ORG_CODE -> optionalString(up.logisticsOrgCode), ORDER_LOGISTICS_ORG_NAME -> optionalString(up.logisticsOrgName),
            UPDATED_TIME -> Some(timestamp)), false, false, ACKNOWLEDGED)
        val logisticsSql =
          sql"""
          update orders set
          logisticsCode = ${optionalString(up.logisticsCode)},
          logisticsOrgCode = ${optionalString(up.logisticsOrgCode)},
          logisticsOrgName = ${optionalString(up.logisticsOrgName)},
          updateTime = ${optionalToTimeDate(Some(timestamp))}
          where id = ${up.orderId} and merchantId = ${up.merchantId}
        """
        executeSql(logisticsSql)
    }
    if (validUps.size == updates.size) {
      ErrorCode.Ok
    } else if (validUps.size == 0) {
      ErrorCode.AllFailed
    } else {
      ErrorCode.PartiallyFailed
    }
  }

  def countItems(q: QueryInvoice): Long = coll.count(mkQuery(q))

  def getItems(q: QueryInvoice): Seq[Invoice] =
    coll.find(mkQuery(q)).sort(DBObject(UPDATED_TIME -> -1)).skip(q.skip).limit(q.limit).map(toClass(_)).toSeq

  def getItemById(id: String): Seq[Invoice] =
    coll.find(MongoDBObject(INVOICE_ID -> id)).map(toClass(_)).toSeq

  def getItemByIds(ids: Seq[String]): Seq[Invoice] =
    coll.find(MongoDBObject() ++= $or(ids.map(i => INVOICE_ID -> i): _*)).map(toClass(_)).toSeq

  def getBalance(merchantId: Long, currency: Currency): Double = {
    coll.find(MongoDBObject(MERCHANT_ID -> merchantId, CURRENCY -> currency.name, INVOICE_STATUS -> InvoiceStatus.Complete.getValue()), MongoDBObject(PRICE -> 1))
      .map(obj => obj.getAs[Double](PRICE).getOrElse(0.0)).sum
  }

  private def toBson(item: Invoice) = {
    MongoDBObject(
      INVOICE_ID -> item.id, MERCHANT_ID -> item.merchantId, CREATED_TIME -> item.invoiceTime, PRICE -> item.data.price, CURRENCY -> item.data.currency.name,
      UPDATED_TIME -> item.updateTime, INVOICE_BINARY -> converter.toBinary(item), INVOICE_STATUS -> item.status.getValue(), BTC_PRICE -> item.btcPrice,
      ORDER_ID -> item.data.orderId.getOrElse(""))
  }

  private def toOrderBson(item: Invoice) = {
    MongoDBObject(
      ORDER_ID -> item.data.orderId.get, ORDER_INVOICE_ID -> item.id, MERCHANT_ID -> item.merchantId, ORDER_ITEM_CODE -> item.data.itemCode.getOrElse(""),
      ORDER_ITEM_DESC -> item.data.itemDesc.getOrElse(""), ORDER_PHYSICAL -> item.data.physical, CREATED_TIME -> item.invoiceTime, UPDATED_TIME -> item.updateTime
    )
  }

  private def toClass(obj: MongoDBObject): Invoice = {
    var invoice = converter.fromBinary(obj.getAsOrElse(INVOICE_BINARY, null), Some(classOf[Invoice.Immutable])).asInstanceOf[Invoice]
    val status = obj.getAs[Int](INVOICE_STATUS).getOrElse(0)
    invoice = invoice.copy(status = InvoiceStatus.get(status).get, updateTime = obj.getAs[Long](UPDATED_TIME).getOrElse(0))

    if (obj.keySet.contains(INVOICE_UPDATE_BINARY)) {
      val iu = converter.fromBinary(obj.getAsOrElse(INVOICE_UPDATE_BINARY, null), Some(classOf[InvoiceUpdate.Immutable])).asInstanceOf[InvoiceUpdate]
      invoice = invoice.copy(
        btcPaid = if (iu.btcPaid.isDefined) iu.btcPaid else invoice.btcPaid,
        lastPaymentBlockHeight = if (iu.lastPaymentBlockHeight.isDefined) iu.lastPaymentBlockHeight else invoice.lastPaymentBlockHeight,
        txid = if (iu.txid.isDefined) iu.txid else invoice.txid)
      invoice
    } else invoice
  }

  private def mkQuery(q: QueryInvoice) = {
    var query = MongoDBObject()

    if (q.merchantId.isDefined) query = query ++ (MERCHANT_ID -> q.merchantId.get)
    if (q.currency.isDefined) query = query ++ (CURRENCY -> q.currency.get.name)
    if (q.statusList.isDefined) query = query ++ (INVOICE_STATUS $in q.statusList.get.map(_.getValue()))
    if (q.orderId.isDefined && q.orderId.get.nonEmpty) query = query ++ (ORDER_ID -> q.orderId.get)

    if (q.from.isDefined && q.to.isDefined) query = query ++ (CREATED_TIME $gte q.from.get $lt q.to.get)
    else if (q.from.isDefined) query = query ++ (CREATED_TIME $gte q.from.get)
    else if (q.to.isDefined) query = query ++ (CREATED_TIME $lt q.to.get)

    query
  }

  private def wpString(data: String) = data.replace("'", "\\'")

  private def optionalString(data: Option[String]) = wpString(data.getOrElse(""))

  private def boolToInt(data: Boolean) = if (data) 1 else 0

  private def handleContact(contactId: String, data: Option[Contact]): String = {
    if (data.isDefined) {
      val c = data.get
      val contactSql =
        sql"""insert into contact
         (id, name, address, address2, city, state,
          zip, country, phone, email, defaultRedirectURL)
          values
         ($contactId, ${c.name}, ${optionalString(c.address)}, ${optionalString(c.address2)}, ${optionalString(c.city)}, ${optionalString(c.state)},
          ${optionalString(c.zip)}, ${optionalString(c.country)}, ${optionalString(c.phone)}, ${optionalString(c.email)}, ${optionalString(c.defaultRedirectURL)})
        """
      executeSql(contactSql)
      contactId
    } else {
      ""
    }
  }

  private def optionalToTimeDate(data: Option[Long]): String = {
    if (data.isDefined && data.get != 0L) {
      val timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
      val format = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
      format.setTimeZone(timeZone)
      format.format(new java.util.Date(data.get))
    } else {
      "0000-00-00 00:00:00"
    }
  }

  private def handlePayments(data: Option[Seq[ByteBuffer]]) = {
    val bytes = if (data.isDefined && data.get.nonEmpty) {
      data.get.foldLeft(new collection.mutable.ArrayBuffer[Byte](0))((res: collection.mutable.ArrayBuffer[Byte], i: ByteBuffer) => res ++= i.array()).toArray
    } else {
      Array[Byte]()
    }
    val in = new java.io.ByteArrayInputStream(bytes)
    ParameterBinder[java.io.InputStream](
      value = in,
      binder = (stmt: java.sql.PreparedStatement, idx: Int) => stmt.setBinaryStream(idx, in, bytes.length)
    )
  }

  private def executeSql(sqlObj: SQL[Nothing, scalikejdbc.NoExtractor], retry: Boolean = false) {
    try {
      DB localTx {
        implicit session =>
          sqlObj.update.apply()
      }
    } catch {
      case e: com.mysql.jdbc.exceptions.jdbc4.CommunicationsException if !retry =>
        Thread.sleep(100)
        logError(e, sqlObj.statement)
        executeSql(sqlObj, true)
      case e: java.io.EOFException if !retry =>
        Thread.sleep(100)
        logError(e, sqlObj.statement)
        executeSql(sqlObj, true)
      case e: Throwable =>
        logError(e, sqlObj.statement, retry)
    }
  }

  private def logError(e: Throwable, statement: String, retry: Boolean = false) {
    if (loggerInner != null) {
      loggerInner.error(s"executeSql failed:retry: $retry\n $statement\n${e.getStackTraceString}")
    } else {
      println(s"executeSql failed:retry: $retry\n $statement\n${e.getStackTraceString}")
    }
  }

}
