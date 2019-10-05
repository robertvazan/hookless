# Hookless #

Hookless is a [reactive programming](https://en.wikipedia.org/wiki/Reactive_programming) library for Java. It automatically updates dependent reactive values whenever change is detected in any of their dependencies, which can be reactive variables or other reactive values. The point of reactive programming is to avoid the many problems with traditional callbacks (managing subscriptions, event rates, memory leaks, ...).

There are quite a few reactive libraries for Java these days, so let's say right at the beginning what makes Hookless different:

* Hookless is all **pure Java** with no special bytecode modifications or JVM instrumentation (contrary to projects like [Quasar](http://docs.paralleluniverse.co/quasar/)).
* Hookless is based on **invalidate-then-refresh** pattern (like [React](https://reactjs.org/) or [Meteor](https://www.meteor.com/) in JavaScript or [Assisticant](https://assisticant.net/) in .NET) as opposed to event streams (and functional reactive programming) used in some popular reactive programming libraries for Java ([RxJava](https://github.com/ReactiveX/RxJava), [Reactor](https://projectreactor.io/)).
* Invalidate-then-refresh pattern allows Hookless to have highly **dynamic** dependency lists, because dependencies are collected during every recomputation of the reactive value instead of being specified upfront.
* Invalidate-then-refresh pattern enables **implicit** dependency tracking (like in [Meteor](https://www.meteor.com/) or [Assisticant](https://assisticant.net/)). Hookless-based code is mostly just plain Java code except that reactive dependencies are transparently captured in a thread-local context object. Hookless relies on thin libraries that wrap numerous existing high-level non-blocking APIs instead of trying to intercept low-level blocking operations (like [Quasar](http://docs.paralleluniverse.co/quasar/)).
* Hookless lets you define a number of **local** reactive values that refresh independently (like in [Meteor](https://www.meteor.com/) or [Assisticant](https://assisticant.net/)) as opposed to single global state that would refresh all at once (like in [React](https://reactjs.org/)).
* Dependency graph in Hookless is **garbage-collected**. Hookless uses weak references (like [Assisticant](https://assisticant.net/)) to make whole sections of the dependency graph collectable once nothing needs them instead of relying on explicit teardown (like [Meteor](https://www.meteor.com/)).
* In order to protect programs from hot reactive variables, invalidated reactive values are refreshed **asynchronously** and concurrently on a thread pool instead of being refreshed immediately after reactive variable change (like in [Assisticant](https://assisticant.net/) or [Meteor](https://www.meteor.com/)).
* Hookless has efficient high-resolution reactive time implemented via semi-explicit **time algebra** (like in [Assisticant](https://assisticant.net/). Other reactive libraries usually just provide either fully explicit timers or a low-resolution global tick.

* Documentation: [Website](https://hookless.machinezoo.com/)
* Sources: [GitHub](https://github.com/robertvazan/hookless), [Bitbucket](https://bitbucket.org/robertvazan/hookless)
* Issues: [GitHub](https://github.com/robertvazan/hookless/issues), [Bitbucket](https://bitbucket.org/robertvazan/hookless/issues)
* License: [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)

