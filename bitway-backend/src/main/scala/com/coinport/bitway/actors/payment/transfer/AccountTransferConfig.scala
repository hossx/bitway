package com.coinport.bitway.actors.payment.transfer

import com.coinport.bitway.data._
import com.coinport.bitway.data.Currency._

class AccountTransferConfig(
  val confirmNumMap: collection.Map[Currency, Int] = collection.Map(Btc -> 1),
  val manualCurrency: collection.Set[Currency] = collection.Set.empty[Currency],
  val succeededRetainNum: collection.Map[Currency, Int] = collection.Map(Btc -> 100),
  val enableAutoConfirm: Boolean = true,
  val refundTimeOut: Long = 5 * 60 * 1000,
  val refundFeeRate: collection.Map[Currency, Double] = collection.Map(Btc -> 0.005, Cny -> 0.005, Usd -> 0.005))

