# How to use AI with AgentSpaces

Written for: Groovy developers who build with agentspaces-groovy.

At the end of this section you will have run three fleets in one JVM: an
agent written as closures on one peer that completes work a second peer
writes; a worker that dies in the middle of a take, whose task comes back on
its own and is finished by another worker; and two peers that vote on a
proposal while the proposer awaits the decision with Groovy 6's `await`. Each
snippet below is the body of one test method in
`groovy/src/test/groovy/ai/badmonkey/agentspaces/groovy/ReadmeExamplesTest.groovy`,
verbatim with the imports elided. The peers run on free loopback ports with no
network and no model key, and `freePort()` and `until(timeout) { }` are the
test's two helpers. Run all three with
`cd agentspaces-groovy && mvn -q test -pl groovy -Dtest=ReadmeExamplesTest`.

## 1. Build an agent and hand it work from another peer

`agent(name) { }` describes a worker as one closure per verb; `Fleet.start { }`
starts a peer from the same keywords as the starter's YAML. The second peer
seeds from the first, writes a task, and reads the finding the agent's return
value became. (`ReadmeExamplesTest.anAgentOnOnePeerTakesWhatAnotherPeerWrites`)

```groovy
int workerPort = freePort()
worker = Fleet.start {                                               // peer one
    bind "127.0.0.1:$workerPort"
    tick '200ms'
    group('it-fleet') { founding 'groovy-it-fleet-v1'; space('tasks') { settleWindow '100ms' } }
}
worker.bind agent('summarizer', description: 'Summarizes tasks') {   // the agent: one closure per verb
    take(ItTask, space: 'tasks', lease: '30s', pollTimeout: '200ms', returns: ItFinding) { ItTask t ->
        new ItFinding(t.topic(), "summary of ${t.topic()}")
    }
}

client = Fleet.start {                                               // peer two, seeded from peer one
    bind "127.0.0.1:${freePort()}"
    tick '200ms'
    group('it-fleet') { founding 'groovy-it-fleet-v1'; seeds "127.0.0.1:$workerPort"; space('tasks') { settleWindow '100ms' } }
}
def tasks = client.context().group().space('tasks')
tasks << new ItTask('tuple spaces', 1)                               // peer two writes a task
Optional<ItFinding> finding = tasks.read(ItFinding, timeout: '30s')  // peer one took it; its return is the next entry
assert finding.get().summary() == 'summary of tuple spaces'
```

The space is the only thing the two peers share: no queue, no broker, and no
URL for the agent. The take is a signed claim under a lease, so no other
worker in the fleet sees the task while this one holds it.

## 2. A crashed worker's take reappears

A worker that takes a task and never finishes dies mid-take when its peer
closes. Its lease lapses, the task reappears in the space, and a worker bound
afterwards finishes it. No retry logic exists anywhere in this code.
(`ReadmeExamplesTest.aCrashedWorkersTakeReappearsAndASecondWorkerFinishesIt`)

```groovy
int slowPort = freePort()
slow = Fleet.start {
    bind "127.0.0.1:$slowPort"; tick '200ms'
    group('it-fleet') { founding 'groovy-it-fleet-v1'; space('tasks') { settleWindow '100ms' } }
}
slow.bind agent('slow-worker') {
    take(ItTask, space: 'tasks', lease: '3s', pollTimeout: '200ms', returns: ItFinding) { ItTask t ->
        Thread.sleep(60_000)                                         // a worker that never finishes
        new ItFinding(t.topic(), 'never')
    }
}
observer = Fleet.start {
    bind "127.0.0.1:${freePort()}"; tick '200ms'
    group('it-fleet') { founding 'groovy-it-fleet-v1'; seeds "127.0.0.1:$slowPort"; space('tasks') { settleWindow '100ms' } }
}
def tasks = observer.context().group().space('tasks')
tasks << new ItTask('resilient', 1)
assert until('20s') { tasks.count(ItTask) == 0 }                    // taken: no other worker can see it
slow.close()                                                        // the worker dies mid-take
assert until('30s') { tasks.count(ItTask) == 1 }                    // its lease lapsed: the task is back
observer.bind agent('second-worker') {
    take(ItTask, space: 'tasks', lease: '30s', pollTimeout: '200ms', returns: ItFinding) { ItTask t ->
        new ItFinding(t.topic(), 'finished by the second worker')
    }
}
assert tasks.read(ItFinding, timeout: '30s').get().summary() == 'finished by the second worker'
```

## 3. A fleet vote, awaited

Each peer binds a reviewer whose `ballot` clause judges every proposal under
the `ship:` prefix. The proposer asks for the decision first, as a
`CompletableFuture` from the `decisionAsync` twin, proposes with a quorum of
two, and `await`s the future; the tally shows one ballot from each peer.
(`ReadmeExamplesTest.twoPeersVoteAndTheProposerAwaitsTheDecision`)

```groovy
int firstPort = freePort()
first = Fleet.start {
    bind "127.0.0.1:$firstPort"; tick '200ms'
    group('it-fleet') { founding 'groovy-it-fleet-v1'; space 'tasks'; space 'votes' }
}
second = Fleet.start {
    bind "127.0.0.1:${freePort()}"; tick '200ms'
    group('it-fleet') { founding 'groovy-it-fleet-v1'; seeds "127.0.0.1:$firstPort"; space 'tasks'; space 'votes' }
}
[first, second].each { peer ->                                      // one reviewer per peer
    peer.bind agent('reviewer') {
        ballot(space: 'votes', prefix: 'ship:') { VoteCapability.Proposal p ->
            p.question().contains('tests') ? 'yes' : 'no'
        }
    }
}
VoteClient votes = second.spaces().group('it-fleet').capability(VoteClient)
def decision = votes.decisionAsync('ship:release-1')                // a CompletableFuture, before any proposal
votes.propose(id: 'ship:release-1', question: 'Ship once the tests pass?', options: ['yes', 'no'], quorum: 2)
VoteCapability.Decision decided = await decision                   // Groovy 6's await on the twin
assert decided.winner() == 'yes'
assert votes.tally('ship:release-1').yes == 2                      // one ballot from each peer
```

Every blocking verb and client wait has an `Async` twin like `decisionAsync`,
so a bound body can fan out under `AsyncScope` and await several at once (see
"Async" below). The one-file `@Grab` form of the first example is the next
section.
