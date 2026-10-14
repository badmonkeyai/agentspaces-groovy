# agentspaces-groovy

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
  <version>0.3.0</version>
</dependency>
```

```groovy
// build.gradle
dependencies {
    implementation 'ai.badmonkey.agentspaces:agentspaces-groovy:0.3.0'
    implementation 'org.apache.groovy:groovy:6.0.0'
}
```

Groovy 6.0.0 (general availability 2026-09-19) on JDK 21 or newer; the
artifacts are Java 21 bytecode. 

## A one-file peer

```groovy
@Grab('ai.badmonkey.agentspaces:agentspaces-groovy:0.3.0')
import ai.badmonkey.agentspaces.examples.fleet.ResearchFleet.ResearchTask
import ai.badmonkey.agentspaces.examples.fleet.ResearchFleet.Finding
@groovy.transform.BaseScript ai.badmonkey.agentspaces.groovy.FleetScript _

fleet {
    bind '127.0.0.1:7591'                       // agentspaces.bind
    security { profile 'dev-local' }            // agentspaces.security.profile
    group('research-fleet') {                   // agentspaces.groups[0]
        founding 'research-fleet-demo-v1'
        seeds '127.0.0.1:7590'
        space 'tasks'
    }
}

bind agent('summarizer', description: 'Summarizes research tasks') {
    take(ResearchTask, space: 'tasks', lease: '30s', returns: Finding) { ResearchTask t ->
        new Finding(t.topic(), "summary of ${t.topic()}", 'groovy')
    }
}

serve()                                         // until Ctrl-C or stop()
```

`groovy worker.groovy` is a peer. `fleet { }` starts it, `agent { }` describes
a worker as closures, `bind` hands it to the Java binder, and `serve()` blocks
until SIGINT or `stop()`; when the script body ends, the agents are unbound and
the peer closes (an in-flight take then lapses at its lease, the binder's crash
idiom). `@Grab` is a development convenience: pin the version, and ship a built
jar or a Maven or Gradle project in production, because a script runs whatever
a repository serves.

## The class form

```groovy
@CompileStatic(extensions = 'ai.badmonkey.agentspaces.groovy.FleetChecker')
@AgentSpec(name = 'pricer', description = 'Prices an order', goals = ['price orders'])
class Pricer {
    @SpaceTake(space = 'tasks', lease = '10m', where = ['priority=1'])
    Quote price(PriceRequest r) { new Quote(r.item(), r.quantity(), 4.25d * r.quantity()) }

    @Ballot(space = 'votes')
    String judge(VoteCapability.Proposal p) { p.question().contains('ship') ? 'approve' : null }
}

def runtime = Fleet.start(Path.of('agentspaces.yaml'))
runtime.bind(new Pricer())
```

The Java annotations on a Groovy class, dynamic or `@CompileStatic`, bind
through `FleetRuntime.bind`, `AgentSpaces.bind`, the Spring Boot starter, or
the Micronaut library unchanged. With `FleetChecker` enabled, a `where` entry
that names no component of the cue record, a lease that does not parse, a
`@Ballot` that returns anything but `String`, or `@Memoized` beside a verb fail
at compile time with the binder's own words. 

Prefer the class form for anything that ships in a jar or runs under a host; the DSL for scripts, tests, and
agents assembled at run time. Both publish the same card.


## The Space API, in Groovy

```groovy
import static ai.badmonkey.agentspaces.groovy.Where.*

space << entry                                        // write under the space's default lease (10m; space.defaultLease = '2h')
space.write(entry, '10m')                             // or lease: '10m', tags: [region: 'eu']
space.read(Task, where: [priority: gte(2), topic: anyOf('a', 'b')], tags: [region: 'eu'])
space.read(Task) { priority > 2 }                     // a filter over the decoded entry, judged on this replica
space.all(Task, limit: 50)                            // an unmodifiable List: GINQ-ready; toSorted, never sort in place
space.entries(Task, tags: [shard: present()])         // with id, issuer, tags, lease, stamp
space.take(Task, lease: '10m', timeout: '2s')         // Optional<TakenEntry<Task>>
space.take(Task, lease: '10m') { Task t -> new Quote(...) }   // take, run, complete with the result; null completes without one; a throw lets the lease lapse
space.use(Task, lease: '10m') { TakenEntry<Task> taken -> ... }  // take, run, complete without a result
space.notify(Task, lease: '1h', on: 'written') { SpaceEvent e -> ... }   // Subscription
votes.propose(id: 'm-1', question: 'ship?', options: ['yes', 'no'], quorum: 2); votes.cast('m-1', 'yes')
aggregate.settled('epoch', ticks: 20, tolerance: 0.01)
```

`where:` takes a plain value (`eq`), a matcher function (`eq`, `ne`, `gt`,
`gte`, `lt`, `lte`, `anyOf` for the Java `in`, `contains`, `isNull`, `notNull`,
`present` on tags), or a closure over the field. The filter closure of
`read`, `all`, `entries`, and `notify` runs after the template matched, on
this replica only; a take claim is judged by the fabric's template, so `take`
takes `where:` and `tags:` and no filter closure.

## Async

Every blocking verb and client wait has a twin returning a
`CompletableFuture` on a virtual thread, which Groovy 6's `await` takes
unchanged. `await` binds looser than member access: write `(await x).size()`
or assign first.

```groovy
Optional<Task> first = await space.readAsync(Task, where: [topic: 'x'])
def (quote, risk) = await(quotes.takeAsync(Quote, lease: '1m'), risk.readAsync(RiskScore, timeout: '5s'))
def decision = await votes.decisionAsync('m-1')
def estimate = await aggregate.settledAsync('epoch', ticks: 20, tolerance: 0.01)

