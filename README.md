# Requester

One of the most useful, and most fraught, functions in Akka is `ask`. On the one hand, it is invaluable for writing clear, understandable code -- after all, the pattern of "Send this message, and then do that with the result" makes loads of sense, and it's usually easy to see what the code is trying to do.

On the other hand, `ask` is something of a landmine in practice, because it violates the most important invariant of Actors: there should never be code lexically inside of the Actor that doesn't run within the Actor's receive loop. `ask` returns a Future, and that Future will be run out-of-band, at some random time in the future. It can happen in parallel with running receive code, or not. There is lots of danger there, and it is easy to cause erratic, hard-to-reproduce bugs.

(Not to mention the fact that `sender` probably won't be set to the value you expect during the response handler. One of the easiest Akka traps to fall into is using `sender` during a Future, which almost never works.)

This library introduces `request`, which you can think of as the better-behaved big brother of `ask`. The look and feel is similar, but there is one crucial difference: the response handler from `request` is *not* Future, and it runs inside the normal receive loop. Also, unlike `ask`, `request` preserves the value of `sender`. The result is that you can safely write straightforward, intuitive, composable code for complex multi-Actor operations, like this:
```scala
case GetSpacesStatus(requester) => {
  for {
    ActiveThings(nConvs) <- conversations ? GetActiveThings
    ActiveSessions(nSessions) <- sessions ? GetActiveSessions
  }
    sender ! SpaceStatus(spaceId, state.displayName, nConvs, nSessions)
}
```
and have it work just as you expect.

### Installing Requester

To use Requester, add this to your libraryDependencies in sbt:
```
"org.querki" %% "requester" % "2.4"
```

### Using Requester

The most common and straightforward use case for Requester is when you have one Actor that wants to make requests of others. You enhance the *requesting* Actor (not the target!) with the Requester trait:
```scala
import org.querki.requester._

class MyActor extends Actor with Requester {
  ... your usual code ...
}
```
Once you've done that, you can write code like the example:
```scala
case GetSpacesStatus(requester) => {
  for {
    ActiveThings(nConvs) <- conversations ? GetActiveThings
    ActiveSessions(nSessions) <- sessions ? GetActiveSessions
  }
    sender ! SpaceStatus(spaceId, state.displayName, nConvs, nSessions)
}
```
(All examples are real code from [Querki](https://www.querki.net/), sometimes a bit simplified.)

Or if you don't need to compose, just use `foreach`:
```scala
persister.request(LoadCommentsFor(thingId, state)) foreach {
  case AllCommentsFor(_, comments) => {
    val convs = {
      val cs = buildConversations(comments)
      loadedConversations += (thingId -> cs)
      cs
    }
    f(convs)
  }
}
```
`request` returns a RequestM, which is a monad that cheats a little -- it's actually slightly mutable, so don't assume perfectly-monadic behavior, but it works as expected inside a for comprehension, and deliberately mimics the core behavior of Future. The functions `map`, `flatMap`, `foreach`, `withFilter` and `onComplete` all work pretty much the same as they do in Future, and other methods of Future will likely be added over time.

**Important:** since `request` is intended to replace `ask`, you should *not* be importing `akka.pattern._` (which is where `ask` comes from). If you have both imported, you will get a name conflict on `?`, and will have to use the wordier `request()` call instead.

### How it works

`request` actually uses `ask` under the hood, but in a very precise and constrained way. It sends your message to the target Actor and gets the response via `ask`. However, it then loops that response (plus the handler) back as a message to this Actor, preserving the original value of `sender`. This way, the response is relatively safe to use (since it is being processed within the Actor's main receive function), and you still have the sender you expect.

Note that the native function for Requester is `request()`. This is aliased to `?`, to mimic `akka.pattern.ask`. The name conflict is entirely intentional: using ask inside of a Requester is *usually* a bug, since it invites all sorts of accidental dangers. If you really want to use `ask()` inside of a Requester, do it with the full name.

### handleRequestResponse

Normally, Requester deals with this loopback automatically, by overriding `unhandled()`. However, in some exceptional cases this doesn't work -- in particular, if your receive function handles *all* messages, the loopback will never get to unhandled, so it will never get resolved. This can happen, for example, when using `stash()` aggressively during setup, stashing all messages until the Actor is fully initialized.

In cases like this, you should put `handleRequestResponse` at the front of your receive function, like this:
```scala
def receive = handleRequestResponse orElse {
  case Start => {
    persister.requestFor[LoadedState](LoadMe(myId)) foreach { currentState =>
      setState(currentState)
      unstashAll()
      become(normalReceive)
    }
  }
  
  case _ => stash()
}
```
In this example, if we didn't have handleRequestResponse there, the response to `LoadMe` would get stashed along with everything else, never processed, and the Actor would simply hang in its Start state. But putting handleRequestResponse at the front deals with the loopbacks before that stash, so everything works.

### `request` and Futures

In ordinary Akka, the above is usually enough: you usually use the results of your request-based computation to set local state in the Requester, or to send a response, typically back to `sender`. Occasionally, though, you may want to wrap the whole thing up into a Future -- this is particularly common when you are writing client/server RPC code, using Scala.js on the front end, [Autowire](https://github.com/lihaoyi/autowire) for the API communication, and Akka Actors implementing the back-end server implementation.

For a case like this, there is an implicit conversion from RequestM[T] to Future[T], so you can write code like this:
```scala
def doChangeProps(thing:Thing, props:PropMap):Future[PropertyChangeResponse] = {
  self.request(createSelfRequest(ChangeProps2(thing.toThingId, props))) map {
    case ThingFound(_, _) => PropertyChanged
    case ThingError(ex, _) => throw new querki.api.GeneralChangeFailure("Error during save")
  }
}
```
Note that the bulk of the code is doing a `request`, so it is returning a `RequestM[PropertyChangeResponse]`. Since we are in a context that expects a `Future[PropertyChangeResponse]`, the system implicitly does the conversion, and it works as you want it to.

### `requestFor`

The ordinary `request` call returns Any, as is usual in Akka. Sometimes, it is clearer to be able to state upfront what type you expect to receive, especially if there is only one "right" answer. `requestFor` is a variant of `request` that allows you to state this:
```scala
notePersister.requestFor[CurrentNotifications](Load) foreach { notes =>
  currentNotes = notes.notes.sortBy(_.id).reverse
	    
  // Okay, we're ready to roll:
  self ! InitComplete
}
```
This makes the whole thing more strongly-typed upfront -- in the above code, the compiler knows that `notes` should be a `CurrentNotifications` message. If anything other than `CurrentNotifications` is received, it will throw a `ClassCastException`.

### Retries

`request` and `requestFor` actually have a second parameter -- the number of times to retry sending this request. Akka is explicitly unreliable: that's not as obvious when running on a single-node system (where message delivery usually succeeds), but when running multi-node that can be quite important. So it is *sometimes* appropriate to retry message sends once or twice when they fail.

You can specify a retry simply by adding the parameter, like this:
```scala
myTarget.requestFor[Loaded](Load, 2) foreach { loaded => ...
```
This says that, if the request times out, Requester should retry up to 2 times. If it continues to fail, after the last retry the RequestM will fail with an `AskTimeoutException`, as usual.

**Important:** retries aren't a panacea, and you can get yourself into real trouble with them if you're not careful. All this is doing is retrying if we don't get a *response*. That doesn't necessarily mean the request didn't get through. It could be that the response failed to get through, or that the request somehow caused an exception, or that that code pathway is broken, or that it's simply taking a long time. This easy retry mechanism can be helpful, but is not by any means the same thing as reliable message delivery. Think through the whole request/response cycle before using it -- in particular, whether getting a duplicate request is going to confuse the recipient.

### loopback()

Requester is primarily focused on the common case where you are trying to send a request to another Actor within receive. But what if you want to use a plain old Future instead? That is where the loopback() function comes in.

loopback() takes one parameter, a Future, and treats it like a Request -- it wraps the Future inside of a RequestM, so that when the Future completes, it will execute the subsequent functions (foreach and map) looped back in the main loop of the Actor. As with an ordinary Request, sender is preserved across this operation.

loopback() is implicit, so if the code already expects a RequestM (for instance, inside of a for comprehension that is led off by a request()), you can just use a Future and it will auto-convert to RequestM. For example:
```scala
for {
  // This returns a RequestM
  ThingConversations(convs) <- spaceRouter.requestFor[ThingConversations](ConversationRequest(rc.requesterOrAnon, rc.state.get.id, GetConversations(rc.thing.get.id)))
  // This returns a Future
  identities <- IdentityAccess.getIdentities(getIds(convs).toSeq) 
}
   ...
```
Since this starts off with requestFor, the for comprehension is expecting RequestM; the Future returned from getIdentities automatically gets looped back, keeping everything running safely inside the Actor's receive loop.

Note however, this can be a bit fragile -- it is easy to forget to put a request at the top of the comprehension, so everything winds up using dangerous raw Futures. So use this implicit with some care.

### RequesterImplicits

The above is all fine so long as you are sending your requests from directly inside the Requester. But what if your structure is more complex -- say, you have your handlers broken up into a number of classes instantiated by and running under the Requester? (For example, in Querki, we have a single primary UserSpaceSession Actor that mediates all normal client/server requests. Each request then causes an appropriate handler object to be created, running under that Actor's receive loop, which actually deals with the functions in question.)

If you want one of these outside classes to be able to use `request`, then you should have it inherit from the RequesterImplicits trait, and override that trait's `requester` member to point to the controlling Requester Actor.

RequesterImplicits actually defines `request`, `requestFor` and `requestFuture`; it delegates the actual processing to the associated Requester. (Requester itself implements RequesterImplicits, so you can just ignore this for the ordinary case.)

### `Promise`-like Constructions

The above covers *most* situations where you want to use RequestM. But what about the down-and-dirty situations, where you have to want to compose complex but relatively primitive interactions safely? These are the sorts of situations where, if you were using `Future`s, you would need to bring `Promise` into play. The corresponding concepts in Requester are the `prep()` and `resolve()` methods.

`RequestM.prep[T]()` creates a new, raw `RequestM[T]`, containing no value and not in any conventional workflow -- it's just a `RequestM`. Sometime after you have that, you call `.resolve(v:Try[T])` on that `RequestM`, to set its value and (more importantly) fire off any subsequent requests that have been mapped onto it.

You would typically use this in situations where need to pass a `RequestM` around externally-defined walls. For example, Akka's `PersistentActor` centers on the method `persist[T](evt:T)(handler:T => Unit):Unit`. Note that this returns a Unit. So how do you compose this operation? How do I return something that allows other functions to do something *after* my handler? You would do that like this:
```scala
def myPersist[T, U](evtIn:T)(handler:T => U):RequestM[U] = {
  val rm = RequestM.prep[U]
  persist(evtIn) { evt =>
    val result = handler(evt)
    rm.resolve(Success(result))
  }
  rm
}
```
This persists the event as usual, but returns a `RequestM` that can be mapped and flatMapped, and those mappings will execute immediately after the handler executes. That way, you can build composable flows. (This is especially useful in Akka Persistence when you have multiple commands that can result in the same event, but want somewhat different responses afterwards.)

It's reasonable to ask, "So why not just use `Promise` and `Future` here?" That's entirely an option. Using `prep`/`resolve` has two advantages:
* Since it is using a RequestM, it combines well with other RequestM-based code, and doesn't require polluting your Actor with potentially dangerous Futures.
* Any additional functions that are mapped onto the returned `RequestM` are guaranteed to be executed synchronously in the same thread where `resolve` was called, so the threading is safe and predictable.

It is your responsibility to only call `resolve` in an "Actor-safe" thread -- during a `receive`, a `persist` handler, or some other safely single-threaded point.

Note that `resolve` takes a `Try[T]` -- you can use it to resolve either `Success` or `Failure`.

`resolve` is the heart of Requester, and is used internally to complete requests -- the conventional Requester workflow is to send an `ask`, loop the response back into the requester's `receive` function, and pass that response into `resolve`. Similarly, `RequestM.successful()` and `.failure()` simply create a `RequestM` and immediately call `resolve` on it. Thus, you can use `resolve` to build other, high-level constructs on top of `RequestM`.

### Caveats

Because of the loopback, request necessarily increases the latency of processing a request. This increase is typically slight (since it sends a message locally to the same Actor), but in a heavily-loaded Actor it could become non-trivial.

Requester is powerful, and brings you back into the land of Akka sanity, but it isn't a panacea. In particular, remember that your `request` response handler will *always* be run asynchronously, in a later run through receive. The state of your Actor may well have changed since you sent your message -- be sure to keep that in mind when you are writing your response handler.

Also, for the same reasons, using Requester with frequent `become` operations or with FSM is pretty fraught. While it isn't necessarily incompatible, I recommend using caution if you are trying to combine these concepts. (This is no different from usual, though: FSM always requires care and thought about what you want to have happen when an obsolete request comes back.)

While Requester is being used heavily in production at Querki, nobody else has used it as of this writing. Please give a yell if you come across bugs, and pull requests are welcomed.

### To Do

Requester clearly ought to pair well with [Typed Actors](http://doc.akka.io/docs/akka/2.3.9/scala/typed-actors.html), but some surgery will be needed. (Unless Typed Actors do this loopback automatically under the hood, in which case Requester isn't necessary.) Basically, we need to extend Requester to have a straightforward way to interpret any Future-producing function (not just ask) as a RequestM, automatically sussing the type that is implicit in the Future, and looping it back as normal. In principle this isn't difficult, but we need to think about how to minimize the boilerplate.

One possibility for the above: create a new implicit ExecutionContext, available on any Requester, which executes *all* Futures as loopbacks. In principle this seems like it would work in the general case, and would be an enormous win -- if we can do that, then RequestM might be able to go away, and you could simply do ordinary Future-based programming that would work properly. This is the ideal case, but needs more research to figure out if it is actually possible.

At the moment, the timeout for requests is built into Requester as a member, instead of being an implicit to functions the way Futures usually work. This is very convenient, but I worry that it's too coarse-grained. We should think about whether it needs to be changed.

I am pretty sure that withFilter() doesn't do the right thing yet. It needs to be adjusted so that its behavior matches that of Future.

More unit tests are needed, especially around failure management.

### Change log

* **2.4** -- Added support for `prep()` and `resolve()`. `resolve` has always been there -- it's central to the system -- but had previously been private. It is now opened up, to allow `Promise`-like code.

* **2.1** -- If a Request is being auto-converted to a Future, Exceptions now propagate from the Request to the Future. request() and requestFor() now work with ActorSelection as well as ActorRef. Fixed the unwinding of nested flatMaps to work tail-recursively. (Previously, if you nested a *lot* of flatMaps together, they could throw a StackOverflow while unwinding at the end.)

* **2.0** -- Improved RequestM to make it compose properly, so you can mostly treat it as you expect from Futures. Added onComplete, so you can handle failures. Added an implicit to convert RequestM[T] to Future[T], which makes interoperability with Futures much easier, and removed the clunky requestFuture mechanism. unhandled() now deals with loopbacks, so you can usually just mix Requester in with no other changes and have it work. Added ? as a syntax for request, specifically to help prevent accidentally mixing the unsafe ask into a Requester.

### License

Copyright (c) 2015 Querki Inc. (justin at querki dot net)

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
