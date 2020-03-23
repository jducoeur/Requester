lazy val root = project.in(file("."))

lazy val akkaV = "2.5.27"

name := "Requester library for Akka"

normalizedName := "requester"

version := "2.7"

organization := "org.querki"

scalaVersion := "2.13.1"

crossScalaVersions := Seq("2.11.8", "2.12.8", "2.13.1")

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % akkaV,
  "com.typesafe.akka" %% "akka-testkit" % akkaV,
  "org.scalatest" %% "scalatest" % "3.1.1" % "test",
  "com.lihaoyi" %% "sourcecode" % "0.1.9"
)

publishTo := sonatypePublishToBundle.value

usePgpKeyHex("A5A4DA66BF0A391F46AEF0EAC74603EB63699C41")
