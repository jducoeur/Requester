package org.querki.requester

import akka.actor._

/**
 * @author jducoeur
 */

object ComprehensionTests {
  case object Start
  case class Response(msg:String)
  case object Hello
  case object There
  case object World
  
  class Answering extends Actor with Requester {
    def receive = handleRequestResponse orElse {
      case Hello => sender ! Response("Hello")
      case There => sender ! Response(" there,")
      case World => sender ! Response(" world!")
    }
  }
  
  class Asking extends Actor with Requester {
    
    lazy val answers = context.actorOf(Props(classOf[Answering]))
    
    def receive = handleRequestResponse orElse {
      case Start => {
        for {
          Response(hello) <- answers.request(Hello)
          Response(there) <- answers.request(There)
          Response(world) <- answers.request(World)
        }
          sender ! hello + there + world
      }
    }
  }
}

class ComprehensionTests extends RequesterTests {
  
  import ComprehensionTests._
  
  "Asker" should {
    "be able to use a for comprehension" in {
      val asker = system.actorOf(Props(classOf[Asking]))
      asker ! Start
      expectMsg("Hello there, world!")
    }
  }
  
}
