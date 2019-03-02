package com.coinport.bitway.actors.account

import com.coinport.bitway.data._
import com.coinport.bitway.serializers.ThriftBinarySerializer
import com.mongodb.casbah.Imports._

trait MerchantAccessHandler {
  val MERCHANT_ID = "_id" //access id
  val MERCHANT_NAME = "nm"
  val EMAIL = "em"
  val EMAIL_VERIFIED = "ev"
  val FEE_RATE = "fr"
  val CONST_FEE = "cf"
  val STATUS = "st"
  val CREATED_TIME = "c@"
  val UPDATED_TIME = "u@"
  val MERCHANT_BINARY = "b"

  val converter = new ThriftBinarySerializer

  def coll: MongoCollection

  def saveItem(merchant: Merchant) = coll += toBson(merchant)

  def countItems(q: QueryMerchants): Long = coll.count(mkQuery(q))

  def getItems(q: QueryMerchants): Seq[Merchant] =
    coll.find(mkQuery(q)).sort(DBObject(MERCHANT_ID -> -1)).skip(q.cursor.skip).limit(q.cursor.limit).map(toClass(_)).toSeq

  def getItemById(id: Long): Seq[Merchant] =
    coll.find(MongoDBObject(MERCHANT_ID -> id)).map(toClass(_)).toSeq

  private def toBson(merchant: Merchant) = {
    MongoDBObject(
      MERCHANT_ID -> merchant.id,
      MERCHANT_NAME -> merchant.name.getOrElse(""),
      EMAIL -> merchant.email.getOrElse(""),
      EMAIL_VERIFIED -> String.valueOf(merchant.emailVerified.getOrElse(false)),
      FEE_RATE -> merchant.feeRate,
      CONST_FEE -> merchant.constFee,
      STATUS -> merchant.status.value,
      CREATED_TIME -> merchant.created.getOrElse(System.currentTimeMillis()),
      UPDATED_TIME -> merchant.updated.getOrElse(System.currentTimeMillis()),
      MERCHANT_BINARY -> converter.toBinary(merchant))
  }

  private def toClass(obj: MongoDBObject): Merchant = {
    converter.fromBinary(obj.getAsOrElse(MERCHANT_BINARY, null), Some(classOf[Merchant.Immutable])).asInstanceOf[Merchant]
  }

  private def mkQuery(q: QueryMerchants) = {
    var query = MongoDBObject()
    if (q.merchantId.isDefined) query = query ++ (MERCHANT_ID -> q.merchantId.get)
    if (q.email.isDefined) query = query ++ (EMAIL -> q.email.get)
    if (q.status.isDefined) query = query ++ (STATUS -> q.status.get.value)
    if (q.spanCur.isDefined) query = query ++ (CREATED_TIME $lte q.spanCur.get.from $gte q.spanCur.get.to)
    query
  }
}
