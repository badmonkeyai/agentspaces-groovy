# The shell

Written for: Groovy developers who build with agentspaces-groovy.

```
java -cp ... ai.badmonkey.agentspaces.groovy.FleetShell --yaml agentspaces.yaml
java -cp ... ai.badmonkey.agentspaces.groovy.FleetShell -e "bind '127.0.0.1:7591'; security { profile 'dev-local' }; group('g') { seeds '127.0.0.1:7590'; space 'tasks' }"
fleet> space('tasks').all(ResearchTask).size()
fleet> space('tasks') << new ResearchTask('agentic memory', 3)
```

`fleet`, `spaces`, `space(name)`, `group(name)`, `agent(name) { }`, and
`bind(agent)` are bound; the shell runs as the configured identity and refuses
a configuration that enables the console or the A2A gateway, so it never
serves (and the runtime it starts could not serve them in any case; the Spring
Boot starter does); closing it closes the peer. Needs
`org.apache.groovy:groovy-groovysh` (an optional dependency).
