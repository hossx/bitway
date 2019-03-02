/**
 * Copyright 2014 Coinport Inc. All Rights Reserved.
 * Author: c@coinport.com (Chao Ma)
 */

package com.coinport.bitway.actors.crypto_network

import akka.event.{ LoggingAdapter, LoggingReceive }
import akka.persistence.Persistent

import com.coinport.bitway.common.ExtendedView
import com.coinport.bitway.data._
import akka.actor.ActorLogging

class BlockchainView(supportedCurrency: Currency, bcconfig: BlockchainConfig)
    extends ExtendedView with BlockchainDataManagerBehavior with ActorLogging {
  override val processorId = "blockchain_btc"
  override val viewId = "blockchain_v_btc"
  lazy implicit val logger: LoggingAdapter = log

  val manager = new BlockchainDataManager(supportedCurrency, bcconfig.maintainedChainLength)

  def receive = LoggingReceive {
    case Persistent(msg, _) => updateState(msg)
    case QueryAsset(currency) => sender ! QueryAssetResult(currency, manager.getAsset, Some(manager.getLocked))
  }
}
