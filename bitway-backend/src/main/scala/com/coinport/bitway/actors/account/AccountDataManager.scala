package com.coinport.bitway.actors.account

import com.coinport.bitway.api.model.CurrencyWrapper
import com.coinport.bitway.common._
import com.coinport.bitway.common.Constants._
import com.coinport.bitway.data._
import com.coinport.bitway.data.Implicits._
import com.coinport.bitway.util._

import org.slf4s.Logging
import scala.collection.mutable.Map

class AccountDataManager(passwordSecret: String = "") extends Manager[TAccountState] with Logging {
  var lastMerchantId = 1E9.toLong

  val idMap = Map.empty[Long, Long] // email hash to user Id
  val accountMap = Map.empty[Long, Merchant]
  val tokenMap = Map.empty[String, Long]
  val verificationTokenMap: Map[String, Long] = Map.empty[String, Long]
  val passwordResetTokenMap: Map[String, Long] = Map.empty[String, Long]
  var aggregationAccount = Map.empty[Currency, CashAccount]

  def getSnapshot = TAccountState(
    getFiltersSnapshot,
    accountMap.map(kv => (kv._1 -> kv._2.copy(settleAccount = Some(Map.empty[Currency, CashAccount] ++ kv._2.settleAccount.getOrElse(Map.empty[Currency, CashAccount]))))),
    tokenMap,
    Some(idMap.clone),
    Some(verificationTokenMap.clone),
    Some(passwordResetTokenMap.clone),
    Some(lastMerchantId),
    Map.empty[Long, Merchant],
    Some(aggregationAccount.clone)
  )

  def loadSnapshot(snapshot: TAccountState) = {
    loadFiltersSnapshot(snapshot.filters)
    accountMap.clear
    accountMap ++= snapshot.accountMap.map(item => (item._1 -> item._2))
    tokenMap.clear; tokenMap ++= snapshot.tokenMap
    idMap.clear; idMap ++= snapshot.idMap.getOrElse(Map.empty[Long, Long])
    verificationTokenMap.clear; verificationTokenMap ++= snapshot.verificationTokenMap.getOrElse(Map.empty[String, Long])
    passwordResetTokenMap.clear; passwordResetTokenMap ++= snapshot.passwordResetTokenMap.getOrElse(Map.empty[String, Long])
    lastMerchantId = snapshot.lastMerchantId.getOrElse(1E9.toLong)
    aggregationAccount.clear
    aggregationAccount ++= snapshot.aggregationAccount.getOrElse(Map.empty[Currency, CashAccount])
  }

  def tokenToMerchantId(token: String): Option[Long] = tokenMap.get(token)

  def createToken(accountId: Long, token: String, secretKey: String,
    merchant: Option[Merchant] = None): Either[ErrorCode, String] =
    accountMap.get(accountId) match {
      case Some(_merchant) =>
        tokenMap += token -> accountId
        accountMap += accountId -> _merchant.copy(tokenAndKeyPairs = _merchant.tokenAndKeyPairs + (token -> secretKey))
        Right(token)
      case None => Left(ErrorCode.MerchantNotExist)
    }

  def removeToken(token: String): Option[(Long)] =
    tokenMap.get(token) map { accountId =>
      accountMap.get(accountId) foreach { merchant =>
        accountMap += accountId -> merchant.copy(tokenAndKeyPairs = merchant.tokenAndKeyPairs - token)
      }
      tokenMap -= token
      accountId
    }

  def regulateMerchant(merchant: Merchant, password: String, verificationToken: String, secretKey: String): Merchant = {
    val emailOpt = merchant.email.map(_.trim.toLowerCase)
    val id = getMerchantId
    val passwordHash = computePassword(id, merchant.email.get, password)

    merchant.copy(
      id = id,
      email = emailOpt,
      emailVerified = Some(false),
      passwordHash = Some(passwordHash),
      verificationToken = Some(verificationToken),
      created = Some(System.currentTimeMillis()),
      secretKey = Some(secretKey)
    )
  }

  def registerMerchant(merchant: Merchant): Merchant = {
    assert(!accountMap.contains(merchant.id))
    addMerchant(merchant)
    lastMerchantId = merchant.id + 1
    merchant
  }

  def checkLogin(email: String, password: String): Either[ErrorCode, Merchant] =
    getMerchant(email) match {
      case None => Left(ErrorCode.MerchantNotExist)
      case Some(m) if m.status == MerchantStatus.Revoked => Left(ErrorCode.MerchantRevoked)
      case Some(merchant) =>
        val passwordHash = computePassword(merchant.id, merchant.email.get, password)
        if (!merchant.emailVerified.get) Left(ErrorCode.EmailNotVerified)
        else if (Some(passwordHash) == merchant.passwordHash) Right(merchant)
        else Left(ErrorCode.PasswordNotMatch)
    }

