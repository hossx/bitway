package com.coinport.bitway.common

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorSelection
import com.coinport.bitway.data._

trait ExtendedActor extends Actor with ActorLogging {

  val config = context.system.settings.config
  val mail = context.actorSelection("/user/mailer")

  override def preRestart(reason: Throwable, message: Option[Any]) = {
    val content = "[ERROR] ACTOR RESTART >>>> [PATH] " + self.path.toString +
      "\n[ERROR] ACTOR RESTART >>>> [REASON] " + reason.getMessage
    "\n[ERROR] ACTOR RESTART >>>> [MESSAGE] " + message.getOrElse("Not specify message").toString
    log.error(content)

    super.preRestart(reason, message)
  }

}
