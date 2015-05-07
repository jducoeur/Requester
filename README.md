# Requester

One of the most useful, and most fraught, functions in Akka is `ask`. On the one hand, it is invaluable for writing clear, understandable code -- after all, the pattern of "Send this message, and then do that with the result" makes loads of sense, and it's usually easy to see what the code is trying to do.

On the other hand, `ask` is something of a landmine in practice, because it violates the most important invariant of Actors: there should never be code lexically inside of the Actor that doesn't run within the Actor's receive loop. `ask` returns a Future, and that Future will be run out-of-band, at some random time in the future. It can happen in parallel with running receive code, or not. There is lots of danger there, and it is easy to cause erratic, hard-to-reproduce bugs.

(Not to mention the fact that `sender` probably won't be set to the value you expect during the response handler. One of the easiest Akka traps to fall into is using `sender` during a Future, which almost never works.)

This library introduces `request`, which you can think of as the better-behaved big brother of `ask`. The look and feel is similar, but there is one crucial difference: the response handler from `request` is *not* Future, and it runs inside the normal receive loop. Also, unlike `ask`, `request` preserves the value of `sender`. The result is that you can safely write straightforward, intuitive, composable code for complex multi-Actor operations, like this:
```
case GetSpacesStatus(requester) => {
  for {
    ActiveThings(nConvs) <- conversations.request(GetActiveThings)
    ActiveSessions(nSessions) <- sessions.request(GetActiveSessions)
  }
    sender ! SpaceStatus(spaceId, state.displayName, nConvs, nSessions)
}
```
and have it work just as you expect.

### Installing Requester

To use Requester, add this to your libraryDependencies in sbt:
```
"org.querki" %% "requester" % "1.0"
```

### Using Requester

The most common and straightforward use case for Requester is when you have one Actor that wants to make requests of others. You enhance the *requesting* Actor (not the target!) with the Requester trait, and add handleRequestResponse to its receive function:
```
import org.querki.requester._

class MyActor extends Actor with Requester {
  def receive = handleRequestResponse orElse {
    ... Your normal receive code...
  }
}
```
Once you've done that, you can write code like the example:
```
case GetSpacesStatus(requester) => {
  for {
    ActiveThings(nConvs) <- conversations.request(GetActiveThings)
    ActiveSessions(nSessions) <- sessions.request(GetActiveSessions)
  }
    sender ! SpaceStatus(spaceId, state.displayName, nConvs, nSessions)
}
```
(All examples are real code from [Querki](https://www.querki.net/), sometimes a bit simplified.)

Or for a simpler example that doesn't need to compose, just use `foreach`:
```
persister.request(LoadCommentsFor(thingId, state)) foreach {
  case AllCommentsFor(_, comments) => {
    // Race condition check: some other request might have loaded this Thing's conversations while
    // we were in the roundtrip. In that case, the already-existing copy is authoritative, because it
    // might have mutated. (In other words, don't keep chasing the race round and round.)
    val convs = loadedConversations.get(thingId) match {
      case Some(newCs) => newCs
      case None => {
        val cs = buildConversations(comments)
        loadedConversations += (thingId -> cs)
        cs
      }
    }
    f(convs)
  }
}
```
`request` returns a RequestM, which is more or less a monad -- it's actually slightly mutable, so don't assume perfectly-monadic behavior, but it works as expected inside a for comprehension, and deliberately mimics the core behavior of Future.

### How it works

`request` actually uses `ask` under the hood, but in a very precise and constrained way. It sends your message to the target Actor and gets the response via `ask`. However, it then loops that response (plus the handler) back as a message to this Actor. That is why you need to add `handleRequestResponse` to your receive handler -- this intercepts these loopbacks, and executes them in your main receive loop.

### `request` and Futures

In ordinary Akka, the above is usually enough: you usually use the results of your request-based computation to set local state in the Requester, or to send a response, typically back to `sender`. Occasionally, though, you may want to wrap the whole thing up into a Future -- this is particularly common when you are writing client/server RPC code, using Scala.js on the front end, [Autowire](https://github.com/lihaoyi/autowire) for the API communication, and Akka Actors implementing the back-end server implementation.

For a case like this, you want to use the `requestFuture` adapter:
```
def doChangeProps(thing:Thing, props:PropMap):Future[PropertyChangeResponse] = {
  requestFuture[PropertyChangeResponse] { implicit promise =>
    self.request(createSelfRequest(ChangeProps2(thing.toThingId, props))) foreach {
      case ThingFound(_, _) => promise.success(PropertyChanged)
      case ThingError(ex, _) => promise.failure(new querki.api.GeneralChangeFailure("Error during save"))
    } 
  }
}
```
`requestFuture` is mostly just an optional bit of sugar, but it is very convenient for circumstances like this. `request` accepts an implicit Promise[_], which is created by `requestFuture`. (Note that the `promise` parameter must be marked implicit for things to work right.) If it finds that implicit Promise, then any thrown exceptions will be routed into it, so that the resulting Future will return Failed(exception), as you would expect.

### `requestFor`

The ordinary `request` call returns Any, as is usual in Akka. Sometimes, it is clearer to be able to state upfront what type you expect to receive, especially if there is only one "right" answer. `requestFor` is a variant of `request` that allows you to state this:
```
notePersister.requestFor[CurrentNotifications](Load) foreach { notes =>
  currentNotes = notes.notes.sortBy(_.id).reverse
	    
  // Okay, we're ready to roll:
  self ! InitComplete
}
```
This makes the whole thing more strongly-typed upfront -- in the above code, the compiler knows that `notes` is a `CurrentNotifications` message. If anything other than `CurrentNotifications` is received, it will throw a `ClassCastException`.

### RequesterImplicits

The above is all fine so long as you are sending your requests from directly inside the Requester. But what if your structure is more complex -- say, you have your handlers broken up into a number of classes instantiated by and running under the Requester? (For example, in Querki, we have a single primary UserSpaceSession Actor that mediates all normal client/server requests. Each request then causes an appropriate handler object to be created, running under that Actor's receive loop, which actually deals with the functions in question.)

If you want one of these outside classes to be able to use `request`, then you should have it inherit from the RequesterImplicits trait, and override that trait's `requester` member to point to the controlling Requester Actor.

RequesterImplicits actually defines `request`, `requestFor` and `requestFuture`; it delegates the actual processing to the associated Requester. (Requester itself implements RequesterImplicits, so you can just ignore this for the ordinary case.)

### Caveats

Because of the loopback, request necessarily increases the latency of processing a request. This increase is typically slight (since it sends a message locally to the same Actor), but in a heavily-loaded Actor it could become non-trivial.

Requester is powerful, and brings you back into the land of Akka sanity, but it isn't a panacea. In particular, remember that your `request` response handler will *always* be run asynchronously, in a later run through receive. The state of your Actor may well have changed since you sent your message -- be sure to keep that in mind when you are writing your response handler.

Also, for the same reasons, using Requester with frequent `become` operations or with FSM is pretty fraught. While it isn't necessarily incompatible, I recommend using extreme caution if you are trying to combine these concepts. (This is no different from usual, though: FSM always requires care and thought about what you want to have happen when an obsolete request comes back.)

There are no unit tests yet. This needs to be rectified.

While Requester is being used heavily in production at Querki, nobody else has used it as of this writing. Please give a yell if you come across bugs, and pull requests are welcomed.

### To Do

Requester clearly ought to pair well with [Typed Actors](http://doc.akka.io/docs/akka/2.3.9/scala/typed-actors.html), but some surgery will be needed. Basically, we need to extend Requester to have a straightforward way to interpret any Future-producing function (not just ask) as a RequestM, automatically sussing the type that is implicit in the Future, and looping it back as normal. In principle this isn't difficult, but we need to think about how to minimize the boilerplate.

### License

Copyright (c) 2015 Querki Inc. (justin at querki dot net)

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
