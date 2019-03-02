package com.coinport.bitway.actors.account

import akka.actor._
import akka.actor.Actor.Receive
import akka.event.{ LoggingAdapter, LoggingReceive }
import akka.persistence._

import com.coinport.bitway.LocalRouters
import com.coinport.bitway.actors.notification.MandrillMailHandler
import com.coinport.bitway.api.model.CurrencyWrapper
import com.coinport.bitway.common._
import com.coinport.bitway.common.Constants._
import com.coinport.bitway.data._
import com.coinport.bitway.data.TransferType._
import com.coinport.bitway.util._

import java.security.SecureRandom

/**
 * This actor should be a persistent actor.
 *
 * Responsible for:
 * - manager accounts
 * - manager access tokens
 * - manage api calling history/stats
 *
 */

class AccountActor(routers: LocalRouters, secret: String = "", handler: MandrillMailHandler)
    extends ExtendedProcessor with EventsourcedProcessor with AccountManagerBehavior with ActorLogging {
  override def processorId = "account"
  override implicit val logger = log

  val manager = new AccountDataManager()

  lazy val channelToPayment = createChannelTo("invoice")
  lazy val paymentActorPath = routers.paymentActor.path

  override def identifyChannel: PartialFunction[Any, String] = {
    case ic: InvoiceComplete => "pm"
    case adm: AdminConfirmTransferSuccess => "pm"
    case ctr: CryptoTransferResult => "pm"
  }

  def receiveRecover = PartialFunction.empty[Any, Unit]

  def receiveCommand = LoggingReceive {
    case DoRegister(merchant, password) =>
      manager.getMerchant(merchant.email.get) match {
        case Some(_) =>
          sender ! RegisterFailed(ErrorCode.EmailAlreadyRegistered)
        case None =>
          val verificationToken = generateRandomHexToken(merchant.email.get)
          val regularMerchant = manager.regulateMerchant(merchant, password, verificationToken, generateSecretKey)
          persist(DoRegister(regularMerchant, password))(updateState)
          sender ! RegisterSucceeded(regularMerchant)
          sendEmailVerificationEmail(regularMerchant)
      }

    case DoLogin(email, password) =>
      manager.checkLogin(email, password) match {
        case Left(error) => sender ! LoginFailed(error)
        case Right(merchant) =>
          sender ! LoginSucceeded(merchant.id, email)
      }

    case e: CreateApiToken =>
      val token = if (e.token.isDefined) e.token.get else java.util.UUID.randomUUID.toString.replace("-", "")
      val secretKey = ""

      persist(e.copy(token = Some(token), secretKey = Some(secretKey)))(updateState)
      sender ! ApiTokenCreated(e.merchantId, token)

    case e: DestoryApiToken => sender ! persist(e)(updateState)
    case e: DestoryAllApiTokens => persist(e)(updateState)
    case e: DisableAccount => persist(e)(updateState)

    case DoValidateRegister(token) => manager.getMerchantWithVerificationToken(token) match {
      case Some(merchant) if merchant.verificationToken == Some(token) =>
        val newMerchant = merchant.copy(emailVerified = Some(true), updated = Some(System.currentTimeMillis()))
        persist(DoUpdateMerchant(newMerchant))(updateState)
        sender ! ValidateRegisterSucceeded(merchant.id)
      case _ => sender ! ValidateRegisterFailed(ErrorCode.InvalidToken)
    }

    case e @ DoRequestPasswordReset(email, _) => manager.getMerchant(email) match {
      case Some(merchant) if merchant.passwordResetToken.isDefined =>
        sender ! RequestPasswordResetSucceeded(merchant.id, merchant.email.get)
        sendRequestPasswordResetEmail(merchant)
      case Some(merchant) if merchant.passwordResetToken.isEmpty =>
        val passwordResetToken = generateRandomHexToken(email)
        persist(e.copy(token = Some(passwordResetToken))) {
          event =>
            updateState(event)
            sender ! RequestPasswordResetSucceeded(merchant.id, merchant.email.get)
            sendRequestPasswordResetEmail(manager.getMerchant(email).get)
        }
      case _ => sender ! RequestPasswordResetFailed(ErrorCode.InvalidToken)
    }

    case e @ DoResetPassword(_, token) => manager.getMerchantWithPasswordResetToken(token) match {
      case Some(merchant) if merchant.passwordResetToken == Some(token) =>
        persist(e)(updateState)
        sender ! ResetPasswordSucceeded(merchant.id, merchant.email.get)
      case _ =>
        sender ! ResetPasswordFailed(ErrorCode.InvalidToken)
    }

    case QueryMerchantById(id) =>
      //      log.info(s">>>>>>>>>>>>>>>> QueryMerchantById $id")
      sender ! QueryMerchantResult(manager.getMerchant(id))

    case e @ DoUpdateMerchant(merchant) => manager.getMerchant(merchant.id) match {
      case Some(_merchant) =>
        val newMerchant = _merchant.copy(
          name = merchant.contact.map(_.name),
          contact = merchant.contact,
          bankAccounts = merchant.bankAccounts,
          btcWithdrawalAddress = merchant.btcWithdrawalAddress,
          updated = Some(System.currentTimeMillis()))
        persist(DoUpdateMerchant(newMerchant))(updateState)
        sender ! UpdateMerchantSucceeded(newMerchant)
      case None => sender ! UpdateMerchantFailed(ErrorCode.MerchantNotExist)
    }

    case e @ DoSetMerchantStatus(id, status, _, _) =>
      manager.getMerchant(id) match {
        case Some(merchant) =>
          persist(e.copy(timestamp = Some(System.currentTimeMillis()), merchant = Some(merchant)))(updateState)
          sender ! SetMerchantStatusResult(ErrorCode.Ok)
        case _ =>
          sender ! SetMerchantStatusResult(ErrorCode.MerchantNotExist)
      }

    case DoUpdateMerchantFee(merchant) => manager.getMerchant(merchant.id) match {
      case Some(_merchant) =>
        val newMerchant = _merchant.copy(feeRate = merchant.feeRate,
          constFee = merchant.constFee, updated = Some(System.currentTimeMillis()))
        persist(DoUpdateMerchant(newMerchant))(updateState)
        sender ! UpdateMerchantSucceeded(newMerchant)
      case None => sender ! UpdateMerchantFailed(ErrorCode.MerchantNotExist)
    }

    case ValidateToken(token) => manager.tokenToMerchantId(token) match {
      case Some(merchantId) =>
        manager.getMerchant(merchantId) match {
          case Some(merchant) if merchant.status == MerchantStatus.Revoked => sender ! AccessDenied(ErrorCode.MerchantRevoked)
          case Some(m) => sender ! AccessGranted(m)
          case _ => sender ! AccessDenied(ErrorCode.InvalidToken)
        }

      case None => sender ! AccessDenied(ErrorCode.InvalidToken)
    }

    case p @ ConfirmablePersistent(msg: InvoiceComplete, _, _) =>
      persist(msg) {
        event =>
          confirm(p)
          updateState(event)
      }

    case MerchantSettle(t: AccountTransfer) =>
      t.`type` match {
        case Settle if t.externalAmount.isDefined && t.externalAmount.get > 0.0 && t.address.isDefined && t.merchantId > 0L && validMerchantStatus(t.merchantId) =>
          val updated = t.copy(amount = new CurrencyWrapper(t.externalAmount.get).internalValue(t.currency), created = Some(System.currentTimeMillis()), innerType = Some(Withdrawal))
          val adjustment = CashAccount(updated.currency, -updated.amount, updated.amount)
          if (!manager.canUpdateCashAccount(updated.merchantId, adjustment)) {
            sender ! RequestTransferFailed(ErrorCode.InsufficientFund)
          } else {
            persist(MerchantSettle(updated)) {
              event =>
                updateState(event)
                channelToPayment forward Deliver(Persistent(event), paymentActorPath)
            }
          }
        case _ =>
          sender ! RequestTransferFailed(ErrorCode.UnsupportTransferType)
      }

    case DoPaymentRequest(t: AccountTransfer) =>
      t.`type` match {
        case Payment if t.address.isDefined && t.merchantId > 1E9.toLong && t.paymentData.isDefined && t.paymentData.get.currency == Some(Currency.Btc)
          && t.paymentData.get.externalAmount.isDefined && t.paymentData.get.externalAmount.get > 0.0 && validMerchantStatus(t.merchantId) =>
          val updated = t.copy(
            currency = Currency.Btc,
            paymentData = Some(t.paymentData.get.copy(amount = Some(new CurrencyWrapper(t.paymentData.get.externalAmount.get).internalValue(t.paymentData.get.currency.get)))),
            created = Some(System.currentTimeMillis()),
            innerType = Some(Withdrawal))
          val adjustment = CashAccount(updated.paymentData.get.currency.get, -updated.paymentData.get.amount.get, updated.paymentData.get.amount.get)
          if (!manager.canUpdateCashAccount(updated.merchantId, adjustment)) {
            sender ! RequestTransferFailed(ErrorCode.InsufficientFund)
          } else {
            persist(DoPaymentRequest(updated)) {
              event =>
                updateState(event)
                channelToPayment forward Deliver(Persistent(event), paymentActorPath)
            }
          }
        case _ =>
          sender ! RequestTransferFailed(ErrorCode.UnsupportTransferType)
      }

    case p @ ConfirmablePersistent(m: AdminConfirmTransferSuccess, _, _) =>
      persist(m) {
        event =>
          confirm(p)
          updateState(event)
      }

    case p @ ConfirmablePersistent(m: AdminConfirmTransferFailure, _, _) =>
      persist(m) { event => confirm(p); updateState(event) }

    case m: AdminConfirmTransferFailure =>
      persist(m)(updateState)

    case p @ ConfirmablePersistent(m: DoCancelSettle, _, _) =>
      persist(m) { event => confirm(p); updateState(event) }

    case p @ ConfirmablePersistent(m: DoCancelTransfer, _, _) =>
      persist(m) { event => confirm(p); updateState(event) }

    case m: CryptoTransferResult =>
      handleCryptoTransferResult(m)

    case p @ ConfirmablePersistent(m: CryptoTransferResult, _, _) =>
      confirm(p)
      handleCryptoTransferResult(m)

    case m @ LockAccountRequest(merchantId, currency, amount, onlyCheck) =>
      if (validMerchantStatus(merchantId) && manager.canUpdateCashAccount(merchantId, CashAccount(currency, -amount, amount))) {
        if (!onlyCheck) {
          persist(m)(updateState)
        }
        sender ! LockAccountResult(true)
      } else {
        sender ! LockAccountResult(false)
      }

    case MerchantBalanceRequest(merchantId, currencyList) =>
      sender ! manager.queryMerchantBalance(merchantId, currencyList)

    case SendVerificationMail(email, code) =>
      sendVerificationEmail(email, code)
      sender ! SendVerificationMailSucceeded(email)

    case m: AdjustAggregationAccount =>
      manager.canUpdateCashAccount(Constants.AGGREGATION_UID, m.adjustment) match {
        case true =>
          persist(m) {
            event =>
              updateState(event)
              sender ! AdjustAggregationAccountResult(ErrorCode.Ok, manager.getMerchantAccounts(Constants.AGGREGATION_UID))
          }
        case _ => sender ! AdjustAggregationAccountResult(ErrorCode.InvalidAmount)
      }

    case m: AdjustMerchantAccount =>
      manager.canUpdateCashAccount(m.merchantId, m.adjustment) match {
        case true =>
          persist(m) {
            event =>
              updateState(event)
              sender ! AdjustMerchantAccountResult(ErrorCode.Ok, manager.getMerchantAccounts(m.merchantId))
          }
        case _ =>
          sender ! AdjustMerchantAccountResult(ErrorCode.InvalidAmount)
      }

    case m: QueryAccountsById =>
      sender ! QueryAccountsByIdResult(manager.getMerchantAccounts(m.id))

    case m @ UpdateSecretKey(_, _, _) =>
      val secretKey = generateSecretKey
      persist(m.copy(secretKey = Some(secretKey), timestamp = Some(System.currentTimeMillis())))(updateState)
      sender ! SecretKeyUpdated(m.merchantId, Some(secretKey))

    case m @ MigrateSecretKey(merchantId, _) =>
      manager.getMerchant(merchantId) match {
        case Some(merchant) if merchant.tokenAndKeyPairs.values.filter(_.nonEmpty).nonEmpty && merchant.secretKey.isEmpty =>
          persist(m.copy(timestamp = Some(System.currentTimeMillis())))(updateState)
        case _ =>
      }
  }

  private def handleCryptoTransferResult(m: CryptoTransferResult) {
    persist(m) {
      event =>
        updateState(event)
    }
  }

  private val rand = SecureRandom.getInstance("SHA1PRNG", "SUN")
  private val hexTokenSecret = MHash.sha256Base64(secret + "hexTokenSecret")

  private def generateRandomHexToken(email: String) =
    MHash.sha256Base32(email + rand.nextLong + hexTokenSecret).substring(0, 40)

  private val verifyTemplateName = "pay-register-confirm"
  private def sendEmailVerificationEmail(merchant: Merchant) {
    handler.sendMail(merchant.email.get, verifyTemplateName, Seq(
      "NAME" -> merchant.name.getOrElse(merchant.email.get),
      "TOKEN" -> merchant.verificationToken.get)
    )
  }

  private val pwdResetTemplateName = "pay-password-reset"
  private def sendRequestPasswordResetEmail(merchant: Merchant) {
    handler.sendMail(merchant.email.get, pwdResetTemplateName, Seq(
      "NAME" -> merchant.name.getOrElse(merchant.email.get),
      "TOKEN" -> merchant.passwordResetToken.get)
    )
  }

  private val verificationTemplateName = "pay-verificationcode"
  private def sendVerificationEmail(email: String, code: String) {
    handler.sendMail(email, verificationTemplateName, Seq("CODE" -> code))
  }

  private def generateSecretKey = generateRandomHexToken("coinport-sk").substring(0, 16)

  private def validMerchantStatus(merchantId: Long): Boolean = {
    manager.accountMap.get(merchantId) match {
      case Some(merchant) if merchant.status == MerchantStatus.Active => true
      case _ => false
    }
  }
}

