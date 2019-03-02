package com.coinport.bitway.actors.bill

import com.coinport.bitway.common.EmbeddedMongoForTestWithBF
import com.coinport.bitway.data._
import com.mongodb.casbah.{ MongoConnection, MongoCollection }
import org.specs2.mutable._
/**
 * Created by chenxi on 8/21/14.
 */
class BillManagerSpec extends EmbeddedMongoForTestWithBF with BillHandler {
  val coll = database("BillAccessDataManagerSpec")
  "BillDataStateSpec" should {
    "can insert and query Bill" in {
      coll.drop()
      coll.size should be(0)
      val bills = (0 to 10).map {
        i =>
          BillItem(
            i,
            merchantId = i % 2,
            currency = Currency.get(i % 2).get,
            amount = (i % 2).toDouble,
            bType = BillType.get(i % 4).get,
            timestamp = i,
            invoiceId = Some(i.toString)
          )
      }

      bills.foreach(addItem)

      val query = QueryBill(merchantId = 1, skip = 0, limit = 10)

      countItems(query) should be(5)
      getItems(query).map(_.timestamp) should equal(Seq(9, 7, 5, 3, 1))
    }
  }
}
