#!/bin/sh
exec ~/software/scala-2.10.3/bin/scala "$0" "$@"
!#



import scala.util.matching.Regex
import java.io.File
import java.util.Calendar
object Generator {

  val structNameExtractor = """\s+struct\s+(\w+)\s*\{""".r
  val structFieldCounter = """\d+\w*:\w*""".r
  val enumNameExtractor = """\s+enum\s+(\w+)\s*\{""".r

  // Do the generation and replace existing files
  val enums = extractEnumsFromFile("bitway-client/src/main/thrift/data.thrift") ++
    extractEnumsFromFile("bitway-client/src/main/thrift/message.thrift") ++
    extractEnumsFromFile("bitway-client/src/main/thrift/state.thrift")

  val structs = extractStructsFromFile("bitway-client/src/main/thrift/data.thrift") ++
    extractStructsFromFile("bitway-client/src/main/thrift/message.thrift") ++
    extractStructsFromFile("bitway-client/src/main/thrift/state.thrift")

  def apply() = {
    generateSerializerFile("ThriftBinarySerializer", 607870725, "BinaryScalaCodec", structs,
      "bitway-client/src/main/scala/com/coinport/bitway/serializers/ThriftBinarySerializer.scala")

    generateSerializerFile("ThriftJsonSerializer", 607100416, "JsonScalaCodec", structs,
      "bitway-client/src/main/scala/com/coinport/bitway/serializers/ThriftJsonSerializer.scala")

    generateConfigFile("ThriftBinarySerializer", structs,
      "bitway-client/src/main/resources/serialization.conf")

    generateJson4sSerializerFile("ThriftEnumJson4sSerialization", enums,
      "bitway-client/src/main/scala/com/coinport/bitway/serializers/ThriftEnumJson4sSerialization.scala")
  }
  // Auto-generate enum serializer code

  def extractEnumsFromFile(file: String): Seq[String] = {
    val lines = scala.io.Source.fromFile(file).mkString
    enumNameExtractor.findAllIn(lines).matchData.toSeq.map(_.group(1)).sortWith(_<_)
  }

  def enumSerializerClause(enum: String) = ENUM_SERIALIZER.format(enum, enum, enum, enum)
    def enumInMapCase(enum: String) = ENUM_IN_MAP_CASE.format(enum)

  def generateJson4sSerializerFile(className: String, enums: Seq[String], outputFile: String) = {
    val enumInMapCases = enums.map(enumInMapCase).mkString
    val enumSerializers = enums.map(enumSerializerClause).mkString
    val enumFormats = enums.map(enum => ENUM_SERIALIZER2.format(enum)).mkString
    val code = ENUM_CODE_TEMPLATE.format(enumInMapCases, className, enumSerializers, enumFormats)
    writeToFile(code, outputFile)
  }

  // Auto-generate EventSerializer code
  def extractStructsFromFile(file: String): Seq[String] = {
    val lines = scala.io.Source.fromFile(file).mkString
    structNameExtractor.findAllIn(lines).matchData.toSeq.map(_.group(1)).sortWith(_<_)
  }

  def generateSerializerFile(className: String, id: Int, codec: String, structs: Seq[String], outputFile: String) = {
    val code = SERIALIZER_CODE_TEMPLATE.format(className, id,
      structs.zipWithIndex.map { case (struct, idx) => codecClause(idx, codec, struct) }.mkString("\n"),
      structs.zipWithIndex.map { case (struct, idx) => toBinaryClauses(idx, struct) }.mkString("\n"),
      structs.zipWithIndex.map { case (struct, idx) => fromBinaryClause(idx, struct) }.mkString("\n"))
    writeToFile(code, outputFile)
  }

  def codecClause(idx: Int, codec: String, struct: String) = s"  lazy val _c${struct} = ${codec}(${struct})"
  def toBinaryClauses(idx: Int, struct: String) = s"    case m: $struct => _c${struct}(m)"
  def fromBinaryClause(idx: Int, struct: String) = s"    case Some(c) if c == classOf[${struct}.Immutable] => _c${struct}.invert(bytes).get"

  // Auto-generate Akka serialization configuration file
  def generateConfigFileEntry(struct: String) = "      \"com.coinport.bitway.data.%s\" = thrift".format(struct)
  def generateConfigFile(className: String, structs: Seq[String], outputFile: String) = {
    val configs = SERIALIZATION_CONFIG_TEMPLATE.format(className, structs.map(generateConfigFileEntry).mkString("\n"))
    writeToFile(configs, outputFile)
  }

  def writeToFile(content: String, file: String) = {
    val pw = new java.io.PrintWriter(new File(file))
    try pw.write(content) finally pw.close()
  }

