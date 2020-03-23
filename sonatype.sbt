
sonatypeProfileName := "org.querki"

publishMavenStyle := true

licenses += ("MIT License", url("http://www.opensource.org/licenses/mit-license.php"))

import xerial.sbt.Sonatype._
sonatypeProjectHosting := Some(GitHubHosting("jducoeur", "jsext", "justin@querki.net"))

homepage := Some(url("http://www.querki.net/"))

scmInfo := Some(ScmInfo(
  url("https://github.com/jducoeur/Requester"),
  "scm:git:git@github.com/jducoeur/Requester.git",
  Some("scm:git:git@github.com/jducoeur/Requester.git")))

developers := List(
  Developer(id = "jducoeur", name = "Mark Waks", email = "justin@querki.net", url = new URL("https://github.com/jducoeur/"))
)
