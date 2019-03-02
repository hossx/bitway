/**
 * Copyright 2014 Coinport Inc. All Rights Reserved.
 * Author: c@coinport.com (Chao Ma)
 */

package com.coinport.bitway.actors.invoice

import org.slf4s.Logging

import com.coinport.bitway.data._

class InvoiceFSM extends Logging {
  import InvoiceStatus._
  import TransferType._

  type Handler = (AnyRef, Invoice) => (Option[Invoice], Option[InvoiceUpdate], Option[InvoiceNotification])

  var handlers = Map.empty[InvoiceStatus, Handler]
  handlers += New -> stateNewHandler
  handlers += Paid -> statePaidHandler
  handlers += Confirmed -> stateConfirmedHandler
  handlers += ReorgingConfirmed -> statePaidHandler

  def action(event: AnyRef, invoice: Invoice): (Option[Invoice], Option[InvoiceUpdate], Option[InvoiceNotification]) = {
    getHandler(invoice.status)(event, invoice)
  }

  private def defaultHandler(event: AnyRef, invoice: Invoice): (Option[Invoice], Option[InvoiceUpdate], Option[InvoiceNotification]) = {
    log.warn("can't handle the status of invoice: " + invoice)
    (None, None, None)
  }

  private def getHandler(status: InvoiceStatus): Handler = handlers.getOrElse(status, defaultHandler)

  private def stateNewHandler(event: AnyRef, invoice: Invoice): (Option[Invoice], Option[InvoiceUpdate], Option[InvoiceNotification]) = event match {
    case e: BitcoinTxSeen if (e.newTx.txType == Some(Deposit)) =>
      val amount = findAmount(invoice.paymentAddress, e.newTx.outputs)
      if (amount.isDefined) {
        handleTxInNew(amount.get, e.newTx.txid, None, e.newTx.timestamp, 0, invoice, isSufficientFee(e.newTx.minerFee, e.newTx.theoreticalMinerFee))
      } else {
        (None, None, None)
      }
    case e: BitcoinBlockSeen if (!e.reorgHeight.isDefined) =>
      val (amount, lastTxid) = findAmount(invoice.paymentAddress, e.newBlock.txs)
      if (amount.isDefined) {
        handleTxInNew(amount.get, lastTxid, Some(e.newBlock.height), e.newBlock.timestamp, 1, invoice, true)
      } else {
        if (e.newBlock.timestamp > invoice.expirationTime) {
          val retInvoice = invoice.copy(status = Expired, updateTime = e.newBlock.timestamp)
          (Some(retInvoice), Some(InvoiceUpdate(invoice.id, Expired)), convertToNotification(retInvoice))
        } else
          (None, None, None)
      }
    case e: BitcoinBlockSeen if (e.reorgHeight.isDefined) =>
      (None, None, None)
    case _ =>
      //      log.info("ignore the event: " + event + " which invoice is: " + invoice)
      (None, None, None)
  }

  private def statePaidHandler(event: AnyRef, invoice: Invoice): (Option[Invoice], Option[InvoiceUpdate], Option[InvoiceNotification]) = event match {
    case e: BitcoinBlockSeen if (!e.reorgHeight.isDefined) =>
      var setHeight = false
      val newInvoice = if (containsTx(e.newBlock.txs, invoice.txid.get) && !invoice.lastPaymentBlockHeight.isDefined) {
        setHeight = true
        invoice.copy(lastPaymentBlockHeight = Some(e.newBlock.height), updateTime = e.newBlock.timestamp)
      } else invoice

      if (newInvoice.lastPaymentBlockHeight.isDefined &&
        e.newBlock.height >= newInvoice.lastPaymentBlockHeight.get + newInvoice.data.transactionSpeed - 1) {
        val retInvoice = newInvoice.copy(status = Confirmed, updateTime = e.newBlock.timestamp)
        (Some(retInvoice), Some(InvoiceUpdate(newInvoice.id, Confirmed,
          lastPaymentBlockHeight = newInvoice.lastPaymentBlockHeight)), if (invoice.status == Paid) convertToNotification(retInvoice) else None) // avoid confirmed send twice
      } else if (e.newBlock.timestamp > newInvoice.invalidTime) {
        val retInvoice = newInvoice.copy(status = Invalid, updateTime = e.newBlock.timestamp)
        (Some(retInvoice), Some(InvoiceUpdate(newInvoice.id, Invalid,
          lastPaymentBlockHeight = newInvoice.lastPaymentBlockHeight)), convertToNotification(retInvoice))
      } else {
        (Some(newInvoice), if (setHeight) Some(InvoiceUpdate(newInvoice.id, newInvoice.status,
          lastPaymentBlockHeight = newInvoice.lastPaymentBlockHeight))
        else None, None)
      }
    case e: BitcoinBlockSeen if (e.reorgHeight.isDefined) =>
      if (invoice.lastPaymentBlockHeight.isDefined && invoice.lastPaymentBlockHeight.get > e.reorgHeight.get)
        (Some(invoice.copy(lastPaymentBlockHeight = None, updateTime = e.newBlock.timestamp)), Some(InvoiceUpdate(
          invoice.id, invoice.status, lastPaymentBlockHeight = invoice.lastPaymentBlockHeight)), None)
      else
        (None, None, None)
    case _ =>
      //      log.info("ignore the event: " + event + " which invoice is: " + invoice)
      (None, None, None)
  }

