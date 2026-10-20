/*
 * Copyright 2026 Bad Monkey, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.badmonkey.agentspaces.groovy

import ai.badmonkey.agentspaces.agent.capability.VoteClient
import ai.badmonkey.agentspaces.capabilities.vote.VoteCapability
import ai.badmonkey.agentspaces.groovy.support.ItFinding
import ai.badmonkey.agentspaces.groovy.support.ItTask
import ai.badmonkey.agentspaces.runtime.FleetRuntime
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

import java.time.Duration

import static ai.badmonkey.agentspaces.groovy.Agents.agent

/**
 * The README's "How to use AI with AgentSpaces" section, verbatim: every
 * documentation snippet is cut from checked-in code that runs under the gate
 * (FEATURES.md section 15, item 18). The lines between each pair of
 * {@code // readme:} markers are the snippet the README shows; the two helpers
 * below stand in for the imports the README elides. Each test starts its
 * peers in this JVM on free loopback ports, with no network and no model key.
 */
class ReadmeExamplesTest {

    private static int freePort() {
        new ServerSocket(0).withCloseable { ServerSocket s -> s.localPort }
    }

    /** Polls until the condition holds or the duration passes; named until because await is a Groovy 6 keyword. */
    private static boolean until(String timeout, Closure<Boolean> condition) {
        long deadline = System.nanoTime() + (timeout as Duration).toNanos()
        while (System.nanoTime() < deadline) {
            if (condition.call()) {
                return true
            }
            Thread.sleep(100)
        }
        condition.call()
    }

    /** README example 1: an agent from closures on one peer, work written by another peer, the result read back. */
    @Test
    @Timeout(90L)
    void anAgentOnOnePeerTakesWhatAnotherPeerWrites() {
        FleetRuntime worker = null
        FleetRuntime client = null
        try {
            // readme: begin agent
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
            // readme: end agent
        } finally {
            client?.close()
            worker?.close()
        }
    }

    /** README example 2: a worker dies mid-take, its lease lapses, the task reappears, and a second worker finishes it. */
    @Test
    @Timeout(120L)
    void aCrashedWorkersTakeReappearsAndASecondWorkerFinishesIt() {
        FleetRuntime slow = null
        FleetRuntime observer = null
        try {
            // readme: begin crash
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
            // readme: end crash
        } finally {
            observer?.close()
            slow?.close()
        }
    }

    /** README example 3: a reviewer agent on each of two peers votes; the proposer awaits the decision with Groovy 6's await. */
    @Test
    @Timeout(90L)
    void twoPeersVoteAndTheProposerAwaitsTheDecision() {
        FleetRuntime first = null
        FleetRuntime second = null
        try {
            // readme: begin vote
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
            // readme: end vote
        } finally {
            second?.close()
            first?.close()
        }
    }
}