AsyncScope.withScope { scope ->                       // inside a bound method: the binder runs it on a virtual thread
    def q = scope.async { await quotes.takeAsync(Quote, lease: '1m', timeout: '5s') }
    def r = scope.async { await risk.readAsync(RiskScore, timeout: '5s') }
    new Order((await q).get(), (await r).get())
}
```

A cancelled twin's blocking call still runs to its own timeout (the binder
renews the take lease meanwhile), so keep fan-out timeouts short.

With `agentspaces-groovy-concurrent`:

```groovy
for await (e in space.channel(Finding, lease: '1h', buffer: 64, on: 'written')) { println e.entry().summary() }
def fan = space.broadcast(Task); def a = fan.subscribe(); def b = fan.subscribe()
def first = Selects.firstOf('2s', left.channel(Task), right.channel(Task)).join()   // first.index, first.value, first.timeout
def decided = votes.decisionVar('m-1')                 // a DataflowVariable bound when the decision lands
```

A channel is fed by one subscription; closing either side closes both, and a
channel closes itself when its lease would lapse unless `renew`ed. A full
buffer blocks the subscription's listener: a slow consumer slows this peer's
event delivery and leaves the fabric alone.

## The fleet DSL

Every keyword is the camel-case name of a starter property under
`agentspaces.*`; a block is a nested group; a repeated keyword appends to a
list; a boolean keyword with no argument means true. Two aliases, and no other
sugar: a millis property takes a duration string under its name without the
suffix (`tick '250ms'` for `tickMillis`), and the singular of a list property
adds one element (`group('research') { }`, `space 'tasks'`). The DSL emits the
kebab-case tree the runtime's loader binds, so an unknown keyword fails naming
the level, the nearest keywords, and the known ones, and the DSL cannot drift
from the starter (a reflective test walks every property). Script variables
and methods resolve inside a block; a local named like a keyword shadows it,
as Groovy scoping has it. `Fleet.start(Path)`, `Fleet.start(String yaml)`, and
`Fleet.start(Map)` take the other forms.

## The shell

```
java -cp ... ai.badmonkey.agentspaces.groovy.FleetShell --yaml agentspaces.yaml
java -cp ... ai.badmonkey.agentspaces.groovy.FleetShell -e "bind '127.0.0.1:7591'; security { profile 'dev-local' }; group('g') { seeds '127.0.0.1:7590'; space 'tasks' }"
fleet> space('tasks').all(ResearchTask).size()
fleet> space('tasks') << new ResearchTask('agentic memory', 3)
```

`fleet`, `spaces`, `space(name)`, `group(name)`, `agent(name) { }`, and
`bind(agent)` are bound; the shell runs as the configured identity and refuses
a configuration that enables the console or the A2A gateway, so it never
serves; closing it closes the peer. Needs `org.apache.groovy:groovy-groovysh`
(an optional dependency).

## Groovy constructs that meet the binder

| Construct | What happens |
|---|---|
| `@CompileStatic` or dynamic class with the Java annotations | binds alike |
| Groovy `record` | a Java record: the entry type a Java peer names the same way (recommended) |
| `@Immutable` class | round-trips through the codec via the generated no-argument constructor; records stay the recommendation |
| a map coerced to an annotated interface (`[price: { }] as Pricer`) | binds: the binder reads annotations through interfaces; the agent DSL stands on this |
| a closure property | not a verb; a class that is only closures binds nothing |
| a trait with an annotated method | binds through the implementing class |
| `@Delegate` to an annotated interface | the class implements the interface, so the verb binds |
| `@Memoized` on a verb | binds, and answers a repeated equal cue from its cache; the checker refuses it |
| `@ActiveMethod` on a verb | returns before the work runs, breaking "the return is the next entry"; the checker refuses it |
| a Java map in a GString or string concatenation | renders Groovy-style (`[k:v]`); `String.valueOf(map)` gives Java's form |
| `'10m' as Duration`, `'10m' as Lease` | the extension module overrides Groovy's own `asType(String, Class)` for these two targets |

## Building

```bash
mvn -B -ntp verify                         # the library and the bridge: 63 and 7 tests
mvn -B -ntp verify -Pexamples              # plus example 21 (examples/groovy-fleet), on example 02's fleet
mvn -B -ntp verify -Phosts                 # plus the Spring Boot and Micronaut passthroughs
ci/grab-check.sh                           # the @Grab script against an installed Groovy 6 and the local repository
```

Maven with `gmavenplus-plugin` 5.1.0 compiles the Groovy sources; JUnit
Jupiter runs the tests (no Spock build exists for Groovy 6). The enforcer keeps
Spring, Micronaut, LangChain4j, and `groovy-concurrent-java` off the library's
classpath. The incubating Groovy features this project touches: the
`groovy.concurrent` toolkit (the bridge artifact), `groovy-csv` and
`groovy-markdown` (example 21 only), and Grape's dual engine (the `@Grab`
recipe); a Groovy minor that changes them touches those places and nothing else.
