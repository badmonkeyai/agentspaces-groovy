# agentspaces-groovy documents

Written for: anyone who read the README and wants the rest of the story.

The README is short by design: what this is, the quick start, and how to add it to a build. Everything else lives here, one topic per document.

| Document | Written for | What it holds |
|---|---|---|
| [About agentspaces-groovy](about.md) | Groovy developers who build with agentspaces-groovy | What AgentSpaces is, and what this project adds to it. |
| [How to use AI with AgentSpaces](how-to-use-ai.md) | Groovy developers who build with agentspaces-groovy | Tested walk-throughs that build an agent, hand it work from another peer, and survive a crash. |
| [A one-file peer](a-one-file-peer.md) | Groovy developers who build with agentspaces-groovy | fleet { bind '127.0.0.1:7591' // agentspaces.bind security { profile 'dev-local' } // agentspaces.security.profile group('research-fleet') { //. |
| [The class form](the-class-form.md) | Groovy developers who build with agentspaces-groovy | @Ballot(space = 'votes') String judge(VoteCapability.Proposal p) { p.question().contains('ship') ? |
| [The Space API, in Groovy](space-api.md) | Groovy developers who build with agentspaces-groovy | space << entry // write under the space's default lease (10m; space.defaultLease = '2h') space.write(entry, '10m') // or lease: '10m'. |
| [Async](async.md) | Groovy developers who build with agentspaces-groovy | Every blocking verb and client wait has a twin returning a `CompletableFuture` on a virtual thread, which Groovy 6's `await` takes unchanged. |
| [The fleet DSL](the-fleet-dsl.md) | Groovy developers who build with agentspaces-groovy | Every keyword is the camel-case name of a starter property under `agentspaces.*`; a block is a nested group; a repeated keyword appends to a list. |
| [The shell](the-shell.md) | Groovy developers who build with agentspaces-groovy | `fleet`, `spaces`, `space(name)`, `group(name)`, `agent(name) { }`, and `bind(agent)` are bound. |
| [Groovy constructs that meet the binder](groovy-constructs.md) | Groovy developers who build with agentspaces-groovy | Groovy constructs that meet the binder |
| [Building](building.md) | contributors who build this repository | Maven with `gmavenplus-plugin` 5.1.0 compiles the Groovy sources; JUnit Jupiter runs the tests (no Spock build exists for Groovy 6). |
