package com.coinport.bitway.util

object Base58Coder {
  val alpha = "123456789abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ"
  val base = alpha.length

  def apply(encodedInput: String) = decode(encodedInput)
  def apply(decodedInput: Long) = encode(decodedInput)

  def encode(input: String): String = encode(input.toLong)
  def encode(input: Long) = {
    def enc(in: Long, acc: String): String = if (in < 1) acc else enc(in / base, alpha((in % base).toInt) + acc)
    enc(input, "")
  }

  def decode: PartialFunction[String, Long] = {
    case s: String if s.length == 0 => 0
    case s: String if s.head != '1' => {
      val in = s.reverse

      def dec(idx: Int, acc: BigInt): Long = if (idx == in.length) acc.toLong else dec(idx + 1, acc + alpha.indexOf(in(idx)) * BigInt(base).pow(idx))
      dec(0, 0)
    }
  }
}