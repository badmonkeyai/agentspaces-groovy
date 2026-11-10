# The fleet DSL

Written for: Groovy developers who build with agentspaces-groovy.

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

Every form takes a second, optional block over the runtime's own options,
which delegates to `FleetRuntime.Builder`: `participant` (a `FleetParticipant`
started after the node ticks and stopped on close), `clock`, `scheduler`,
`embedder`, `schemaRegistry`, and `identity`. In a script, `fleet({ }) { }`
is the same pair.

```groovy
def runtime = Fleet.start({
    bind '127.0.0.1:7591'
    group('research') { seeds '127.0.0.1:7590'; space 'tasks' }
}) {
    participant modelServer                 // FleetRuntime.Builder.participant
    clock InstantSource.system()            // FleetRuntime.Builder.clock
}
```

The runtime builds the peer from the Spring Boot starter's own build methods
with no Spring context, so three starter features have no hook in the DSL.
`capabilities { orderedLog() }` is a keyword the starter refuses
(`ordered-log=true`), because the ordered log runs over a fixed member set
the starter cannot infer; the hand-wired form is `OrderedTakes.over(...)` and
`group.ordered(name, ordered)`, as `AnnotationPassthroughTest.wireOrdered`
shows. `console { enabled }` and `a2a { enabled }` are keywords `Fleet.start`
refuses, naming the starter: the runtime does not serve the fleet console or
the A2A gateway, the starter's auto-configurations do. `BeaconLocator` beans
and `PeerNodeCustomizer`s are Spring beans with no runtime equivalent; the
runtime joins with each group's seeds, multicast when enabled, and the default
bootstrap chain. `capabilities { gossipLearn() }` gives the same learner the
starter builds, pure averaging with no metrics space.