trait AccountManagerBehavior {
  val manager: AccountDataManager
  implicit val logger: LoggingAdapter

  def updateState: Receive = {
    case DoRegister(regularMerchant, _) => manager.registerMerchant(regularMerchant)
    case CreateApiToken(merchantId, token, secretKey, merchant) =>
      assert(token.isDefined)
      manager.createToken(merchantId, token.get, secretKey.get, merchant)
    case DestoryApiToken(token) =>
      manager.removeToken(token) match {
        case Some(accountId) => ApiTokenDestroied(accountId, token)
        case None => ApiTokenNotExist(token)
      }
    case e: DestoryAllApiTokens => // not supported yet
    case e: DisableAccount => // not supported yet
    case DoRequestPasswordReset(email, token) =>
      manager.requestPasswordReset(email, token.get)
    case DoResetPassword(password, token) =>
      manager.resetPassword(password, token)
    case DoUpdateMerchant(merchant) => manager.updateMerchant(merchant)
    case DoSetMerchantStatus(_, status, timestamp, merchant) => manager.setMerchantStatus(status, timestamp, merchant)
    case InvoiceComplete(completes) =>
      manager.billingAccount(completes)
    case MerchantSettle(t: AccountTransfer) =>
      manager.updateCashAccount(t.merchantId, CashAccount(t.currency, -t.amount, t.amount))
    case DoPaymentRequest(t: AccountTransfer) =>
      manager.updateCashAccount(t.merchantId, CashAccount(t.paymentData.get.currency.get, -t.paymentData.get.amount.get, t.paymentData.get.amount.get))
    case AdminConfirmTransferFailure(t, _) => failedTransfer(t)
    case DoCancelSettle(t) => failedTransfer(t)
    case DoCancelTransfer(t) => failedTransfer(t)
    case AdminConfirmTransferSuccess(t) => succeededTransfer(t)
    case CryptoTransferResult(multiTransfers, invoiceComplete) => {
      multiTransfers.values foreach {
        transferWithFee =>
          transferWithFee.transfers.foreach {
            transfer =>
              transfer.status match {
                case TransferStatus.Succeeded => succeededTransfer(transfer)
                case TransferStatus.Failed => failedTransfer(transfer)
                case _ => logger.error("Unexpected transferStatus" + transfer.toString)
              }
          }
          val txType = transferWithFee.transfers(0).`type`
          if (txType != Deposit) {
            transferWithFee.minerFee foreach (subtractMinerFee(transferWithFee.transfers(0).currency, _))
          }
      }
      invoiceComplete.foreach {
        ic =>
          manager.billingAccount(ic.completes)
      }
    }
    case LockAccountRequest(merchantId, currency, amount, _) =>
      manager.updateCashAccount(merchantId, CashAccount(currency, -amount, amount))

    case AdjustAggregationAccount(adjustment) =>
      manager.adjustAggregationAccount(adjustment)

    case AdjustMerchantAccount(merchantId, adjustment) =>
      manager.updateCashAccount(merchantId, adjustment)

    case UpdateSecretKey(merchantId, secretKey, timestamp) =>
      manager.updateSecretKey(merchantId, secretKey.get, timestamp)

    case MigrateSecretKey(merchantId, timestamp) =>
      manager.migrateSecretKey(merchantId, timestamp)

    case _ =>
  }

