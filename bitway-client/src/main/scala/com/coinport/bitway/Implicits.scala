package com.coinport.bitway.data

import com.coinport.bitway.common.AgentRole

class RichCashAccount(raw: CashAccount) {
  def total: Double = raw.available + raw.pendingWithdrawal

  def +(another: CashAccount): CashAccount = {
    if (raw.currency != another.currency)
      throw new IllegalArgumentException("Cannot add different currency accounts")
    CashAccount(raw.currency, raw.available + another.available, raw.pendingWithdrawal + another.pendingWithdrawal)
  }

  def -(another: CashAccount): CashAccount = {
    if (raw.currency != another.currency)
      throw new IllegalArgumentException("Cannot minus different currency accounts")
    CashAccount(raw.currency, raw.available - another.available, raw.pendingWithdrawal - another.pendingWithdrawal)
  }

  def isValid = (raw.available >= 0.0 && raw.pendingWithdrawal >= 0.0)
}

class RichRole(v: AgentRole.Value) {
  def <<(exchange: Exchange) = v.toString.toLowerCase + "_" + exchange.toString.toLowerCase
}

object Implicits {
  implicit def cashAccount2Rich(raw: CashAccount) = new RichCashAccount(raw)
  implicit def role2Rich(raw: AgentRole.Value) = new RichRole(raw)
}
