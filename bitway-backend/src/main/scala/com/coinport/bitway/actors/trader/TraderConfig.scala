/**
 * Copyright 2014 Coinport Inc. All Rights Reserved.
 * Author: c@coinport.com (Chao Ma)
 */

package com.coinport.bitway.actors.trader

final case class TraderConfig(
  var ip: String = "localhost",
  var port: Int = 6379)