  def requestPasswordReset(email: String, passwordResetToken: String): Merchant = {
    val merchant = getMerchant(email).get
    passwordResetTokenMap += passwordResetToken -> merchant.id
    val updatedMerchant = merchant.copy(passwordResetToken = Some(passwordResetToken))
    accountMap += merchant.id -> updatedMerchant
    updatedMerchant
  }

  def resetPassword(newPassword: String, passwordResetToken: String): Merchant = {
    val merchant = getMerchantWithPasswordResetToken(passwordResetToken).get
    val passwordHash = computePassword(merchant.id, merchant.email.get, newPassword)
    val updatedMerchant = merchant.copy(passwordHash = Some(passwordHash), passwordResetToken = None)
    accountMap += merchant.id -> updatedMerchant
    passwordResetTokenMap -= passwordResetToken
    updatedMerchant
  }

  def getMerchant(id: Long): Option[Merchant] = accountMap.get(id)

  def getMerchant(email: String): Option[Merchant] = {
    idMap.get(MHash.murmur3(email.trim.toLowerCase)).map(accountMap.get(_).get)
  }

  def getMerchantWithPasswordResetToken(token: String): Option[Merchant] = {
    passwordResetTokenMap.get(token).map(accountMap.get(_).get)
  }

  def getMerchantWithVerificationToken(token: String): Option[Merchant] = {
    verificationTokenMap.get(token).map(accountMap.get(_).get)
  }

  def updateMerchant(merchant: Merchant): Merchant = {
    accountMap += merchant.id -> merchant
    merchant
  }

  def updateSecretKey(merchantId: Long, secretKey: String, timestamp: Option[Long]) = {
    getMerchant(merchantId) match {
      case Some(merchant) => accountMap += merchantId -> merchant.copy(secretKey = Some(secretKey), updated = timestamp)
      case _ =>
        log.info(s">>>>>>>>>>>>>>>> updateSecretKey for not exists merchant $merchantId")
    }
  }

  def migrateSecretKey(merchantId: Long, timestamp: Option[Long]) = {
    getMerchant(merchantId) match {
      case Some(merchant) =>
        val sks = merchant.tokenAndKeyPairs.values.filter(_.nonEmpty)
        if (sks.nonEmpty) {
          accountMap += merchantId -> merchant.copy(secretKey = Some(sks.head), updated = timestamp)
        }
      case _ =>
    }
  }

  def setMerchantStatus(status: MerchantStatus, timestamp: Option[Long], merchant: Option[Merchant]) {
    if (merchant.isEmpty) return
    log.info(s">>>>>>>>>>>>>>>> setMerchantStatus($status, $timestamp, $merchant)")
    val newMerchant = merchant.get.copy(updated = timestamp, status = status)
    updateMerchant(newMerchant)
  }

  def billingAccount(invoices: Seq[Invoice]) {
    invoices.filter(_.status == InvoiceStatus.Confirmed).foreach {
      i =>
        if (i.data.fee > 0.0) {
          updateCashAccount(i.merchantId, CashAccount(i.data.currency, new CurrencyWrapper(i.data.price - i.data.fee).internalValue(i.data.currency), 0))
          updateCashAccount(COINPORT_UID, CashAccount(i.data.currency, new CurrencyWrapper(i.data.fee).internalValue(i.data.currency), 0))
        } else {
          updateCashAccount(i.merchantId, CashAccount(i.data.currency, new CurrencyWrapper(i.data.price).internalValue(i.data.currency), 0))
        }
    }
  }

  def canUpdateCashAccount(merchantId: Long, adjustment: CashAccount) = {
    val current = merchantId match {
      case AGGREGATION_UID => Some(aggregationAccount.getOrElse(adjustment.currency, CashAccount(adjustment.currency, 0, 0)))
      case _ => getMerchantCashAccount(merchantId, adjustment.currency)
    }
    current match {
      case Some(c) =>
        (c + adjustment).isValid
      case _ =>
        log.error(s"Can't update not exist merchant $merchantId, ${adjustment.toString}]")
        false
    }
  }

  def updateCashAccount(merchantId: Long, adjustment: CashAccount) = {
    assert(merchantId > 0)
    if (merchantId == COINPORT_UID) {
      updateCoinportAccount(adjustment)
    } else if (merchantId == CRYPTO_UID) {
      updateCryptoAccount(adjustment)
    } else {
      getMerchantCashAccount(merchantId, adjustment.currency) match {
        case Some(current) =>
          val updated = current + adjustment
          assert(updated.isValid)
          setCashAccount(merchantId, updated)
          adjustAggregationAccount(adjustment)
        case _ =>
          log.error(s"Can't update not registered merchant $merchantId, ${adjustment.toString}]")
      }
    }
  }

  def adjustAggregationAccount(adjustment: CashAccount) {
    val updateAccount = aggregationAccount.getOrElse(adjustment.currency, CashAccount(adjustment.currency, 0, 0)) + adjustment
    if (updateAccount.isValid) {
      aggregationAccount += (adjustment.currency -> updateAccount)
    }
  }

