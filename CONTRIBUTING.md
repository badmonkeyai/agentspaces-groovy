# Contributing to AgentSpaces Groovy

Thank you for your interest in contributing. AgentSpaces Groovy is an
Apache 2.0 project from Bad Monkey, Inc., and everything in this repository
stays Apache 2.0.

This repository holds the Groovy 6 bindings: extension methods, the fleet and
agent DSLs, the script base class, the async twins, the checker, the shell,
and the incubating channel bridge. The coordination fabric itself lives in the
[AgentSpaces core repository](https://github.com/badmonkeyai/agentspaces); the
plain-Java runtime this project starts peers through lives in
[agentspaces-langchain4j](https://github.com/badmonkeyai/agentspaces-langchain4j).

## Before you start

- **Questions and ideas** are welcome as [GitHub issues](https://github.com/badmonkeyai/agentspaces-groovy/issues).
- **Security issues** must not be filed as public issues. See
  [SECURITY.md](SECURITY.md) for private reporting.
- **Code of conduct:** by participating you agree to the
  [code of conduct](CODE_OF_CONDUCT.md).

## The rules a change must keep

- **The Java binder binds; Groovy describes.** An extension calls one Java
  method; the agent DSL emits an interface the binder reads; the fleet DSL
  emits the tree the runtime's loader binds. A feature that needs new binder
  behaviour is a ticket against the core.
- **Mirror the Java names.** DSL keywords are the starter's property names,
  clause options the annotations' attribute names, matcher functions the
  `Matchers` statics (`anyOf` for `in`). `DslParityTest` and `NameParityTest`
  keep it so.
- **Stable on stable.** `agentspaces-groovy` depends on stable Groovy 6
  features only; anything that imports `groovy.concurrent` belongs in
  `agentspaces-groovy-concurrent`, and `BridgeTest` checks the core jar.

## Building and testing

```bash
mvn -B -ntp verify                 # library and bridge
mvn -B -ntp verify -Pexamples      # with example 21 (needs example-02-research-fleet 0.4.0 in the local repository)
mvn -B -ntp verify -Phosts         # with the Spring Boot and Micronaut passthroughs
ci/grab-check.sh                   # after mvn install: the @Grab script under an installed Groovy 6
```

Tests are JUnit Jupiter written in Groovy, over real peers on loopback TCP.
Name a test for the sentence it proves, give every fleet test a `@Timeout`,
and keep each test to its own space or topic when a class shares one runtime.

## Pull requests

Open an issue first for anything beyond a fix. Keep the change small, add the
test that fails without it, and sign off your commits (DCO).
