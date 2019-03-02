/**
 * Copyright 2014 Coinport Inc. All Rights Reserved.
 * Author: c@coinport.com (Chao Ma)
 */

package com.coinport.bitway.common.support

import akka.actor._
import akka.persistence.ConfirmablePersistent
import com.coinport.bitway.common.Manager

trait RedeliverFilterSupport[T <: AnyRef, M <: Manager[T]] extends Actor with ActorLogging with ChannelSupport {
  val manager: M
  def identifyChannel: PartialFunction[Any, String]

  def filterFor(target: Receive): Receive = {
    case p @ ConfirmablePersistent(payload, seq, _) if target.isDefinedAt(p) =>
      val channel = if (identifyChannel.isDefinedAt(payload)) identifyChannel(payload) else "default"
      if (manager.hasProcessed(channel, seq)) {
        p.confirm()
        log.warning("ConfirmablePersistent request was previously processed: " + p)
      } else {
        target(p)
      }
  }

  abstract override def confirm(p: ConfirmablePersistent) {
    super.confirm(p)
    val channel = if (identifyChannel.isDefinedAt(p.payload)) identifyChannel(p.payload) else "default"
    manager.rememberProcessedId(channel, p.sequenceNr)
  }
}
