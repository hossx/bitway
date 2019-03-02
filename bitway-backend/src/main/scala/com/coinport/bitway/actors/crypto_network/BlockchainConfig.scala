/**
 * Copyright 2014 Coinport Inc. All Rights Reserved.
 * Author: c@coinport.com (Chao Ma)
 */

package com.coinport.bitway.actors.crypto_network

import com.coinport.bitway.data.Currency

final case class BlockchainConfig(
  ip: String = "bitway",
  port: Int = 6379,
  batchFetchAddressNum: Int = 100,
  requestChannelPrefix: String = "creq_",
  responseChannelPrefix: String = "cres_",
  maintainedChainLength: Int = 20,
  createInvoiceTimesLimit: Int = 500)

final case class BlockchainConfigs(
  configs: Map[Currency, BlockchainConfig] = Map.empty)
