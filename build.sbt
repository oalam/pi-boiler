name := "regulation"

version := "1.3"

scalaVersion := "2.11.7"

libraryDependencies ++= Seq(
    "log4j" % "log4j" % "1.2.17",
    "com.pi4j" % "pi4j-core" % "1.0",
    "com.typesafe.akka" % "akka-actor_2.11" % "2.4.1",
    "com.typesafe.akka" % "akka-slf4j_2.11" % "2.4.1",
    "com.typesafe.akka" % "akka-cluster_2.11" % "2.4.1",
    "org.scalatest" % "scalatest_2.11" % "2.2.4" % "test",
    "ch.qos.logback" % "logback-classic" % "1.1.3",
    "io.spray" % "spray-can_2.11" % "1.3.3",
    "io.spray" % "spray-routing_2.11" % "1.3.3",
    "io.spray" % "spray-testkit_2.11" % "1.3.3" % "test",
    "commons-cli" % "commons-cli" % "1.3",
    "org.elasticsearch" % "elasticsearch" % "2.1.1",
    "javax.mail" % "mail" % "1.4.7"
)

resolvers += "spray repo" at "http://repo.spray.io"