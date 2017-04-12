lazy val root = project.in(file("."))

lazy val akkaV = "2.4.6"

name := "Requester library for Akka"

normalizedName := "requester"

version := "2.6"

organization := "org.querki"

scalaVersion := "2.11.8"

crossScalaVersions := Seq("2.10.5", "2.11.8")

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % akkaV,
  "com.typesafe.akka" %% "akka-testkit" % akkaV,
  "org.scalatest" %% "scalatest" % "2.2.6" % "test",
  "com.lihaoyi" %% "sourcecode" % "0.1.3"
)
  
homepage := Some(url("http://www.querki.net/"))

licenses += ("MIT License", url("http://www.opensource.org/licenses/mit-license.php"))

scmInfo := Some(ScmInfo(
    url("https://github.com/jducoeur/Requester"),
    "scm:git:git@github.com/jducoeur/Requester.git",
    Some("scm:git:git@github.com/jducoeur/Requester.git")))

publishMavenStyle := true

publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases" at nexus + "service/local/staging/deploy/maven2")
}

pomExtra := (
  <developers>
    <developer>
      <id>jducoeur</id>
      <name>Mark Waks</name>
      <url>https://github.com/jducoeur/</url>
    </developer>
  </developers>
)

pomIncludeRepository := { _ => false }
