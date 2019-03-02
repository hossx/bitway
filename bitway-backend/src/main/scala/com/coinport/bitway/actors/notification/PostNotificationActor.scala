package com.coinport.bitway.actors.notification

import scala.concurrent.duration.DurationInt
import com.coinport.bitway.LocalRouters
import com.coinport.bitway.common._
import com.coinport.bitway.data._
import scala.concurrent.ExecutionContext.Implicits.global
import akka.actor._
import akka.event.LoggingReceive
import akka.persistence._
import dispatch._
import com.ning.http.client.Response
import java.net.ConnectException
import akka.util.Timeout
import akka.pattern.ask
import com.coinport.bitway.serializers.ThriftJsonSerializer

/**
 * This actor should be a persistent actor.
 *
 * Responsible for:
 * - should be shared for parallel* ping back
 * - need to schedule ping back if failed
 * - support ping back strategy
 *
 *
 * Notify merchants using the given url and data multiple times until the send is either successful
 * or the BitPay server gives up. As of this writing the BitPay server attempts retries on the following schedule.
 * - 0:00 ­ 1 minute delay
 * - 1:00 ­ 4 minute delay
 * - 5:00 ­ 9 minute delay
 * - 14:00 ­ 16 minute delay
 * - 30:00 ­ 25 minute delay
 * - 55:00 ­ failed
 */

class PostNotificationActor(routers: LocalRouters) extends ExtendedProcessor with EventsourcedProcessor with ActorLogging {
  override def processorId = "notifier"

  val manager = new PostNotificationDataManager()
  val serializer = new ThriftJsonSerializer

  implicit val timeout = Timeout(10 seconds)

  override def preStart = {
    super.preStart
    scheduleResendNotify
  }

  def receiveRecover = PartialFunction.empty[Any, Unit]

  def receiveCommand = LoggingReceive {

    case p @ ConfirmablePersistent(m: NotifyMerchants, _, _) =>
      log.info("received notification >>>>>>> " + m)
      confirm(p)
      updateState(m)
    case m: NotifyMerchants => updateState(m)
    case e: ResendNotify => persist(e)(updateState)
    case e: ResendSuccessed => persist(e)(updateState)
    case e: ResendFailed => persist(e)(updateState)
  }

  def updateState: Receive = {
    case e: NotifyMerchants => notify(e)
    case e: ResendNotify => manager.AddResendNotify(buildSummery(e.invoice), e.invoice.data.notificationURL.get, e.timestamp)
    case e: ResendSuccessed => manager.updateResendSortWhenSuccessed(e.post)
    case e: ResendFailed => manager.updateResendSortWhenFailed(e.post, e.timestamp)
  }

  private def notify(e: NotifyMerchants) {
    e.invoiceNotificaitons map {
      case n =>
        if ((n.invoice.data.fullNotifications || n.invoice.status == InvoiceStatus.Confirmed || n.invoice.status == InvoiceStatus.Refunded)
          && validateUrl(n.invoice.data.notificationURL)) {
          val requestWithParams = buildRequest(n.invoice)
          if (!recoveryRunning) {
            // tryNotify(maxTry, requestWithParams, id)
            val startTime = currentTimeStr
            val res = sendRequest(requestWithParams)
            if (!res._1) {
              self ! ResendNotify(n.invoice, System.currentTimeMillis / 1000)
              log.warning(res._2)
            } else {
              log.info(n.invoice.toString)
            }
            NotificationMongoHandler.addItem(buildSummery(n.invoice), n.invoice.data.notificationURL.get, if (res._1) "success" else "failed", 0, s"$startTime - $currentTimeStr", res._2)
          }
        } else {
          log.info("dropped notify : " + n.toString)
        }
    }
  }

  private def buildRequest(invoice: Invoice): Req = {
    val request = url(invoice.data.notificationURL.get).POST
    val requestAsJson = request.setContentType("application/json", "UTF-8")
    requestAsJson << new String(serializer.toBinary(convertToInfo(buildSummery(invoice))), "UTF-8")
  }

  private def invoiceSummaryToReq(summary: InvoiceSummary, reqUrl: String): Req = {
    val request = url(reqUrl).POST
    val requestAsJson = request.setContentType("application/json", "UTF-8")
    requestAsJson << new String(serializer.toBinary(convertToInfo(summary)), "UTF-8")
  }