  private def succeededTransfer(t: AccountTransfer) {
    t.innerType match {
      case Some(Withdrawal) if t.`type` != SendCoin => // SendCoin should not update merchant account
        val (currency, amount) = getWithdrawalLikeAmount(t)
        t.fee match {
          case Some(f) if f.amount > 0 =>
            manager.transferFundFromPendingWithdrawal(f.payer, f.payee.getOrElse(COINPORT_UID), f.currency, f.amount)
            manager.updateCashAccount(t.merchantId, CashAccount(currency, 0, f.amount - amount))
          case _ =>
            manager.updateCashAccount(t.merchantId, CashAccount(currency, 0, -amount))
        }
      case _ =>
    }
  }

  private def failedTransfer(t: AccountTransfer) {
    t.innerType match {
      case Some(Withdrawal) if t.`type` != SendCoin =>
        val (currency, amount) = getWithdrawalLikeAmount(t)
        manager.updateCashAccount(t.merchantId, CashAccount(currency, amount, -amount))
      case _ =>
    }
  }

  private def getWithdrawalLikeAmount(t: AccountTransfer): (Currency, Long) = {
    t.`type` match {
      case Refund => (t.refundCurrency.get, t.refundInternalAmount.get)
      case Payment =>
        if (t.paymentData.isDefined && t.paymentData.get.currency.isDefined && t.paymentData.get.amount.isDefined) {
          (t.paymentData.get.currency.get, t.paymentData.get.amount.get)
        } else {
          logger.error(s"Payment transfer with invalid paymentData : $t")
          (Currency.Btc, 0L)
        }
      case _ => (t.currency, t.amount)
    }
  }

  private def subtractMinerFee(currency: Currency, minerFee: Long) {
    manager.updateCashAccount(CRYPTO_UID, CashAccount(currency, -minerFee, 0))
  }
}