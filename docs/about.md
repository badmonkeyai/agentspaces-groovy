# About agentspaces-groovy

Written for: Groovy developers who build with agentspaces-groovy.

**A peer-to-peer coordination fabric for agents, services, and the people who work with them.**

AgentSpaces is a P2P framework to quickly discover and join P2P networks, where agents, software services, and people share assets, queries, results, and coordinate. The framework helps you build fleets of agents, tie in other software, and coordinate across them with a replicated tuple space, quorum building, and more. Agents post work, claim tasks, publish results, vote on decisions, and recover from each other's failures. There is no broker, no registry, no scheduler, and no server to keep alive. Coordination is peer-to-peer, secured end to end, and replicated across the fleet.

## About agentspaces-groovy

AgentSpaces for Apache Groovy 6: the shared replicated tuple space
and its peer-to-peer fabric in Groovy's own idiom. A Groovy class carrying the Java annotations already
binds as a worker; this project builds on that floor. Let's use Groovy to quickly
build, leverage, and interact with our P2P agent collaboration space!

| Artifact | What it is |
|---|---|
| `agentspaces-groovy` | GDK-style extension methods on the Space API and the capability clients; coercions (`'10m' as Lease`, `Task.from(map)`, `record.toMap()`); the `where:` template DSL; `Fleet.start { }`, the fleet configuration DSL whose keywords are the starter's properties; `agent(name) { }`, the agent DSL bound by the Java binder through a generated interface; `FleetScript`, so one `.groovy` file is a peer; `await`-ready twins of every blocking verb; `FleetChecker`, a type-checking extension that turns bind-time refusals into compile errors; `FleetShell`, a `groovysh` session over a running peer. Depends on stable Groovy 6 features only. |
| `agentspaces-groovy-concurrent` | Channels over space subscriptions (`for await`), broadcast, select recipes, and dataflow twins over Groovy 6's `groovy.concurrent` toolkit, which Groovy marks **incubating**. Every use of that package lives here. |

```xml
<dependency>
  <groupId>ai.badmonkey.agentspaces</groupId>
  <artifactId>agentspaces-groovy</artifactId>
  <version>0.6.0</version>
</dependency>
```

```groovy
// build.gradle
dependencies {
    implementation 'ai.badmonkey.agentspaces:agentspaces-groovy:0.6.0'
    implementation 'org.apache.groovy:groovy:6.0.0'
}
```

Groovy 6.0.0 (general availability 2026-09-19) on JDK 21 or newer; the
artifacts are Java 21 bytecode.
