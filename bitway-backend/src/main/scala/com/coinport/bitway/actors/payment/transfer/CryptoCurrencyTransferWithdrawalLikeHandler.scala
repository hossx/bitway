package com.coinport.bitway.actors.payment.transfer

import com.coinport.bitway.data._
import com.coinport.bitway.data.TransferStatus._

class CryptoCurrencyTransferWithdrawalLikeHandler extends CryptoCurrencyTransferHandler {
  def this(item: CryptoCurrencyTransferItem)(implicit env: TransferEnv) {
    this()
    this.item = item
    setEnv(env, None)
  }

  def this(t: AccountTransfer, from: Option[CryptoCurrencyTransactionPort], to: Option[CryptoCurrencyTransactionPort], timestamp: Option[Long])(implicit env: TransferEnv) {
    this()
    setEnv(env, timestamp)
    // id, currency, sigId, txid, merchantId, from, to(user's internal address), includedBlockHeight, txType, status, accountTransferId, created, updated
    item = CryptoCurrencyTransferItem(env.manager.getNewTransferItemId, t.currency, None, None, Some(t.merchantId), from, to, None, Some(t.`type`), Some(Confirming), Some(t.id), timestamp)
    saveItemToMongo()
  }
}
