/**
 * Copyright 2014 Coinport Inc. All Rights Reserved.
 * Author: c@coinport.com (Chao Ma)
 */

package com.coinport.bitway.client

import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor._
import akka.cluster.Cluster
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import com.google.common.util.concurrent.ListenableFuture
import com.google.bitcoin.protocols.payments._
import com.google.bitcoin.core.Wallet
import com.coinport.bitway.LocalRouters
import com.coinport.bitway.data._
import com.coinport.bitway.data.Currency._
import com.coinport.bitway.data.Exchange._
import com.coinport.bitway.Bitway
import com.coinport.bitway.actors.invoice.InvoiceAccessHandler
import com.mongodb.casbah.MongoConnection
import com.mongodb.casbah.MongoURI

class InvoiceColl extends InvoiceAccessHandler {
  val mongoUri = MongoURI("")
  val mongo = MongoConnection(mongoUri)
  val db = mongo(mongoUri.database.get)
  val coll = db("invoices")
  val orderColl = db("order")
  val loggerInner = null
}

object Client {
  implicit val timeout = Timeout(10 seconds)

  val configPath = System.getProperty("client.config") match {
    case null => "client.conf"
    case c => c
  }
  private val config = ConfigFactory.load(configPath)
  private implicit val system = ActorSystem("bitway", config)
  private implicit val cluster = Cluster(system)
  private val exchanges = List(OkcoinCn, OkcoinEn, Btcchina, Huobi, Bitstamp)
  val routers = new LocalRouters(exchanges)
  val backend = system.actorOf(Props(new Bitway(routers)), name = "backend")
  println("Example: Client.backend ! SomeMessage()")

  def getPaymentRequest(id: String) = {
    val url = "http://192.168.0.125:9000/api/v1/i/" + id
    val future: ListenableFuture[PaymentSession] = PaymentSession.createFromUrl(url)
    val session = future.get()
    val memo = session.getMemo()
    val amountWanted = session.getValue()
    val req = session.getSendRequest()
    // wallet.completeTx(req)
    println("~" * 40 + req.tx)
  }

  def getLatestInvoice(from: Long) = {
    val ic = new InvoiceColl
    val query = QueryInvoice(None, 0, 100, None, Some(from), None, None, None)
    ic.getItems(query) foreach { case m => println(m) }
  }
}
