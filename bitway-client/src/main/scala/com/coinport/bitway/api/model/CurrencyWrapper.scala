package com.coinport.bitway.api.model

import com.coinport.bitway.data.Currency
import com.coinport.bitway.data.Currency._

object CurrencyConversion {
  // exponent (10-based) of the factor between internal value and external value
  // Btc -> 3 means: 1 BTC(external value) equals 1 * 10E3 MBTC(internal value)
  val exponents = Map[Currency, Double](
    Btc -> 8,
    Doge -> 8,
    Cny -> 5,
    Usd -> 5
  )

  val multipliers: Map[Currency, Double] = exponents map {
    case (k, v) =>
      k -> math.pow(10, v)
  }

  val currencyDecimals = Map[Currency, Int](
    Cny -> 4,
    Btc -> 4,
    Doge -> 4
  )

  def getExponent(currency: Currency) = exponents.get(currency).getOrElse(1.0).toInt

  def getMultiplier(currency: Currency) = multipliers.get(currency).getOrElse(1.0)

  def roundExternal(external: Double, currency: Currency): Double = {
    val multiplier = CurrencyConversion.multipliers(currency)
    (BigDecimal((BigDecimal(external) * multiplier).toLong) / multiplier).toDouble
  }
}

class CurrencyWrapper(val value: Double) {
  def externalValue(currency: Currency): Double = {
    (BigDecimal(value) / CurrencyConversion.multipliers(currency)).toDouble
  }

  def internalValue(currency: Currency): Long = {
    (BigDecimal(value) * CurrencyConversion.multipliers(currency)).toLong
  }

  def ceiledInternalValue(currency: Currency): Long = {
    (BigDecimal(value) * CurrencyConversion.multipliers(currency)).doubleValue().ceil.toLong
  }

  def E(currency: Currency) = externalValue(currency)

  def I(currency: Currency) = internalValue(currency)
}