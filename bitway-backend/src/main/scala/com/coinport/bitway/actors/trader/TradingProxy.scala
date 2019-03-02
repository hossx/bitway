/**
 * Copyright 2014 Coinport Inc. All Rights Reserved.
 * Author: c@coinport.com (Chao Ma)
 */

package com.coinport.bitway.actors.trader

import akka.actor._
import akka.event.LoggingReceive
import akka.persistence._
import java.util.UUID
import scala.concurrent.duration._
import com.coinport.bitway.LocalRouters
import com.coinport.bitway.api.model.CurrencyWrapper
import com.coinport.bitway.common._
import com.coinport.bitway.data._
import com.coinport.bitway.serializers._
import com.redis._
import com.redis.serialization.Parse.Implicits.parseByteArray
import Constants._
import scala.math.BigDecimal
import com.coinport.bitway.util.Util

class TradingProxy(routers: LocalRouters, config: TraderConfig) extends Actor with ActorLogging {
  implicit def executionContext = context.dispatcher
  val serializer = new ThriftBinarySerializer()
  val client: Option[RedisClient] = try {
    Some(new RedisClient(config.ip, config.port))
  } catch {
    case ex: Throwable => None
  }

  private def reportAssetInterval = 60 second

  override def preStart {
    super.preStart
    scheduleReportAsset
  }

  def receive = LoggingReceive {
    case e @ QueryAssetResult(currency, assets, lockForWithdrawal) if (client.isDefined) =>
      client.get.rpush(PAYMENT_CHANNEL, serializer.toBinary(PaymentMessage(assets = Some(e))))

    case p @ ConfirmablePersistent(m: NotifyMerchants, _, _) =>
      p.confirm
      if (client.isDefined) {
        m.invoiceNotificaitons foreach {
          case InvoiceNotification(_, invoice) if (invoice.status == InvoiceStatus.Confirmed) =>
            if (invoice.data.currency == Currency.Cny ||
              invoice.data.currency == Currency.Usd) {
              val price = Util.roundDouble((BigDecimal(invoice.data.price) / BigDecimal(invoice.btcPrice)).toDouble, 2)
              client.get.rpush(PAYMENT_CHANNEL, serializer.toBinary(PaymentMessage(action = Some(TradeAction(
                id = invoice.id,
                pair = TradePair(Currency.Btc, invoice.data.currency),
                amount = invoice.btcPrice,
                price = price,
                status = TradeActionStatus.Processing,
                executedAmount = Some(invoice.btcPrice),
                avgExecutedPrice = Some(price)
              )))))
            }

          case _ =>
            log.info("needn't sell btc in invoice which not paid or has been concerned")
        }
      }

    case p @ ConfirmablePersistent(MerchantRefund(t: AccountTransfer), _, _) =>
      p.confirm()
      if (client.isDefined) {
        val refundAmountNoFee = t.fee match {
          case Some(f) if f.amount > 0 => t.refundAmount.get - new CurrencyWrapper(f.amount).externalValue(f.currency)
          case _ => t.refundAmount.get
        }
        client.get.rpush(PAYMENT_CHANNEL, serializer.toBinary(PaymentMessage(action = Some(TradeAction(
          id = UUID.randomUUID().toString(),
          pair = TradePair(Currency.Btc, t.refundCurrency.get),
          amount = t.externalAmount.get,
          price = (refundAmountNoFee / t.externalAmount.get),
          status = TradeActionStatus.Processing
        )))))
      }

    case p @ ConfirmablePersistent(DoPaymentRequest(t: AccountTransfer), _, _) =>
      if (client.isDefined) {
        val paymentAmountNoFee = t.fee match {
          case Some(f) if f.amount > 0 => t.paymentData.get.externalAmount.get - new CurrencyWrapper(f.amount).externalValue(f.currency)
          case _ => t.paymentData.get.externalAmount.get
        }
        client.get.rpush(PAYMENT_CHANNEL, serializer.toBinary(PaymentMessage(action = Some(TradeAction(
          id = UUID.randomUUID().toString(),
          pair = TradePair(Currency.Btc, t.paymentData.get.currency.get),
          amount = t.externalAmount.get,
          price = (paymentAmountNoFee / t.externalAmount.get),
          status = TradeActionStatus.Processing
        )))))
      }

    case event: AnyRef =>
      log.error(s"TradingProxy received unrecognized message ${event.toString}")
  }

  private def scheduleReportAsset {
    context.system.scheduler.schedule(1 second, reportAssetInterval) {
      routers.blockchainView ! QueryAsset(Currency.Btc)
    }
  }
}

class QuantReceiver(routers: LocalRouters, config: TraderConfig) extends Actor with ActorLogging {
  implicit val executeContext = context.system.dispatcher

  val serializer = new ThriftBinarySerializer()
  val client: Option[RedisClient] = try {
    Some(new RedisClient(config.ip, config.port))
  } catch {
    case ex: Throwable => None
  }

  override def preStart = {
    super.preStart
    listenAtRedis(10)
  }

  def receive = LoggingReceive {
    case e: ListenAtRedis =>
      if (client.isDefined) {
        client.get.lpop[Array[Byte]](QUANT_CHANNEL) match {
          case Some(s) =>
            val response = serializer.fromBinary(s, classOf[QuantMessage.Immutable]).asInstanceOf[QuantMessage]
            if (response.rates.isDefined)
              routers.paymentActor ! response.rates.get
            else if (response.sendCoin.isDefined)
              routers.paymentActor ! response.sendCoin.get
            listenAtRedis()
          case None =>
            listenAtRedis(5)
        }
      }
  }

  private def listenAtRedis(timeout: Long = 0) {
    context.system.scheduler.scheduleOnce(timeout seconds, self, ListenAtRedis(0))(context.system.dispatcher)
  }
}
