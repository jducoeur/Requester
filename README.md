# Requester

One of the most useful, and most fraught, functions in Akka is `ask`. On the one hand, it is invaluable for
writing clear, understandable code -- after all, the pattern of "Send this message, and then do that with the result"
makes loads of sense, and it's usually easy to see what the code is trying to do.

On the other hand, `ask` is something of a landmine in practice, because it violates the most important
invariant of Actors: there should never be code lexically inside of the Actor that doesn't run within the
Actor's receive loop. `ask` returns a Future, and that Future will be run out-of-band, at some random time
in the future. It can happen in parallel with running receive code, or not. There is lots of danger there,
and it is easy to cause erratic, hard-to-reproduce bugs.

(Not to mention the fact that `sender` probably won't be set to the value you expect during the response handler.
One of the easiest Akka traps to fall into is using `sender` during a Future, which almost never works.)

This library introduces `request`, which you can think of as the better-behaved big brother of `ask`. The look
and feel is similar, but there is one crucial difference: the response handler from `request` is *not* Future,
and it runs inside the normal receive loop. Also, unlike `ask`, `request` preserves the value of `sender`. The
result is that you can write straightforward, intuitive, composable code for complex multi-Actor operations, like this:
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


### License

Copyright (c) 2015 Querki Inc. (justin at querki dot net)

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
