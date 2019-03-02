package com.coinport.bitway.actors.invoice

import com.coinport.bitway.data.{ ExchangeRate, Currency }

class InvoiceConfig(
  val isTest: Boolean = false,
  val maxPrice: collection.Map[Currency, Double] = Map.empty[Currency, Double],
  val defaultBidRates: collection.Map[Currency, ExchangeRate] = Map.empty[Currency, ExchangeRate],
  val defaultAskRates: collection.Map[Currency, ExchangeRate] = Map.empty[Currency, ExchangeRate])
