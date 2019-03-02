package com.coinport.bitway.actors.account

import akka.actor._
import akka.event.LoggingReceive
import akka.persistence._

import com.coinport.bitway.common._
import com.coinport.bitway.data._
import com.mongodb.casbah.MongoDB

class MerchantAccessActor(db: MongoDB) extends ExtendedView with MerchantAccessHandler with AccountManagerBehavior with ActorLogging {
  override val processorId = "account"
  override val viewId = "merchant"
  override implicit val logger = log

  val manager = new AccountDataManager()
  val coll = db("merchant")

  def receive = LoggingReceive {
    case Persistent(r: DoRegister, _) =>
      updateState(r)
      writeMerchant(r.merchant.id)

    case Persistent(r: DoUpdateMerchant, _) =>
      updateState(r)
      writeMerchant(r.merchant.id)

    case Persistent(r: DoSetMerchantStatus, _) =>
      updateState(r)
      saveItem(r.merchant.get.copy(status = r.status, updated = r.timestamp))

    case Persistent(msg, _) => updateState(msg)

    case m: QueryMerchants =>
      sender ! QueryMerchantsResult(getItems(m), countItems(m))
  }

  def writeMerchant(merchantId: Long) {
    manager.getMerchant(merchantId) match {
      case Some(merchant) => saveItem(merchant)
      case _ =>
        log.error("unable update not exists merchant")
    }
  }

}
