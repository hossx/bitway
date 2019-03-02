package com.coinport.bitway.common.stackable

import akka.persistence.Processor
import akka.persistence.SnapshotOffer
import com.coinport.bitway.data._
import akka.actor.ActorLogging
import com.coinport.bitway.common.Manager
import com.coinport.bitway.common.support._

trait StackableCmdsourced[T <: AnyRef, M <: Manager[T]]
    extends Processor with ActorLogging with SnapshotSupport with RecoverySupport with RedeliverFilterSupport[T, M] {
  val manager: M

  abstract override def receive = filterFor(super.receive) orElse super.receive orElse {
    case cmd: TakeSnapshotNow => takeSnapshot(cmd)(saveSnapshot(manager.getSnapshot))

    case SnapshotOffer(meta, snapshot) =>
      log.info("Loading snapshot: " + meta)
      manager.loadSnapshot(snapshot.asInstanceOf[T])

    case QueryRecoverStats => execAfterRecover(recoveryFinished)

    case DumpStateToFile(_) => sender ! dumpToFile(manager.getSnapshot, self.path)
  }
}
