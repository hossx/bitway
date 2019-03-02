/**
 * Copyright (C) 2014 Coinport Inc. <http://www.coinport.com>
 *
 */

package com.coinport.bitway

import akka.actor._
import akka.cluster.routing._
import akka.routing._
import akka.contrib.pattern.ClusterSingletonProxy
import akka.cluster.Cluster
import org.slf4s.Logging

import com.coinport.bitway.common.Role
import com.coinport.bitway.common.AgentRole
import com.coinport.bitway.data.Exchange
import com.coinport.bitway.data.Implicits._

class LocalRouters(exchanges: Seq[Exchange])(implicit cluster: Cluster) extends Object with Logging {
  implicit val system = cluster.system

  lazy val accountActor = routerForSingleton(Role.account)
  lazy val blockchainActor = routerForSingleton(Role.blockchain)
  lazy val paymentActor = routerForSingleton(Role.payment)
  lazy val invoiceAccessActor = routerForSingleton(Role.iaccess)
  lazy val billActor = routerForSingleton(Role.billAccess)
  lazy val postNotificationActor = routerForSingleton(Role.post_notifier)
  lazy val emailNotificationActor = routerFor(Role.email_notifier)
  lazy val tradingProxy = routerForSingleton(Role.tradingProxy)
  lazy val merchantAccessActor = routerForSingleton(Role.merchantAccess)

  lazy val wsDelegateActors = broadcastRouterFor(Role.wsdelegate)

  lazy val blockchainView = routerFor(Role.blockchainview)

  private def routerForSingleton(role: Role.Value): ActorRef = routerForSingleton(role.toString)

  private def routerForSingleton(name: String): ActorRef = {
    system.actorOf(
      ClusterSingletonProxy.defaultProps("/user/" + name + "/singleton", name),
      name + "_router")
  }

  private def routerFor(role: Role.Value) = {
    val name = role.toString
    system.actorOf(
      ClusterRouterGroup(RoundRobinGroup(Nil),
        ClusterRouterGroupSettings(
          totalInstances = Int.MaxValue,
          routeesPaths = List("/user/" + name),
          allowLocalRoutees = cluster.selfRoles.contains(name),
          useRole = Some(name))).props,
      name + "_router")
  }

  private def broadcastRouterFor(role: Role.Value) = {
    val name = role.toString
    system.actorOf(
      ClusterRouterGroup(BroadcastGroup(Nil),
        ClusterRouterGroupSettings(
          totalInstances = Int.MaxValue,
          routeesPaths = List("/user/" + name),
          allowLocalRoutees = false,
          useRole = None)).props,
      name + "_router")
  }
}
