# A one-file peer

Written for: Groovy developers who build with agentspaces-groovy.

```groovy
@Grab('ai.badmonkey.agentspaces:agentspaces-groovy:0.6.0')
@Grab('ai.badmonkey.agentspaces:example-02-research-fleet:0.6.0')   // the two records below; unpublished, see the note
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
a repository serves. The second `@Grab` names `example-02-research-fleet`,
the core reactor's example module that declares `ResearchTask` and `Finding`;
that artifact is not published to Maven Central, so install it from a checkout
of the core (`mvn -q install -DskipTests` in `agentspaces/`) or replace the two
imports with records of your own, as the quick start above does.
`examples/groovy-fleet/scripts/worker.groovy` is this script in full.
