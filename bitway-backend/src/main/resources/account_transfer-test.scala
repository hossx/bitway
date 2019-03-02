import com.coinport.bitway.actors.payment.transfer.AccountTransferConfig
import com.coinport.bitway.data.Currency
import com.coinport.bitway.data.Currency.Btc

new AccountTransferConfig {
  override val confirmNumMap: collection.Map[Currency, Int] = collection.Map(Btc -> 1)
  override val succeededRetainNum: collection.Map[Currency, Int] = collection.Map(Btc -> 100)
}
