package com.coinport.bitway

import akka.actor._
import akka.cluster.Cluster
import akka.pattern.ask
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import java.net.InetAddress

import com.coinport.bitway.common.AgentRole
import com.coinport.bitway.common.Role
import com.coinport.bitway.data.Exchange._
import com.coinport.bitway.data.Implicits._

object Main {
  def main(args: Array[String]): Unit = {

    val exchanges = List(OkcoinCn, OkcoinEn, Btcchina, Huobi, Bitstamp)
    val allRoles = Role.values.map(_.toString) ++ AgentRole.values.flatMap(a => exchanges.map { e => a << e })

    if (args.length < 2 || args.length > 4) {
      val message = """please supply 1 to 4 parameters:
        required args(0): port - supply 0 to select a port randomly
        required args(1): seeds - seed note seperated by comma, i.e, "127.0.0.1:25551,127.0.0.1:25552"
        optioanl args(2): roles - "*" for all roles, "" for empty node, and "a,b,c" for 3 roles
        optioanl args(3): hostname - self hostname

        available roles:%s
      """.format(allRoles.mkString("\n\t\t", "\n\t\t", "\n\t\t"))
      println(message)
      System.exit(1)
    }

    val seeds = args(1).split(",").map(_.stripMargin).filter(_.nonEmpty).map("\"akka.tcp://bitway@" + _ + "\"").mkString(",")

    val roles =
      if (args.length < 3) ""
      else if (args(2) == "*" || args(2) == "all") allRoles.mkString(",")
      else args(2).split(",").map(_.stripMargin).filter(_.nonEmpty).map("\"" + _ + "\"").mkString(",")

    val hostname =
      if (args.length < 4) InetAddress.getLocalHost.getHostAddress
      else args(3)

    var config = ConfigFactory.parseString("akka.remote.netty.tcp.port=" + args(0))
      .withFallback(ConfigFactory.parseString("akka.remote.netty.tcp.hostname=" + hostname))
      .withFallback(ConfigFactory.parseString("akka.cluster.roles=[" + roles + "]"))
      .withFallback(ConfigFactory.parseString("akka.cluster.seed-nodes=[" + seeds + "]"))
      .withFallback(ConfigFactory.load())

    println("====")
    println(roles)
    println(seeds)
    println(hostname)

    implicit val system = ActorSystem("bitway", config)
    implicit val cluster = Cluster(system)

    val routers = new Deployer(config, hostname).deploy(exchanges)

    Thread.sleep(5000)

    val logo = "\n" +
      " _    _ _                     \n" +
      "| |__(_) |___ __ ____ _ _  _  \n" +
      "| '_ \\ |  _\\ V  V / _` | || | \n" +
      "|_.__/_|\\__|\\_/\\_/\\__,_|\\_, | \n" +
      "                        |__/  \n";

    println(logo)

    system.registerOnTermination {
      system.log.info("Bitway shutdown.")
    }
  }
}
