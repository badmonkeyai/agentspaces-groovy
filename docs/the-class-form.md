# The class form

Written for: Groovy developers who build with agentspaces-groovy.

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
`@Ballot` that returns anything but `String`, or `@Memoized` or `@ActiveMethod`
beside a verb fail at compile time with the binder's own words.

Prefer the class form for anything that ships in a jar or runs under a host; the DSL for scripts, tests, and
agents assembled at run time. Both publish the same card.
