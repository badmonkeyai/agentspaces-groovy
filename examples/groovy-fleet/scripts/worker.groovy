// Example 21: a one-file Groovy peer on example 02's research fleet.
//
//   groovy worker.groovy [port] [seed-host:port]
//
// @Grab fetches the library (pin the version; a production peer is a built jar, see the README).
@Grab('ai.badmonkey.agentspaces:agentspaces-groovy:0.3.0')
@Grab('ai.badmonkey.agentspaces:example-02-research-fleet:0.3.0')
import ai.badmonkey.agentspaces.examples.fleet.ResearchFleet.ResearchTask
import ai.badmonkey.agentspaces.examples.fleet.ResearchFleet.Finding
@groovy.transform.BaseScript ai.badmonkey.agentspaces.groovy.FleetScript _

int port = args ? (args[0] as int) : 7591
String seed = args.size() > 1 ? args[1] : '127.0.0.1:7590'

fleet {
    bind "127.0.0.1:$port"
    tick '250ms'
    security { profile 'dev-local' }        // example 02's peers speak plain TCP; the starter's default is mtls
    group('research-fleet') {
        founding 'research-fleet-demo-v1'      // the same bytes example 02 founds from, so the GroupId agrees
        seeds seed
        space('tasks') { settleWindow '150ms' }
    }
}

bind agent('groovy-summarizer', description: 'Summarizes research tasks from a one-file Groovy peer', goals: ['research']) {
    take(ResearchTask, space: 'tasks', lease: '30s', pollTimeout: '500ms', returns: Finding) { ResearchTask task ->
        new Finding(task.topic(), "researched: ${task.topic()} (groovy)", 'groovy')
    }
}

println "groovy worker on 127.0.0.1:$port, seeded from $seed; Ctrl-C to stop"
serve()
