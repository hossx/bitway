package com.coinport.bitway.actors.bill

import java.nio.ByteBuffer

import com.coinport.bitway.data._
import com.coinport.bitway.serializers.ThriftBinarySerializer
import com.mongodb.casbah.Imports._
import com.mongodb.WriteConcern._

/**
 * Created by chenxi on 8/21/14.
 */

trait BillHandler {
  //merchant id
  val BID = "_id"
  val MERCHANT_ID = "mid"
  val CURRENCY = "c"
  val PRICE = "p"
  val BILL_TYPE = "bt"
  val CREATED_TIME = "c@"
  val BINARY = "ub"

  val converter = new ThriftBinarySerializer

  def coll: MongoCollection

  def addItem(item: BillItem) = coll.insert(toBson(item))

  def countItems(q: QueryBill): Long = coll.count(mkQuery(q))

  def getItems(q: QueryBill): Seq[BillItem] =
    coll.find(mkQuery(q)).sort(DBObject(CREATED_TIME -> -1)).skip(q.skip).limit(q.limit).map(toClass(_)).toSeq

  private def toBson(item: BillItem) = {
    MongoDBObject(BID -> item.id, MERCHANT_ID -> item.merchantId, CREATED_TIME -> item.timestamp, PRICE -> item.amount, CURRENCY -> item.currency.name,
      BILL_TYPE -> item.bType.name, BINARY -> converter.toBinary(item))
  }

  private def toClass(obj: MongoDBObject): BillItem =
    converter.fromBinary(obj.getAsOrElse(BINARY, null), Some(classOf[BillItem.Immutable])).asInstanceOf[BillItem]

  private def mkQuery(q: QueryBill) = {
    var query = MongoDBObject() ++ (MERCHANT_ID -> q.merchantId)

    if (q.currency.isDefined) query = query ++ (CURRENCY -> q.currency.get.name)
    if (q.billType.isDefined) query = query ++ (BILL_TYPE -> q.billType.get.name)
    if (q.from.isDefined && q.to.isDefined) query = query ++ (CREATED_TIME $gte q.from.get $lt q.to.get)
    else if (q.from.isDefined) query = query ++ (CREATED_TIME $gte q.from.get)
    else if (q.to.isDefined) query = query ++ (CREATED_TIME $lt q.to.get)

    query
  }
}
