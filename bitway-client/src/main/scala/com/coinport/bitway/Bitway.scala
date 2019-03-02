package com.coinport.bitway

import akka.actor.Actor
import akka.event.LoggingReceive
import com.coinport.bitway.data._
import org.slf4s.Logging

final class Bitway(routers: LocalRouters) extends Actor with Logging {
  def receive = {
    LoggingReceive {
      // AccountActor
      case m: DoRegister => routers.accountActor forward m
      case m: DoLogin => routers.accountActor forward m
      case m: CreateApiToken => routers.accountActor forward m
      case m: DestoryApiToken => routers.accountActor forward m
      //      case m: DestoryAllApiTokens => routers.accountActor forward m
      //      case m: DisableAccount => routers.accountActor forward m
      case m: DoValidateRegister => routers.accountActor forward m
      case m: DoRequestPasswordReset => routers.accountActor forward m
      case m: DoResetPassword => routers.accountActor forward m
      case m: QueryMerchantById => routers.accountActor forward m
      case m: DoUpdateMerchant => routers.accountActor forward m
      //      case m: DoUpdateMerchantFee => routers.accountActor forward m
      case m: ValidateToken => routers.accountActor forward m
      case m: MerchantSettle => routers.accountActor forward m
      case m: MerchantBalanceRequest => routers.accountActor forward m
      case m: SendVerificationMail => routers.accountActor forward m
      case m: DoSetMerchantStatus => routers.accountActor forward m
      case m: AdjustAggregationAccount => routers.accountActor forward m
      case m: QueryAccountsById => routers.accountActor forward m
      case m: UpdateSecretKey => routers.accountActor forward m
      case m: MigrateSecretKey => routers.accountActor forward m
      case m: DoPaymentRequest => routers.accountActor forward m

      // MerchantAccessActor
      case m: QueryMerchants => routers.merchantAccessActor forward m

      // BillActor
      case m: QueryBill => routers.billActor forward m

      // BlockchainActor
      case m: CreateInvoice => routers.blockchainActor forward m
      case m: SendRawTransaction => routers.blockchainActor forward m
      case m: CleanBlockChain => routers.blockchainActor forward m
      case m: AdjustAddressAmount => routers.blockchainActor forward m
      case m: AllocateNewAddress => routers.blockchainActor forward m
      case m: QueryBlockChainAmount => routers.blockchainActor forward m

      // BlockchainView
      case m: QueryAsset => routers.blockchainView forward m

      // InvoiceAccessActor
      case m: QueryInvoiceById => routers.invoiceAccessActor forward m
      case m: QueryInvoiceByIds => routers.invoiceAccessActor forward m
      case m: QueryInvoice => routers.invoiceAccessActor forward m
      //      case m: QueryMerchantBalance => routers.invoiceAccessActor forward m // sum invoice.amount will not get right balance
      case m: PaymentReceived => routers.invoiceAccessActor forward m
      // case m: InvoicePartialUpdate => routers.invoiceAccessActor forward m
      case m: OrderLogisticsUpdated => routers.invoiceAccessActor forward m

      // PaymentActor
      case m: InvoiceDebugRequest => routers.paymentActor forward m
      case m: GetBestBidRates => routers.paymentActor forward m
      case m: GetBestAskRates => routers.paymentActor forward m
      //      case m: DoSendCoin => routers.paymentActor forward m
      case m: MerchantRefund => routers.paymentActor forward m
      case m: AdminConfirmTransferFailure => routers.paymentActor forward m
      case m: DoCancelSettle => routers.paymentActor forward m
      case m: DoCancelTransfer => routers.paymentActor forward m
      case m: AdminConfirmTransferSuccess => routers.paymentActor forward m
      case m: QueryTransfer => routers.paymentActor forward m
      case m: GetCurrentExchangeRate => routers.paymentActor forward m

      //-------------------------------------------------------------------------
      case m =>
        log.error("Coinex received unsupported event: " + m.toString)
        sender ! MessageNotSupported(m.toString)
    }
  }
}
