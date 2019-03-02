resolvers ++= Seq(
  Resolver.sonatypeRepo("snapshots"),
  Resolver.sonatypeRepo("releases"),
  "Nexus Snapshots" at "http://192.168.0.105:8081/nexus/content/repositories/snapshots",
  Classpaths.typesafeResolver
)


addSbtPlugin("com.typesafe.sbt" % "sbt-scalariform" % "1.3.0")

//addSbtPlugin("com.github.mpeltonen" % "sbt-idea" % "1.7.0-SNAPSHOT")

addSbtPlugin("com.typesafe.sbteclipse" % "sbteclipse-plugin" % "2.5.0")

addSbtPlugin("com.twitter" %% "scrooge-sbt-plugin" % "3.13.0")

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.11.2")

addSbtPlugin("com.github.sbt" %% "sbt-scalabuff" % "1.3.8")
