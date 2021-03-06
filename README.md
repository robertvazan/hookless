[![Maven Central](https://img.shields.io/maven-central/v/com.machinezoo.hookless/hookless)](https://search.maven.org/artifact/com.machinezoo.hookless/hookless)
[![Build Status](https://travis-ci.com/robertvazan/hookless.svg?branch=master)](https://travis-ci.com/robertvazan/hookless)
[![Coverage Status](https://codecov.io/gh/robertvazan/hookless/branch/master/graph/badge.svg)](https://codecov.io/gh/robertvazan/hookless)

# Hookless #

Hookless is a [reactive programming](https://en.wikipedia.org/wiki/Reactive_programming) library for Java. It automatically runs dependent reactive computations whenever change is detected in any of their dependencies. It is nearly completely transparent to application code, because it tracks dependencies implicitly via thread-local context object.

## Status ##

Class-level progress is tracked using [Stagean annotations](https://stagean.machinezoo.com/). Core classes are stable, including APIs, but poorly documented.

## Download ##

Hookless is available from [Maven Central](https://search.maven.org/artifact/com.machinezoo.hookless/hookless). Further setup instructions are on the [website](https://hookless.machinezoo.com/). Hookless is distributed under [Apache License 2.0](LICENSE).

## Documentation ##

You can use [javadoc](https://hookless.machinezoo.com/javadocs/core/overview-summary.html) for reference, but most classes are not documented yet. There are however extensive comments in [source code](src/main/java/com/machinezoo/hookless). [Website](https://hookless.machinezoo.com/) contains additional information, including [conceptual overview](https://hookless.machinezoo.com/concepts) and a list of [reactive adapters](https://hookless.machinezoo.com/adapters).

## Contribute ##

Bug reports, feature suggestions, and pull requests are welcome. For major changes, open an issue first to discuss the change.

* Sources: [GitHub](https://github.com/robertvazan/hookless), [Bitbucket](https://bitbucket.org/robertvazan/hookless)
* Issues: [GitHub](https://github.com/robertvazan/hookless/issues), [Bitbucket](https://bitbucket.org/robertvazan/hookless/issues)
* License: [Apache License 2.0](LICENSE)

