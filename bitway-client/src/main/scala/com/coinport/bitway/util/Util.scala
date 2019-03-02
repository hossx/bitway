/**
 * Copyright 2014 Coinport Inc. All Rights Reserved.
 * Author: c@coinport.com (Chao Ma)
 */

package com.coinport.bitway.util

import com.ning.http.client.Response
import dispatch._
import java.net.ConnectException
import javax.crypto.spec.SecretKeySpec
import javax.crypto.Mac
import org.slf4s.Logging
import scala.collection.mutable.SortedSet
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.parsing.json.JSON.parseFull
import com.coinport.bitway.data.DepthItem
import java.security.MessageDigest

object Util extends Logging {

  val HMAC_SHA1_ALGORITHM = "HmacSHA1"
  val HMAC_SHA256_ALGORITHM = "HmacSHA256"
  val HMAC_SHA512_ALGORITHM = "HmacSHA512"

  val httpPool = Http.configure { builder =>
    builder.setAllowPoolingConnection(true)
    builder.setRequestTimeoutInMs(5000)
    builder.setConnectionTimeoutInMs(5000)
    builder
  }

  def md5(s: String) = {
    val md: MessageDigest = MessageDigest.getInstance("MD5")
    md.update(s.getBytes("UTF-8"))
    val digestBytes = md.digest()
    digestBytes.map { b =>
      if (Integer.toHexString(0xFF & b).length() == 1)
        "0" + Integer.toHexString(0xFF & b)
      else
        Integer.toHexString(0xFF & b)
    }.mkString
  }

  def combineParams(params: Map[String, String]): String = {
    val sortedParams = SortedSet.empty[String] ++ params.keySet
    sortedParams.map(item => item + "=" + params(item)).mkString("&")
  }

  def toDepthItemList(list: List[List[Any]]): List[DepthItem] = {
    var depthList: List[DepthItem] = List()
    list.map { f =>
      val a = f(0) match {
        case i: String => i.toDouble
        case i: Double => i
        case _ => 0.0
      }
      val b = f(1) match {
        case i: String => i.toDouble
        case i: Double => i
        case _ => 0.0
      }
      depthList = DepthItem(a, 0.0, b) :: depthList
    }
    depthList
  }

  def sendRequest(req: Req): Option[Any] = {
    httpPool(req).either() match {
      case Right(res) => {
        parseFull(res.getResponseBody())
      }
      case Left(e: ConnectException) => {
        log.error("Can't connect!" + e.getMessage)
        None
      }
      case Left(StatusCode(code)) => {
        log.error("Some other code: " + code.toString)
        None
      }
      case Left(e) => {
        log.error("Something else: " + e.getMessage)
        None
      }
    }
  }

  def sendRequestAndGetString(req: Req): Option[String] = {
    httpPool(req).either() match {
      case Right(res) => {
        Some(res.getResponseBody())
      }
      case Left(e: ConnectException) => {
        log.error("Can't connect!" + e.getMessage)
        None
      }
      case Left(StatusCode(code)) => {
        log.error("Some other code: " + code.toString)
        None
      }
      case Left(e) => {
        log.error("Something else: " + e.getMessage)
        None
      }
    }
  }

  private def byteArrayToHex(buf: Array[Byte]): String = {
    buf.map("%02X" format _).mkString
  }

  def getSignature(data: String, secretKey: String): String = getSignature(HMAC_SHA1_ALGORITHM, data, secretKey)

  def getSignature(algorithm: String, data: String, secretKey: String): String = {
    val signingKey = new SecretKeySpec(secretKey.getBytes(), algorithm);
    val mac = Mac.getInstance(algorithm)
    mac.init(signingKey)
    val rawHmac = mac.doFinal(data.getBytes())
    byteArrayToHex(rawHmac)
  }

  def roundDouble(src: Double, roundNum: Int): Double = {
    BigDecimal(src).setScale(roundNum, BigDecimal.RoundingMode.HALF_UP).toDouble
  }
}
