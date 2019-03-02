import sbt._
import Keys._
import com.twitter.scrooge._
import com.typesafe.sbt.SbtScalariform._
import sbtassembly.Plugin._
import AssemblyKeys._
import scalabuff.ScalaBuffPlugin._

object BitwayBuild extends Build {
  val bitwayVersion = "0.2.10-SNAPSHOT"

  val akkaVersion = "2.3.3"
  val bijectionVersion = "0.6.2"
  val scroogeVersion = "3.13.0"
  val sprayVersion = "1.3.1"

  val sharedSettings = Seq(
    organization := "com.coinport",
    version := bitwayVersion,
    scalaVersion := "2.10.4",
    fork := true,
    crossScalaVersions := Seq("2.10.4"),
    initialCommands in console := """
      import akka.pattern.ask
      import akka.util.Timeout
      import scala.concurrent.ExecutionContext.Implicits.global
      import scala.concurrent.duration._
      import com.coinport.bitway.client.Client._
      import com.coinport.bitway.data._
      import Currency._

      implicit val timeout = Timeout(2 seconds)
    """,
    scalacOptions ++= Seq("-encoding", "utf8"),
    scalacOptions ++= Seq("-optimize"),
    scalacOptions += "-deprecation",
    publishMavenStyle := true,
    publishArtifact in (Compile, packageDoc) := false,
    publishArtifact in (Compile, packageSrc) := false,
    publishArtifact in Test := false,
    pomIncludeRepository := { _ => false },
    credentials += Credentials(Path.userHome / ".ivy2" / ".credentials"),
    publishTo <<= (version) { version: String =>
  val nexus = "https://nexus.coinport.com/nexus/content/repositories/"
  if (version.trim.endsWith("SNAPSHOT"))
    Some("snapshots" at nexus + "snapshots/")
  else
    Some("releases"  at nexus + "releases/")
},

    resolvers ++= Seq(
      Resolver.sonatypeRepo("snapshots"),
      "Spray Repo" at "http://repo.spray.io",
      "Nexus release" at "https://nexus.coinport.com/nexus/content/repositories/releases",
      "Nexus Snapshots" at "https://nexus.coinport.com/nexus/content/groups/public")) ++ assemblySettings ++ Seq(
      test in assembly := {},
      mergeStrategy in assembly <<= (mergeStrategy in assembly) { (old) =>
        {
          case PathList("javax", "servlet", xs @ _*) => MergeStrategy.first
          case PathList(ps @ _*) if ps.last endsWith ".html" => MergeStrategy.first
          // case PathList(ps @ _*) if ps.last endsWith ".xml" => MergeStrategy.first
          // case PathList(ps @ _*) if ps.last endsWith ".properties" => MergeStrategy.first
          case "application.conf" => MergeStrategy.concat
          case "unwanted.txt" => MergeStrategy.discard
          case x => old(x)
        }
      })

  lazy val root = Project(
    id = "bitway",
    base = file("."),
    settings = Project.defaultSettings ++ sharedSettings)
    .aggregate(client, backend)

  lazy val client = Project(
    id = "bitway-client",
    base = file("bitway-client"),
    settings = Project.defaultSettings ++
      sharedSettings ++
      ScroogeSBT.newSettings ++
      scalariformSettings ++
      scalabuffSettings)
    .settings(libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-cluster" % akkaVersion,
      "com.typesafe.akka" %% "akka-contrib" % akkaVersion,
      "com.typesafe.akka" %% "akka-persistence-experimental" % akkaVersion,
      "org.specs2" %% "specs2" % "2.3.8" % "test",
      "org.scalatest" %% "scalatest" % "2.0" % "test",
      "com.twitter" %% "bijection-scrooge" % bijectionVersion,
      "com.twitter" %% "bijection-json4s" % bijectionVersion,
      "com.twitter" %% "bijection-json" % bijectionVersion,
      "com.twitter" %% "scrooge-core" % scroogeVersion,
      "com.twitter" %% "scrooge-serializer" % scroogeVersion,
      "org.slf4s" %% "slf4s-api" % "1.7.6",
      "io.spray" %% "spray-json" % "1.2.5",
      "org.json4s" %% "json4s-native" % "3.2.7",
      "org.json4s" %% "json4s-ext" % "3.2.7",
      "com.google.guava" % "guava" % "16.0.1",
      "org.mongodb" %% "casbah" % "2.6.5",
      "com.twitter" %% "util-eval" % "6.12.1",
      "com.google" % "bitcoinj" % "0.11.3",
      "net.databinder.dispatch" %% "dispatch-core" % "0.11.1",
      "org.apache.thrift" % "libthrift" % "0.8.0"))
    .configs(ScalaBuff)

  lazy val backend = Project(
    id = "bitway-backend",
    base = file("bitway-backend"),
    settings = Project.defaultSettings ++
      sharedSettings ++
      ScroogeSBT.newSettings ++
      scalariformSettings)
    .settings(
      libraryDependencies += ("com.coinport" %% "akka-persistence-hbase" % "1.0.11-SNAPSHOT")
        .exclude("org.jboss.netty", "netty")
        .exclude("org.jruby", "jruby-complete")
        .exclude("javax.xml.stream", "stax-api")
        .exclude("javax.xml.stream", "stax-api")
        .exclude("commons-beanutils", "commons-beanutils")
        .exclude("commons-beanutils", "commons-beanutils-core")
        .exclude("tomcat", "jasper-runtime")
        .exclude("tomcat", "jasper-compiler")
        .exclude("org.slf4j", "slf4j-log4j12"),
      libraryDependencies ++= Seq(
        "io.spray" %% "spray-json" % "1.2.6",
        "com.typesafe.akka" %% "akka-cluster" % akkaVersion,
        "com.typesafe.akka" %% "akka-contrib" % akkaVersion,
        "com.typesafe.akka" %% "akka-remote" % akkaVersion,
        "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
        "com.typesafe.akka" %% "akka-testkit" % akkaVersion,
        "com.typesafe.akka" %% "akka-persistence-experimental" % akkaVersion,
        "org.specs2" %% "specs2" % "2.3.8" % "test",
        "org.scalatest" %% "scalatest" % "2.0" % "test",
        "org.apache.commons" % "commons-io" % "1.3.2",
        "ch.qos.logback" % "logback-classic" % "1.1.2",
        "ch.qos.logback" % "logback-core" % "1.1.2",
        "io.spray" % "spray-can" % sprayVersion,
        "io.spray" % "spray-routing" % sprayVersion,
        "io.spray" % "spray-client" % sprayVersion,
        "io.spray" % "spray-http" % sprayVersion,
        "de.flapdoodle.embed" % "de.flapdoodle.embed.mongo" % "1.42" % "test",
        "net.debasishg" % "redisclient_2.10" % "2.12",
        "mysql" % "mysql-connector-java" % "5.1.34",
        "org.scalikejdbc" %% "scalikejdbc" % "2.1.4",
        "org.scalikejdbc" %% "scalikejdbc-config" % "2.1.4"),
      parallelExecution in Test := false)
    .dependsOn(client)
}
