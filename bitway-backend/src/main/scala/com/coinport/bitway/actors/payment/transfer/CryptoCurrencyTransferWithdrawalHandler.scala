package com.coinport.bitway.actors.payment.transfer

import com.coinport.bitway.data._
import TransferType._
import TransferStatus._

object CryptoCurrencyTransferWithdrawalHandler extends CryptoCurrencyTransferWithdrawalLikeBase {

  override def handleFailed(handler: CryptoCurrencyTransferHandler, error: Option[ErrorCode] = None) {
    error match {
      // Failed by bitway, not by backcore, should not terminate transfer
      case None if handler.item.txType == Some(Settle) || handler.item.txType == Some(Refund) || handler.item.txType == Some(Payment) =>
        handler.onFail(TransferStatus.BitwayFailed)
      case _ =>
        handler.onFail()
        super.handleFailed(handler, error)
    }
  }

  override def item2UnlockInfo(item: CryptoCurrencyTransferItem): Option[CryptoCurrencyTransferInfo] = {
    item.status match {
      case Some(Failed) | Some(Succeeded) =>
        item2CryptoCurrencyTransferInfo(item)
      case _ =>
        logger.warning(s"""${"~" * 50} item2BitwayInfo() get unexpected item : ${item.toString}""")
        None
    }
  }

  override def item2CryptoCurrencyTransferInfo(item: CryptoCurrencyTransferItem): Option[CryptoCurrencyTransferInfo] = {
    Some(CryptoCurrencyTransferInfo(item.id, Some(item.to.get.address), item.to.get.internalAmount, item.to.get.amount, None))
  }

  override def item2InvoiceUpdate(item: CryptoCurrencyTransferItem): Option[InvoiceUpdate] = {
    if (item.txType == Some(Refund)) {
      item.accountTransferId match {
        case Some(transferId) =>
          transferHandler.get(transferId) match {
            case Some(transfer) if transfer.invoiceId.isDefined =>
              Some(InvoiceUpdate(transfer.invoiceId.get, status = InvoiceStatus.Complete, refundAccAmount = Some(transfer.refundAmount.get)))
            case _ => None
          }
        case _ => None
      }
    } else {
      None
    }
  }

  override def item2PaymentInfo(item: CryptoCurrencyTransferItem): Option[AccountTransfer] = {
    if (item.txType == Some(Payment)) {
      item.accountTransferId match {
        case Some(transferId) =>
          transferHandler.get(transferId) match {
            case Some(transfer) if transfer.paymentData.isDefined =>
              Some(transfer)
            case _ =>
              None
          }
        case _ => None
      }
    } else {
      None
    }
  }

}

object CryptoCurrencyTransferUnknownHandler extends CryptoCurrencyTransferBase {

  override def newHandlerFromItem(item: CryptoCurrencyTransferItem): CryptoCurrencyTransferHandler = {
    null
  }

  override def handleTx(currency: Currency, tx: BitcoinTx, timestamp: Option[Long], includedBlockHeight: Option[Long] = None) {
    refreshLastBlockHeight(currency, includedBlockHeight)
  }

}