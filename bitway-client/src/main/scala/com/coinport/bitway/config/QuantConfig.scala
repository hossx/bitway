/**
 * Copyright 2014 Coinport Inc. All Rights Reserved.
 * Author: c@coinport.com (Chao Ma)
 */

package com.coinport.bitway.config

final case class QuantConfig(
  var ip: String = "localhost",
  var port: Int = 6379,
  var paymentQuantId: Long = 10000001L)
