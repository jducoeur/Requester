package org.querki.requester

import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.util.{Try,Success,Failure}
import scala.reflect.ClassTag

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
  
/**
 * The request "monad". It's actually a bit suspicious, in that it's mutable, but by and
 * large this behaves the way you expect a monad to work. In particular, it works with for
 * comprehensions, allowing you to compose requests much the way you normally do Futures.
 * But since it is mutable, it should never be used outside the context of an Actor.
 */
class RequestM[T] {
  /**
   * The actions to take after this Request resolves.
   * 
   * Yes, this is mutable. That's arguably sad and evil, but remember that this is only intended
   * for use inside the pseudo-single-threaded world of an Actor.
   */
  private var callbacks = List.empty[Function[Try[T], _]]
  
  /**
   * Store the result, so that if someone hooks a callback into me after I'm resolved, I
   * can handle that immediately. (This can happen if, for example, RequestM.successful()
   * comes into play.)
   * 
   * Yes, also mutable. Same argument. Deal.
   */
  private var result:Option[Try[T]] = None
  
  private [requester] def resolve(v:Try[T]):Unit = {
    result = Some(v)
    try {
      callbacks foreach { cb => cb(v) }
    } catch {
      case th:Throwable => // Should we do anything about Exceptions raised downstream?
    }
  }
  
  def onComplete[U](handler: (Try[T]) => U):Unit = {
    result match {
      case Some(v) => handler(v)
      case None => callbacks :+= handler
    }
  }
  
  // TODO: This should probably become onSuccess
  def handleSucc(handler:T => _):Unit = {
    onComplete { t:Try[T] =>
      t match {
        case Success(v) => handler(v)
        case Failure(ex) =>
      }
    }
  }
  
  def foreach(handler:T => Unit):Unit = {
    handleSucc(handler)
  }
  
  def map[U](handler:T => U):RequestM[U] = {
    // What's going on here? I need to synchronously return a new RequestM, but I won't
    // actually complete until sometime later. So when I *do* complete, pipe that result
    // into the given handler function, and use that to resolve the returned child.
    val child:RequestM[U] = new RequestM
    onComplete {
      case Success(v) => {
        try {
          val result = handler(v)
          child.resolve(Success(result))
          result
        } catch {
          case th:Throwable => child.resolve(Failure(th))
        }        
      }
      case Failure(ex) => child.resolve(Failure(ex))
    }
    child
  }
  
  def flatMap[U](handler:T => RequestM[U]):RequestM[U] = {
    // What's going on here? The problem we have is that we need to return a RequestM
    // *synchronously* from flatMap, so that higher-level code can compose on it. But
    // the *real* RequestM being returned from handler won't come into existence until
    // some indefinite time in the future. So we need to create a new one right now,
    // and when the real one comes into existence, link its success to that of the one
    // we're returning here.
    val child:RequestM[U] = new RequestM
    onComplete {
      case Success(v) => {
        try {
          val subHandler = handler(v)
          subHandler.onComplete { u:Try[U] => child.resolve(u) }
          subHandler
        } catch {
          case th:Throwable => { child.resolve(Failure(th)) }
        }
      }
      case Failure(ex) => child.resolve(Failure(ex))
    }
    child
  }
  
  def filter(p:T => Boolean):RequestM[T] = {
    val filtered = new RequestM[T]
    val filteringCb:Function[T,_] = { v:T =>
      if (p(v)) {
        filtered.resolve(Success(v))
      }
    }
    handleSucc(filteringCb)
    filtered
  }
  
  def withFilter(p:T => Boolean):RequestM[T] = filter(p)
}

object RequestM {
  def successful[T](result:T):RequestM[T] = {
    val r = new RequestM[T]
    r.resolve(Success(result))
    r
  }
  
  def failed[T](ex:Throwable):RequestM[T] = {
    val r = new RequestM[T]
    r.resolve(Failure(ex))
    r
  }
}

/**
 * Implicit that hooks into *other* Actors, to provide the nice request() syntax to send
 * messages to them. These implicits are available to any Actor that mixes in Requester, but
 * RequesterImplicits should also be mixed into any other class that wants access to this
 * capability. Those other classes must have access to a Requester -- usually, they should be
 * functional classes owned *by* a Requester.
 * 
 * This trait gives you the functions that you actually call directly -- request() and requestFor().
 * But those calls mostly create RequestM objects, and the actual work gets
 * done by the associated Requester.
 */
trait RequesterImplicits {
  
  /**
   * The actual Requester that is going to send the requests and process the responses. If
   * you mix RequesterImplicits into a non-Requester, this must point to that Actor, which
   * does all the real work. (If you are using this from within Requester, it's already set.)
   */
  def requester:Requester
  
  /**
   * Hook to add the request() methods to a third-party Actor.
   */
  implicit class RequestableActorRef(target:ActorRef) {
    /**
     * The basic, simple version of request() -- sends a message, process the response.
     * 
     * You can think of request as a better-behaved version of ask. Where ask sends a message to the
     * target actor, and gives you a Future that will execute when the response is received, request
     * does the same thing but will process the resulting RequestM '''in the Actor's receive loop'''.
     * While this doesn't save you from every possible problem, it makes it much easier to write clear
     * and complex operations, involving coordinating several different Actors, without violating the
     * central invariants of the Actor model.
     * 
     * The current sender will be preserved and will be active during the processing of the results,
     * so you can use it as normal.
     * 
     * This version of the call does not impose any expectations on the results. You can use a
     * destructuring case class in a for comprehension if you want just a single return type, or you
     * can map the RequestM to a PartialFunction in order to handle several possible returns.
     * 
     * @param msg The message to send to the target actor.
     */
    def request(msg:Any):RequestM[Any] = {
      val req = new RequestM[Any]
      requester.doRequest[Any](target, msg, req)
      req
    }
    
    /**
     * A more strongly-typed version of request().
     * 
     * This works pretty much exactly like request, but expects that the response will be of type T. It will
     * throw a ClassCastException if anything else is received. Otherwise, it is identical to request().
     */
    def requestFor[T](msg:Any)(implicit tag: ClassTag[T]):RequestM[T] = {
      val req = new RequestM[T]
      requester.doRequest[T](target, msg, req)
      req
    }
  }
  