  val ENUM_SERIALIZER = """
  class %sSerializer extends CustomSerializer[%s](format => (
    { case JString(s) => %s.valueOf(s).get }, {
      case x: %s => JString(x.name)
    }))
"""
  val ENUM_IN_MAP_CASE = "\n          case ks: %s => ks.name.toUpperCase"

  val ENUM_SERIALIZER2 = " +\n    new %sSerializer"

  val ENUM_CODE_TEMPLATE = """
/**
 * Copyright (C) 2014 Coinport Inc. <http://www.coinport.com>
 *
 * This file was auto generated by auto_gen_serializer.sh
 */

package com.coinport.bitway.serializers

import org.json4s.CustomSerializer
import org.json4s._
import org.json4s.ext._
import com.coinport.bitway.data._
import org.json4s.native.Serialization

object MapSerializer extends Serializer[Map[Any, Any]] {
  def serialize(implicit format: Formats): PartialFunction[Any, JValue] = {
    case m: Map[_, _] => JObject(m.map({
      case (k, v) => JField(
        k match {
          case ks: String => ks%s
          case ks: Any => ks.toString
        },
        Extraction.decompose(v))
    }).toList)
  }

  // TODO(d): https://github.com/json4s/json4s/blob/master/tests/src/test/scala/org/json4s/native/SerializationExamples.scala
  def deserialize(implicit format: Formats): PartialFunction[(TypeInfo, JValue), Map[Any, Any]] = {
    sys.error("Not interested.")
  }
}

object %s {
%s
  implicit val formats = Serialization.formats(NoTypeHints)%s
}
"""

  val SERIALIZER_CODE_TEMPLATE = """
/**
 * Copyright (C) 2014 Coinport Inc. <http://www.coinport.com>
 *
 * This file was auto generated by auto_gen_serializer.sh
 */

package com.coinport.bitway.serializers

import akka.serialization.Serializer
import com.twitter.bijection.scrooge.BinaryScalaCodec
import com.coinport.bitway.data._

class %s extends Serializer {
  val includeManifest: Boolean = true
  val identifier = %d
%s

  def toBinary(obj: AnyRef): Array[Byte] = obj match {
%s

    case m => throw new IllegalArgumentException("Cannot serialize object: " + m)
  }

  def fromBinary(bytes: Array[Byte],
    clazz: Option[Class[_]]): AnyRef = clazz match {
%s

    case Some(c) => throw new IllegalArgumentException("Cannot deserialize class: " + c.getCanonicalName)
    case None => throw new IllegalArgumentException("No class found in EventSerializer when deserializing array: " + bytes.mkString("").take(100))
  }
}
"""

  val SERIALIZATION_CONFIG_TEMPLATE = """
#
# Copyright (C) 2014 Coinport Inc. <http://www.coinport.com>
#
# This file was auto generated by auto_gen_serializer.sh

akka {
  actor {
    serializers {
      bytes = "akka.serialization.ByteArraySerializer"
      proto = "akka.remote.serialization.ProtobufSerializer"
      akka-containers = "akka.remote.serialization.MessageContainerSerializer"
      daemon-create = "akka.remote.serialization.DaemonMsgCreateSerializer"
      akka-cluster = "akka.cluster.protobuf.ClusterMessageSerializer"
      akka-pubsub = "akka.contrib.pattern.protobuf.DistributedPubSubMessageSerializer"
      akka-persistence-snapshot = "akka.persistence.serialization.SnapshotSerializer"
      akka-persistence-message = "akka.persistence.serialization.MessageSerializer"
      thrift = "com.coinport.bitway.serializers.%s"
    }
    serialization-bindings {
      "[B" = bytes
      "akka.event.Logging$LogEvent" = bytes
      "com.google.protobuf.GeneratedMessage" = proto
      "com.google.protobuf.Message" = proto
      "akka.actor.ActorSelectionMessage" = akka-containers
      "akka.remote.DaemonMsgCreate" = daemon-create
      "akka.cluster.ClusterMessage" = akka-cluster
      "akka.contrib.pattern.DistributedPubSubMessage" = akka-pubsub
      "akka.persistence.serialization.Snapshot" = akka-persistence-snapshot
      "akka.persistence.serialization.Message" = akka-persistence-message

%s
    }
  }
}
"""

  val JSON_PROTOCOL_TEMPLATE = """
/**
 * Copyright (C) 2014 Coinport Inc. <http://www.coinport.com>
 *
 * This file was auto generated by auto_gen_serializer.sh
 */

import spray.json._
import DefaultJsonProtocol._

import com.coinport.bitway.data._

object MessageJsonProtocol extends DefaultJsonProtocol {
%s
}
"""
}

Generator()