  def updateCryptoAccount(adjustment: CashAccount) = {
    if (!accountMap.contains(CRYPTO_UID)) {
      accountMap += CRYPTO_UID -> Merchant(CRYPTO_UID, Some("coinport-minerfee"), settleAccount = Some(Map.empty[Currency, CashAccount]))
    }
    val updated = getMerchantCashAccount(CRYPTO_UID, adjustment.currency).get + adjustment
    setCashAccount(CRYPTO_UID, updated, false)
  }

  def updateCoinportAccount(adjustment: CashAccount) = {
    if (!accountMap.contains(COINPORT_UID)) {
      accountMap += COINPORT_UID -> Merchant(COINPORT_UID, Some("coinport-fee"), settleAccount = Some(Map.empty[Currency, CashAccount]))
    }
    val updated = getMerchantCashAccount(COINPORT_UID, adjustment.currency).get + adjustment
    setCashAccount(COINPORT_UID, updated, false)
  }

  def queryMerchantBalance(merchantId: Long, currencyList: Seq[Currency]): MerchantBalanceResult = {
    getMerchantAccounts(merchantId) match {
      case Some(accounts) =>
        val cashes = currencyList.map { accounts.get(_) }.filter(cs => cs.isDefined)
        val balances = Map(cashes.map(i => (i.get.currency -> new CurrencyWrapper(i.get.available).externalValue(i.get.currency))).toSeq: _*)
        val pendings = Map(cashes.map(i => (i.get.currency -> new CurrencyWrapper(i.get.pendingWithdrawal).externalValue(i.get.currency))).toSeq: _*)
        MerchantBalanceResult(balances, Some(ErrorCode.Ok), Some(pendings))
      case _ => MerchantBalanceResult(Map.empty, Some(ErrorCode.MerchantNotExist), None)
    }
  }

  def transferFundFromPendingWithdrawal(from: Long, to: Long, currency: Currency, amount: Long) = {
    updateCashAccount(from, CashAccount(currency, 0, -amount))
    updateCashAccount(to, CashAccount(currency, amount, 0))
  }

  private def getMerchantCashAccount(merchantId: Long, currency: Currency): Option[CashAccount] =
    getMerchantAccounts(merchantId) match {
      case Some(accounts) => Some(accounts.getOrElse(currency, CashAccount(currency, 0L, 0L)))
      case _ => None
    }

  def getMerchantAccounts(merchantId: Long): Option[collection.Map[Currency, CashAccount]] = {
    if (merchantId == AGGREGATION_UID)
      Some(aggregationAccount)
    else
      accountMap.get(merchantId) match {
        case Some(merchant) =>
          Some(merchant.settleAccount.getOrElse(Map.empty[Currency, CashAccount]))
        case _ =>
          None
      }
  }

  private def setCashAccount(merchantId: Long, cashAccount: CashAccount, checkValid: Boolean = true) = {
    if (checkValid && !cashAccount.isValid)
      throw new IllegalArgumentException("Attempted to set user cash account to an invalid value: " + cashAccount)
    accountMap.get(merchantId) match {
      case Some(merchant) =>
        val accounts = merchant.settleAccount.getOrElse(Map.empty[Currency, CashAccount])
        val updated = merchant.copy(settleAccount = Some(accounts + (cashAccount.currency -> cashAccount)))
        accountMap += merchantId -> updated
      case _ =>
        log.error(s"Can't update not registered merchant $merchantId, ${cashAccount.toString}]")
    }
  }

  private def getMerchantId = lastMerchantId

  private def addMerchant(merchant: Merchant) = {
    idMap += MHash.murmur3(merchant.email.get) -> merchant.id
    accountMap += merchant.id -> merchant
    verificationTokenMap += merchant.verificationToken.get -> merchant.id
  }

  //  private def reAddMerchant(merchant: Merchant) = {
  //    addMerchant(merchant)
  //    merchant.tokenAndKeyPairs.keys foreach { tokenMap += _ -> merchant.id }
  //    merchant.passwordResetToken match {
  //      case Some(resetToken) => passwordResetTokenMap += resetToken -> merchant.id
  //      case _ =>
  //    }
  //  }
  //
  //  private def removeMerchant(merchant: Merchant) = {
  //    idMap -= MHash.murmur3(merchant.email.getOrElse(""))
  //    accountMap -= merchant.id
  //    verificationTokenMap -= merchant.verificationToken.getOrElse("")
  //    merchant.tokenAndKeyPairs.keys.foreach { tokenMap.remove }
  //    passwordResetTokenMap -= merchant.passwordResetToken.getOrElse("")
  //  }

  private def computePassword(id: Long, email: String, password: String) =
    MHash.sha256Base64(email + passwordSecret + MHash.sha256Base64(id + password.trim + passwordSecret))

}
