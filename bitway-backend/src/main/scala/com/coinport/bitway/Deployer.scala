/**
 * Copyright (C) 2014 Coinport Inc. <http://www.coinport.com>
 *
 */

package com.coinport.bitway

import akka.actor._
import akka.cluster.Cluster
import akka.contrib.pattern.ClusterSingletonManager

import com.coinport.bitway.actors.bill._
import com.coinport.bitway.actors.payment._
import com.coinport.bitway.common.stackable._
import com.coinport.bitway.data._
import com.mongodb.casbah.{ MongoConnection, MongoURI }
import com.twitter.util.Eval
import com.typesafe.config.Config

import java.io.InputStream
import org.slf4s.Logging
import org.apache.commons.io.IOUtils
import scala.collection.mutable.ListBuffer

import actors.account.{ AccountActor, AccountDataManager, MerchantAccessActor }
import actors.crypto_network._
import actors.invoice._
import actors.notification._
import actors.trader._
import common.Role
import common.AgentRole
import Currency._
import Exchange._
import Implicits._
// import actors._

class Deployer(config: Config, host: String)(implicit cluster: Cluster) extends Object with Logging {
  implicit val system = cluster.system
  val currency = Btc
  val paths = new ListBuffer[String]

  def deploy(exchanges: Seq[Exchange]): LocalRouters = {

    println("======" + cluster.selfRoles)
    // Then deploy routers
    val routers = new LocalRouters(exchanges)

    val mongoUriForViews = MongoURI(config.getString("akka.invoice.mongo-uri-for-readers"))
    val mongoForViews = MongoConnection(mongoUriForViews)
    val dbForViews = mongoForViews(mongoUriForViews.database.get)
    val mandrilApiKey = config.getString("akka.mailer.mandrill-api-key")
    val invoiceUrlBase = config.getString("akka.mailer.invoice-url-base")

    // Finally deploy processors
    val configs = loadConfig[BlockchainConfigs](config.getString("akka.blockchain.config-path")).configs
    deploySingleton(Props(new AccountActor(routers, "", new MandrillMailHandler(mandrilApiKey)) with StackableEventsourced[TAccountState, AccountDataManager]), Role.account)
    deploySingleton(Props(new BlockchainActor(routers, currency, configs.getOrElse(currency, BlockchainConfig())) with StackableEventsourced[TBlockchainState, BlockchainDataManager]), Role.blockchain)
    deploySingleton(Props(new InvoiceAccessActor(routers, dbForViews)), Role.iaccess)
    deploySingleton(Props(new PaymentActor(routers, dbForViews, new MandrillMailHandler(mandrilApiKey)) with StackableEventsourced[TInvoiceState, PaymentManager]), Role.payment)
    deploySingleton(Props(new BillActor(routers, dbForViews)), Role.billAccess)
    deploySingleton(Props(new BillWriter(dbForViews) with StackableView[TBillWriterState, BillWriterManager]), Role.billWriter)
    deploySingleton(Props(new BlockchainReceiver(routers, currency, configs.getOrElse(currency, BlockchainConfig()))), Role.receiver)
    deploySingleton(Props(new PostNotificationActor(routers) with StackableEventsourced[TNotificationState, PostNotificationDataManager]), Role.post_notifier)
    deploySingleton(Props(new MerchantAccessActor(dbForViews) with StackableView[TAccountState, AccountDataManager]), Role.merchantAccess)

    val traderConfig = loadConfig[TraderConfig](config.getString("akka.trader.config-path"))
    deploySingleton(Props(new QuantReceiver(routers, traderConfig)), Role.quantReceiver)
    deploySingleton(Props(new TradingProxy(routers, traderConfig)), Role.tradingProxy)

    deploy(Props(new EmailNotificationActor(invoiceUrlBase, new MandrillMailHandler(mandrilApiKey))), Role.email_notifier)
    deploy(Props(new BlockchainView(currency, configs.getOrElse(currency, BlockchainConfig())) with StackableView[TBlockchainState, BlockchainDataManager]), Role.blockchainview)

    log.info("Deployed the following actors:\n\t" + paths.mkString("\n\t"))
    routers
  }

  private def deploySingleton(props: => Props, name: String) {
    if (cluster.selfRoles.contains(name)) {
      val actor = system.actorOf(ClusterSingletonManager.props(
        singletonProps = props,
        singletonName = "singleton",
        terminationMessage = PoisonPill,
        role = Some(name)),
        name = name)
      val path = actor.path.toStringWithoutAddress + "/singleton"
      log.info(s"------- deployed actor at: ${actor.path}")
      paths += path
    }
  }

  private def deploySingleton(props: => Props, role: Role.Value) {
    deploySingleton(props, role.toString)
  }

  private def deploy(props: => Props, role: Role.Value): Option[ActorRef] = deploy(props, role.toString)

  private def deploy(props: => Props, name: String): Option[ActorRef] = {
    if (!cluster.selfRoles.contains(name)) None else {
      val actor = system.actorOf(props, name)
      log.info(s"------- deployed actor at: ${actor}")
      paths += actor.path.toStringWithoutAddress
      Some(actor)
    }
  }

  private def loadConfig[T](configPath: String): T = {
    val in: InputStream = this.getClass.getClassLoader.getResourceAsStream(configPath)
    (new Eval()(IOUtils.toString(in))).asInstanceOf[T]
  }
}
