package com.coinport.bitway.actors.bill

/**
 * Created by chenxi on 8/21/14.
 */

import akka.actor._
import akka.event.LoggingReceive
import akka.persistence._

import com.coinport.bitway.common._
import com.coinport.bitway.data._
import com.mongodb.casbah.MongoDB
import com.coinport.bitway.LocalRouters

class BillActor(routers: LocalRouters, db: MongoDB) extends ExtendedActor with BillHandler with ActorLogging {

  val coll = db("bills")

  def receive = LoggingReceive {
    // ============ The following events are from frontend:
    case e: QueryBill =>
      sender ! QueryBillResult(getItems(e), countItems(e).toInt)
  }
}
