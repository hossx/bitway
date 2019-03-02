package com.coinport.bitway.actors.notification

import com.coinport.bitway.data._
import com.coinport.bitway.common._
import scala.collection.mutable.Map
import dispatch.Req
import scala.collection.SortedMap
import scala.collection.mutable.SortedSet
import dispatch._
import com.ning.http.client.Response
import java.net.ConnectException
import akka.util.Timeout
import scala.concurrent.ExecutionContext.Implicits.global
import org.slf4s.Logging

class PostNotificationDataManager extends Manager[TNotificationState] with Logging {

  val delays: Array[Long] = Array(60L, 240L, 540L, 960L, 1500L)

  implicit val ordering = new Ordering[DelayedPost] {
    def compare(a: DelayedPost, b: DelayedPost) = { (a.timestamp - b.timestamp).toInt }
  }
  var waitForResendSet = SortedSet.empty[DelayedPost]
  val resendMap = Map.empty[Long, InvoiceStatus]

  var lastId = 1E9.toLong

  def getSnapshot = TNotificationState(getFiltersSnapshot, waitForResendSet.toList)

  def loadSnapshot(snapshot: TNotificationState) = {
    loadFiltersSnapshot(snapshot.filters)
    waitForResendSet = SortedSet.empty[DelayedPost] ++ snapshot.posts
  }

  private def getLastId = lastId

  def AddResendNotify(req: InvoiceSummary, url: String, timestamp: Long) {
    waitForResendSet += DelayedPost(timestamp + delays(0), url, req, 5, 1)
  }

  def updateResendSortWhenSuccessed(delayedPost: DelayedPost) = waitForResendSet.remove(delayedPost)

  def updateResendSortWhenFailed(delayedPost: DelayedPost, ts: Long) {
    waitForResendSet.remove(delayedPost)
    if (delayedPost.tried < delayedPost.maxTry) {
      waitForResendSet += delayedPost.copy(nextSendTime(ts, delayedPost.tried), tried = (delayedPost.tried + 1))
    } else {
      log.info(delayedPost.toString)
    }
  }

  def nextSendTime(currentTime: Long, tried: Int): Long = {
    currentTime + delays(tried)
  }
}
