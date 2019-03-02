import com.coinport.bitway.actors.invoice.InvoiceConfig
import com.coinport.bitway.data.{ExchangeRate, Currency}
import com.coinport.bitway.data.Currency._

new InvoiceConfig {
  override val isTest: Boolean = false
  override val maxPrice: collection.Map[Currency, Double] = Map(Cny -> 100000.00, Usd -> 16000.00, Btc -> 40.00)
  override val defaultBidRates: collection.Map[Currency, ExchangeRate] = Map(Cny -> ExchangeRate(Map(5000.0 -> 1556.0, 10000.0 -> 1552.0, 50000.0 -> 1545.0, 100000.0 -> 1535.0)),
    Usd -> ExchangeRate(Map(500.0 -> 254.0, 1000.0 -> 253.0, 5000.0 -> 250.0, 10000.0 -> 245.0)))
  override val defaultAskRates: collection.Map[Currency, ExchangeRate] = Map(Cny -> ExchangeRate(Map(2.0 -> 2700.00, 5.0 -> 2750.0, 10.0 -> 2800.0, 20.0 -> 2900.0)),
    Usd -> ExchangeRate(Map(2.0 -> 440.0, 5.0 -> 445.0, 10.0 -> 450.0, 20.0 -> 460.0)))
}
