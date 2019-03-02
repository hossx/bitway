/**
 * Copyright 2014 Coinport Inc. All Rights Reserved.
 * Author: c@coinport.com (Chao Ma)
 */

package com.coinport.bitway.config

import com.coinport.bitway.data._

class ExchangeConfig {
  var exchangeType: Exchange = Exchange.Huobi

  var accessKey: String = ""
  var secretKey: String = ""
  var userId: String = ""

  var supportMarkets: List[TradePair] = List.empty

  var minExchangeAmount: Map[TradePair, Double] = Map.empty
  var minExchangeUnit: Map[TradePair, Double] = Map.empty
  var exchangeFee: Map[TradePair, Double] = Map.empty
  var withdrawalFee: Map[Currency, Double] = Map.empty
  var minWithdrawalFee: Map[Currency, Double] = Map.empty

  var bidNum: Option[Int] = Some(50)
  var askNum: Option[Int] = Some(50)
  var ttl: Int = 60 * 1000 // 1 min

  var qpsLimit: Double = 0.1
  var maxRetry: Int = 3
  var timeout: Int = 7 // seconds

  var depositAddress: Map[Currency, String] = Map.empty
}
