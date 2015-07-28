package org.querki.requester

import scala.util.{Try,Success,Failure}

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
  
  class Answering extends QTestActor {
    def doReceive = {
      case Hello => sender ! Response("Hello")
      case There => sender ! Response(" there,")
      case World => sender ! Response(" world!")
    }
  }
  
  class Asking extends QTestActor {
    
    lazy val answers = context.actorOf(Props(classOf[Answering]))
    
    def doReceive = {
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
  
  class RawExponent extends QTestActor {
    def doReceive = {
      case Start => {
        for {
          four <- doubler.requestFor[Int](2)
          eight <- doubler.requestFor[Int](four)
          sixteen <- doubler.requestFor[Int](eight)
        }
          sender ! sixteen
      }
    }
  }
  
  class MapExponent extends QTestActor {
    def doReceive = {
      case Start => {
        val rm = for {
          four <- doubler.requestFor[Int](2)
          eight <- doubler.requestFor[Int](four)
          sixteen <- doubler.requestFor[Int](eight)
        }
          yield sixteen
          
        rm foreach { sixteen => sender ! sixteen }
      }
    }    
  }
  
  case class StartWith(errorP:Boolean)
  
  class CompleteExponent extends QTestActor {
    def doReceive = {
      case StartWith(errorP) => {
        val rm = for {
          four <- doubler.requestFor[Int](2)
          eight <- doubler.requestFor[Int](four)
          dummy = if (errorP) throw new Exception("BOOM")
          sixteen <- doubler.requestFor[Int](eight)
        }
          yield sixteen
          
        rm onComplete {
          case Success(sixteen) => sender ! sixteen
          case Failure(ex) => sender ! ex
        }
      }
    }    
  }
  
  case class Terms(seed:Int, exp:Int)
  
  class Exponent extends QTestActor {
    def doReceive = {
      case Terms(seed, exp) => askDoubler(seed, exp) foreach { result => sender ! result }
    }
    
    def askDoubler(seed:Int, exp:Int):RequestM[Int] = {
      if (exp == 1) {
        RequestM.successful(seed)
      } else {
        doubler.requestFor[Int](seed) flatMap { doubled =>
          askDoubler(doubled, exp - 1)
        }
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
  
  "Requester" should {
    "be able to flatMap manually" in {
      val exp = system.actorOf(Props(classOf[RawExponent]))
      exp ! Start
      expectMsg(16)
    }
    
    "be able to map using yield" in {
      val exp = system.actorOf(Props(classOf[MapExponent]))
      exp ! Start
      expectMsg(16)      
    }
    
    "be able to use onComplete for success" in {
      val exp = system.actorOf(Props(classOf[CompleteExponent]))
      exp ! StartWith(false)
      expectMsg(16)
    }
    
    "be able to use onComplete for failure" in {
      val exp = system.actorOf(Props(classOf[CompleteExponent]))
      exp ! StartWith(true)
      val ex = expectMsgClass(dur, classOf[Exception])
      assert(ex.getMessage == "BOOM")
    }
    
    "be able to recursively flatMap" in {
      val exp = system.actorOf(Props(classOf[Exponent]))
      exp ! Terms(2,4)
      expectMsg(16)
    }
  }
}
