/**
 * Copyright 2014 Coinport Inc. All Rights Reserved.
 * Author: c@coinport.com (Chao Ma)
 */

import com.coinport.bitway.common._
import com.coinport.bitway.data._
import com.coinport.bitway.data.Currency._
import com.coinport.bitway.actors.crypto_network._

BlockchainConfigs(Map(
  Btc -> BlockchainConfig(
    ip = "bitway",
    port = 6379,
    batchFetchAddressNum = 100,
    maintainedChainLength = 100
  ),
  Doge -> BlockchainConfig(
    ip = "bitway",
    port = 6379,
    batchFetchAddressNum = 100,
    maintainedChainLength = 200
  )
))