  private def buildSummery(invoice: Invoice): InvoiceSummary = {
    val data = invoice.refundAccAmount match {
      case Some(rf) => Some(NotificationData(invoice.refundAccAmount))
      case _ => None
    }
    InvoiceSummary(
      invoice.id,
      invoice.data.price,
      invoice.data.currency,
      invoice.btcPrice,
      invoice.status,
      invoice.invoiceTime,
      invoice.expirationTime,
      invoice.data.posData,
      Some(System.currentTimeMillis),
      invoice.data.orderId,
      data)
  }

  private def convertToInfo(summary: InvoiceSummary): InvoiceNotificationInfo = {
    InvoiceNotificationInfo(
      summary.id,
      summary.price,
      summary.currency.name.toUpperCase(),
      summary.btcPrice,
      summary.invoiceTime,
      summary.expirationTime,
      summary.posData.getOrElse(""),
      System.currentTimeMillis(),
      summary.status.name.toUpperCase(),
      summary.orderId.getOrElse(""),
      summary.data
    )
  }

  def sendRequest(req: Req): (Boolean, String) = {
    try {
      val res: Either[Throwable, Response] = Http(req).either()
      res match {
        case Right(res) => {
          (res.getStatusCode == 200, responseStr(res))
        }
        case Left(_: ConnectException) => {
          log.error("Can't connect!")
          (false, s"Can't connect! ${req.url}")
        }
        case Left(StatusCode(code)) => {
          log.error("Some other code: " + code.toString)
          (false, code.toString)
        }
        case Left(e) => {
          log.error("Something else: " + e.getMessage)
          (false, e.getMessage)
        }
      }
    } catch {
      case e: Throwable =>
        log.error(s"Caught exception when send notify : ${req.url}, ${e.getStackTraceString}")
        (true, "")
    }
  }

  //TODO(xiaolu) check url is normal with regex
  private def validateUrl(url: Option[String]): Boolean = {
    if (url.isEmpty) false
    else if (!url.get.startsWith("https") && !url.get.startsWith("http")) false
    else true
  }

  private def scheduleResendNotify {

    // TODO(xiaolu) handle one http request at once now ---> handle multi requests at once
    context.system.scheduler.schedule(1 second, 2 second) {
      if (!recoveryRunning) {
        val ts = System.currentTimeMillis / 1000
        if (manager.waitForResendSet.headOption.isDefined && manager.waitForResendSet.head.timestamp <= ts) {
          val post = manager.waitForResendSet.head
          val tryStartTime = currentTimeStr
          try {
            val res: Either[Throwable, Response] = Http(invoiceSummaryToReq(post.summary, post.url)).either()
            res match {
              case Right(res) if res.getStatusCode == 200 => {
                self ? ResendSuccessed(post, ts) map {
                  case m => {
                    log.info(post.summary.toString)
                  }
                }
                NotificationMongoHandler.updateItem(post.summary.id, "success", post.tried, s"$tryStartTime - $currentTimeStr", responseStr(res))
              }
              case Right(res) =>
                handleFail(post, ts, tryStartTime, responseStr(res))
              case Left(_: ConnectException) =>
                handleFail(post, ts, tryStartTime, s"Can't connect ${post.url}")
              case Left(StatusCode(code)) =>
                handleFail(post, ts, tryStartTime, code.toString)
              case Left(e) =>
                handleFail(post, ts, tryStartTime, e.getStackTraceString)
            }
          } catch {
            case e: Throwable =>
              log.error(s"Caught exception when resend notify : ${post.url}, ${e.getStackTraceString}")
          }
        }
      }
    }
  }
  private def handleFail(post: DelayedPost, timeStamp: Long, resTime: String, resInfo: String) {
    self ! ResendFailed(post, timeStamp)
    NotificationMongoHandler.updateItem(post.summary.id, "failed", post.tried, s"$resTime - $currentTimeStr", resInfo)
  }

  private def currentTimeStr = {
    val format = new java.text.SimpleDateFormat("yyyy-MM-dd hh:mm:ss")
    format.format(new java.util.Date(System.currentTimeMillis()))
  }

  private def responseStr(res: Response) = s"${res.getStatusCode} ${res.getResponseBody}"

}