  private def stateConfirmedHandler(event: AnyRef, invoice: Invoice): (Option[Invoice], Option[InvoiceUpdate], Option[InvoiceNotification]) = event match {
    case e: BitcoinBlockSeen if (!e.reorgHeight.isDefined) =>
      var payHeight: Option[Long] = None
      val newInvoice = invoice.lastPaymentBlockHeight.isDefined match {
        case true => invoice
        case false if containsTx(e.newBlock.txs, invoice.txid.get) => // high speed invoice confirmed by tx, without block
          payHeight = Some(e.newBlock.height)
          invoice.copy(lastPaymentBlockHeight = payHeight, updateTime = e.newBlock.timestamp)
        case _ => invoice
      }
      if (!newInvoice.lastPaymentBlockHeight.isDefined)
        return (None, None, None)
      if (e.newBlock.height >= newInvoice.lastPaymentBlockHeight.get + 5) {
        val retInvoice = newInvoice.copy(status = Complete, updateTime = e.newBlock.timestamp)
        (Some(retInvoice), Some(InvoiceUpdate(newInvoice.id, Complete, None, payHeight)), convertToNotification(retInvoice))
      } else {
        payHeight.isDefined match {
          case true =>
            (Some(newInvoice), Some(InvoiceUpdate(newInvoice.id, newInvoice.status, lastPaymentBlockHeight = payHeight)), None)
          case false =>
            (None, None, None)
        }
      }
    case e: BitcoinBlockSeen if (e.reorgHeight.isDefined) =>
      if (!invoice.lastPaymentBlockHeight.isDefined)
        return (None, None, None)
      if (e.reorgHeight.get >= invoice.lastPaymentBlockHeight.get + invoice.data.transactionSpeed - 1) {
        (None, None, None)
      } else {
        val newInvoice = if (invoice.lastPaymentBlockHeight.get > e.reorgHeight.get)
          invoice.copy(lastPaymentBlockHeight = None)
        else
          invoice
        val retInvoice = newInvoice.copy(status = ReorgingConfirmed, updateTime = e.newBlock.timestamp)
        (Some(retInvoice), Some(InvoiceUpdate(newInvoice.id, ReorgingConfirmed,
          lastPaymentBlockHeight = newInvoice.lastPaymentBlockHeight)), convertToNotification(retInvoice))
      }
    case _ =>
      //      log.info("ignore the event: " + event + " which invoice is: " + invoice)
      (None, None, None)
  }

  private def handleTxInNew(amount: Double, txid: Option[String], height: Option[Long], timestamp: Long,
    txHeight: Int, invoice: Invoice, sufficientFee: Boolean): (Option[Invoice], Option[InvoiceUpdate], Option[InvoiceNotification]) = {
    if (!sufficientFee)
      return (None, None, None)
    val sumAmount = BigDecimal(amount + invoice.btcPaid.getOrElse(0.0)).setScale(8, BigDecimal.RoundingMode.HALF_UP).toDouble
    val newInvoice = if (invoice.txid.isDefined && invoice.txid == txid)
      return (None, None, None)
    else
      invoice.copy(btcPaid = Some(sumAmount), txid = txid, updateTime = timestamp, lastPaymentBlockHeight = height)
    if (sumAmount >= invoice.btcPrice - 0.00011) {
      if (invoice.data.transactionSpeed <= txHeight) {
        val retInvoice = newInvoice.copy(status = Confirmed, updateTime = timestamp)
        (Some(retInvoice), Some(InvoiceUpdate(invoice.id, Confirmed, Some(sumAmount), height, txid)),
          convertToNotification(retInvoice))
      } else {
        val retInvoice = newInvoice.copy(status = Paid, updateTime = timestamp)
        (Some(retInvoice), Some(InvoiceUpdate(invoice.id, Paid, Some(sumAmount), height, txid)),
          convertToNotification(retInvoice))
      }
    } else {
      (Some(newInvoice), Some(InvoiceUpdate(
        newInvoice.id, newInvoice.status, newInvoice.btcPaid, newInvoice.lastPaymentBlockHeight, txid)),
        if (amount > 0) convertToNotification(newInvoice) else None)
    }
  }

  private def findAmount(address: String, ports: Option[Seq[BitcoinTxPort]]): Option[Double] = {
    if (ports.isDefined) {
      val amounts = ports.get.filter(i => (i.address == address && i.amount.isDefined)).map(_.amount.get)
      if (amounts.size > 0)
        Some(amounts.sum)
      else
        None
    } else None
  }

  private def findAmount(address: String, txs: Seq[BitcoinTx]): (Option[Double], Option[String]) = {
    val relateTxs = findRelatedTxs(address, txs)
    val outputs = relateTxs.flatMap(_.outputs.get).filter(_.address == address)
    if (outputs.size > 0)
      (Some(outputs.map(_.amount.getOrElse(0.0)).sum), relateTxs.last.txid)
    else
      (None, None)
  }

  private def findRelatedTxs(address: String, txs: Seq[BitcoinTx]): Seq[BitcoinTx] = {
    txs.filter(tx => (tx.txType == Some(Deposit) && tx.outputs.isDefined &&
      tx.outputs.get.exists(port => port.address == address)))
  }

  private def containsTx(txs: Seq[BitcoinTx], txid: String): Boolean = txs.exists(tx =>
    tx.txid.isDefined && tx.txid.get == txid
  )

  private def convertToNotification(invoice: Invoice): Option[InvoiceNotification] = {
    val notificationUrl = invoice.data.notificationURL
    Some(InvoiceNotification(notificationUrl.getOrElse(""), invoice))
  }

  private def isSufficientFee(minerFee: Option[Long], theoreticalMinerFee: Option[Long]) = minerFee.getOrElse(0L) >= theoreticalMinerFee.getOrElse(0L)
}