  /**
   * Convert a Future into a Request.
   * 
   * This takes the specified Future, and runs it in the Requester's main loop, to make it properly safe. As usual,
   * sender will be preserved.
   * 
   * This is implicit, so if you are in a context that already expects a Request (such as a for comprehension with a Request
   * at the top), it will quietly turn the Future into a Request. If Request isn't already expected, though, you'll have
   * to specify loopback explicitly.
   */
  implicit def loopback[T](f:Future[T])(implicit tag:ClassTag[T]):RequestM[T] = {
    val req = new RequestM[T]
    requester.doRequestGuts[T](f, req)
    req
  }
  
  /**
   * Convert a Request into a Future.
   * 
   * Sometimes, at the edges of the API, you need to think in terms of Futures. When this is necessary,
   * this implicit will take your RequestM and turn it into a Future of the matching type.
   */
  implicit def request2Future[T](req:RequestM[T]):Future[T] = {
    val promise = Promise[T]
    // TODO: this should work for failures as well:
    req foreach { t => promise.success(t) }
    promise.future
  }
}

/**
 * Easy and relatively safe variant of "ask".
 * 
 * The idea here is that it would be lovely to have a replacement for the "ask" pattern. Ask
 * is powerful, but quite dangerous -- in particular, handling the response in the most obvious
 * way, using the Future's completion callbacks, is a fine way to break your Actor's threading
 * and cause strange timing bugs.
 * 
 * So the idea here is to build something with similar semantics to ask, but deliberately a bit
 * dumbed-down and safer for routine use. Where ask returns a Future that you can then put 
 * callbacks on, request() takes those callbacks as parameters, and runs them *in this Actor's main thread*.
 * 
 * In other words, I want to be able to say something like:
 * 
 * def receive = {
 *   ...
 *   case MyMessage(a, b) => {
 *     otherActor.request(MyRequest(b)).foreach {
 *       case OtherResponse(c) => ...
 *     }
 *   }
 * }
 * 
 * While OtherResponse is lexically part of MyRequest, it actually *runs* during receive, just like
 * any other incoming message, so it isn't prone to the threading problems that ask is.
 * 
 * How does this work? Under the hood, it actually does use ask, but in a very specific and constrained
 * way. We send the message off using ask, and then hook the resulting Future. When the Future completes,
 * we wrap the response and the handler together in a RequestedResponse message, and loop that back
 * around as a message to the local Actor. 
 * 
 * Note that the original sender is preserved, so the callback can use it without problems. (This is the
 * most common error made when using ask, and was one of the motivations for creating Requester.) 
 * 
 * Note that, to make this work, the Request trait mixes in its own version of unhandled(). I *think* this
 * should Just Work, but it's probably the part where I'm on least-comfortable ground, so watch for edge
 * cases there. I have not yet tested how this interacts with Actor-defined unhandled(), and I'm mildly
 * concerned about possible loops.
 * 
 * IMPORTANT: Requester is *not* compatible with stateful versions of become() -- that is, if you are
 * using become() in a method where you are capturing the parameters in the closure of become(),
 * Requester will probably not work right. This is because the body of the response handler will capture
 * the closed-over parameter, and if the Actor has become() something else in the meantime, the handler
 * will use the *old* data, not the new.
 * 
 * More generally, Requester should be used with great caution if your Actor changes state frequently.
 * While it *can* theoretically be used with FSM, it may not be wise to do so, since the state machine
 * may no longer be in a compatible state by the time the response is received. Requester is mainly intended
 * for Actors that spend most or all of their time in a single state; it generally works quite well for those.
 */
trait Requester extends Actor with RequesterImplicits {
  
  val requester = this
  
  /**
   * The response from request() will be wrapped up in here and looped around. You shouldn't need to
   * use this directly. 
   */
  case class RequestedResponse[T](response:Try[T], handler:RequestM[T]) {
    def invoke = { handler.resolve(response) }
  }
  
  /**
   * Override this to specify the timeout for requests
   */
  implicit val requestTimeout = Timeout(10 seconds)
 
  /**
   * Send a request, and specify the handler for the received response. You may also specify a failHandler,
   * which will be run if the operation fails for some reason. (Most often, because we didn't receive a
   * response within the timeout window.)
   */
  def doRequest[T](otherActor:ActorRef, msg:Any, handler:RequestM[T])(implicit tag: ClassTag[T]) = {
    doRequestGuts(otherActor ask msg, handler)
  }
  
  def doRequestGuts[T](f:Future[Any], handler:RequestM[T])(implicit tag: ClassTag[T]) = {
    val originalSender = sender
    import context.dispatcher
    val fTyped = f.mapTo[T]
    fTyped.onComplete {
      case Success(resp) => {
        try {
          self.tell(RequestedResponse(Success(resp), handler), originalSender)
        } catch {
          // TBD: is this ever going to plausibly happen?
          case ex:Exception => {
            self.tell(RequestedResponse(Failure(ex), handler), originalSender)
          }
        }
      }
      case Failure(thrown) => {
        self.tell(RequestedResponse(Failure(thrown), handler), originalSender)
      }
    }    
  }
  
  def handleRequestResponse:Actor.Receive = {
    case resp:RequestedResponse[_] => resp.invoke
  }
}
