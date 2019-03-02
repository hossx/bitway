package com.coinport.bitway.actors.notification

import com.mongodb.casbah.Imports._
import com.mongodb.casbah.MongoDB
import com.mongodb.casbah.{ MongoConnection, MongoURI }
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.coinport.bitway.data._

object NotificationMongoHandler {

  val config: Config = ConfigFactory.load
  val mongoUri = MongoURI(config.getString("akka.invoice.mongo-uri-for-readers"))
  val mongo = MongoConnection(mongoUri)
  val db = mongo(mongoUri.database.get)
  val coll = db("post_notify")

  ///////////////////////////////////////////////
  val ID = "id"
  val PRICE = "price"
  val CURRENCY = "currency"
  val BTCPRICE = "btcPrice"
  val INVOICETIME = "invoiceTime"
  val EXPIRATIONTIME = "expirationTime"
  val POSDATA = "posData"
  val CURRENTTIME = "currentTime"
  val URL = "url"
  val STATUS = "status"
  val TRIEDTIMES = "triedTimes"
  val RESTIME = "resTime"
  val RESINFO = "resInfo"
  val TRIEDRESTIME = "triedResTime"
  val TRIEDRESINFO = "triedResInfo"

  def addItem(item: InvoiceSummary, url: String, status: String, tried: Int, resTime: String, resInfo: String) = coll.insert(toBson(item, url, status, tried, resTime, resInfo))

  def updateItem(invoiceId: String, status: String, tried: Int, triedResTime: String, triedResInfo: String) =
    coll.update(
      MongoDBObject(ID -> invoiceId),
      $set(STATUS -> status, TRIEDTIMES -> tried, s"${TRIEDRESTIME}_${tried.toString}" -> triedResTime, s"${TRIEDRESINFO}_${tried.toString}" -> triedResInfo),
      true, false, com.mongodb.WriteConcern.ACKNOWLEDGED)

  def toBson(item: InvoiceSummary, url: String, status: String, tried: Int, resTime: String, resInfo: String) =
    MongoDBObject(
      ID -> item.id,
      PRICE -> item.price,
      CURRENCY -> item.currency.name,
      BTCPRICE -> item.btcPrice,
      INVOICETIME -> item.invoiceTime,
      EXPIRATIONTIME -> item.expirationTime,
      POSDATA -> item.posData,
      CURRENTTIME -> item.currentTime,
      URL -> url,
      STATUS -> status,
      TRIEDTIMES -> tried,
      RESTIME -> resTime,
      RESINFO -> resInfo)
}