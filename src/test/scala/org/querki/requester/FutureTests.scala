package org.querki.requester

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

import akka.actor._

import org.scalatest.concurrent._

/**
 * @author jducoeur
 */
object FutureTests {
  case object Start
  case class TestFuture(f:Future[Int])
  
  class NewFutureActor extends QTestActor {
    def doReceive = {
      case Start => {
        val f:Future[Int] =
          for {
            four <- doubler.requestFor[Int](2)
            eight <- doubler.requestFor[Int](four)
            sixteen <- doubler.requestFor[Int](eight)
          }
            yield sixteen
        
        sender ! TestFuture(f)
      }
    }
  }
}

class FutureTests extends RequesterTests with Futures with ScalaFutures {
  import FutureTests._
  
  implicit val dur = 1 second
  
  "Requester" should {
    "be able to work through a Future, new-style" in {
      val actor = system.actorOf(Props(classOf[NewFutureActor]))
      actor ! Start
      val TestFuture(fut) = receiveOne(dur)
      whenReady(fut) { n =>
        assert(n == 16)
      }
    }
  }
}