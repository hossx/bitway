package com.coinport.bitway.common

object Role extends Enumeration {
  val account = Value
  val blockchain = Value
  val blockchainview = Value
  val payment = Value
  val iaccess = Value
  val billAccess = Value
  val billWriter = Value
  val post_notifier = Value
  val email_notifier = Value
  val receiver = Value
  val rest = Value
  val wsdelegate = Value
  val tradingProxy = Value
  val quantReceiver = Value
  val merchantAccess = Value
}

object AgentRole extends Enumeration {
  val agent = Value
}

object QuantRole extends Enumeration {
  val trader = Value
  val sender = Value
  val receiver = Value
}
