import SonatypeKeys._

sonatypeSettings

lazy val root = project.in(file("."))

name := "Requester library for Akka"

normalizedName := "requester"

version := "1.1"

organization := "org.querki"

scalaVersion := "2.11.6"

crossScalaVersions := Seq("2.10.4", "2.11.6")

libraryDependencies += "com.typesafe.akka" %% "akka-actor" % "2.3.9"
  
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
