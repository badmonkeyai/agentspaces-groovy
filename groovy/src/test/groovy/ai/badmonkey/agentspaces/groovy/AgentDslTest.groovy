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

import ai.badmonkey.agentspaces.agent.AgentBinder
import ai.badmonkey.agentspaces.agent.Tagged
import ai.badmonkey.agentspaces.agent.capability.Contribution
import ai.badmonkey.agentspaces.agent.capability.Motion
import ai.badmonkey.agentspaces.api.space.Lease
import ai.badmonkey.agentspaces.api.space.Space
import ai.badmonkey.agentspaces.capabilities.vote.VoteCapability
import ai.badmonkey.agentspaces.groovy.support.Fleets
import ai.badmonkey.agentspaces.groovy.support.ItFinding
import ai.badmonkey.agentspaces.groovy.support.ItTally
import ai.badmonkey.agentspaces.groovy.support.ItTask
import ai.badmonkey.agentspaces.runtime.FleetRuntime
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout

import java.time.Duration

import static ai.badmonkey.agentspaces.groovy.Agents.agent
import static org.assertj.core.api.Assertions.assertThat
import static org.assertj.core.api.Assertions.assertThatThrownBy

/**
 * The agent DSL (GR-A-01 to GR-A-07, GR-Q-SEC-01, GR-Q-PRF-02) over one
 * passthrough runtime: clauses become an annotated interface the Java binder
 * binds; the outcomes are read back from the spaces as in the passthrough suite.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentDslTest {

    private static final Lease TEN_MINUTES = Lease.of(Duration.ofMinutes(10))
    private FleetRuntime runtime

    @BeforeAll
    void start() {
        runtime = Fleets.startPassthrough(Fleets.freePort())
    }

    @AfterAll
    void stop() {
        runtime.close()
    }

    private Space space(String name) {
        runtime.spaces().group('it-fleet').space(name)
    }

    @Test
    @Timeout(60L)
    void takeNotifyAndTheCardComeFromClauses() {
        def worker = agent('clause-worker', description: 'Takes and forks', goals: ['work']) {
            take(ItTask, name: 'summarize', space: 'tasks', pollTimeout: 'PT0.2S', where: ['priority=1'], returns: ItFinding) { ItTask t ->
                new ItFinding(t.topic(), 'from a clause')
            }
            notify(ItTask, space: 'tasks', where: ['priority=2'], produces: [ItFinding, ItTally]) { ItTask t ->
                [Tagged.of(new ItFinding(t.topic(), 'forked'), 'kind', 'fork'), new ItTally(t.topic(), t.priority())]
            }
        }
        assertThat(worker.toString()).contains("agent 'clause-worker'").contains('summarize').contains('notify2')
        List<AgentBinder.Bound> bound = runtime.bind(worker)
        assertThat(bound).hasSize(1)
        def card = bound[0].card()
        assertThat(card.agent().localName()).isEqualTo('clause-worker')
        assertThat(card.description()).isEqualTo('Takes and forks')
        assertThat(card.goals()).containsExactly('work')
        assertThat(card.actions()*.name()).containsExactlyInAnyOrder('summarize', 'notify2')
        assertThat(card.toMap().keySet()).contains('agent', 'description', 'goals', 'consumes', 'produces', 'actions')
        assertThat(card.produces()).anySatisfy { String p -> assertThat(p).contains('ItFinding') }

        Space tasks = space('tasks')
        tasks << new ItTask('one', 1) << new ItTask('two', 2)
        def mine = [where: [topic: Where.anyOf('one', 'two')]]
        assertThat(Fleets.await(Duration.ofSeconds(20)) { tasks.count(mine, ItFinding) == 2 && tasks.count(mine, ItTally) == 1 }).isTrue()
        assertThat(tasks.all(mine, ItFinding)*.summary()).containsExactlyInAnyOrder('from a clause', 'forked')
        assertThat(tasks.entries(ItFinding, where: [topic: 'two'], tags: [kind: 'fork'])).hasSize(1)
        assertThat(tasks.all(mine, ItTask)*.topic()).as('the take consumed one, the notify left two').containsExactly('two')
    }

    @Test
    @Timeout(60L)
    void aPanelOfProposeBallotAndOnDecisionBindsFromClausesWithMapsIn() {
        def votes = runtime.spaces().group('it-fleet').capability(ai.badmonkey.agentspaces.agent.capability.VoteClient)
        def panel = agent('clause-panel') {
            propose(ItTask, space: 'tasks', vote: 'votes', prefix: 'dsl:', key: 'topic', options: ['approve', 'deny'], quorum: 1, where: ['priority=5'], asMap: true, returns: String) { Map t ->
                "Approve ${t.topic}?"
            }
            ballot(space: 'votes', prefix: 'dsl:') { VoteCapability.Proposal p -> 'approve' }
            onDecision(space: 'votes', prefix: 'dsl:', resultSpace: 'findings', returns: ItFinding) { VoteCapability.Decision d ->
                new ItFinding(d.proposalId(), "decided: ${d.winner()} tally ${votes.tally(d.proposalId())}")
            }
        }
        runtime.bind(panel)
        space('tasks').write(new ItTask('merge', 5), TEN_MINUTES)
        assertThat(space('findings').read(ItFinding, timeout: '20s', where: [topic: 'dsl:merge']))
                .hasValue(new ItFinding('dsl:merge', 'decided: approve tally [approve:1, deny:0]'))
    }

    @Test
    @Timeout(60L)
    void motionAndContributionReturnsAndAsMapRoundTrip() {
        def mover = agent('clause-mover') {
            notify(ItTask, space: 'tasks', where: ['priority=6'], returns: Motion) { ItTask t ->
                Motion.in('votes', "motion:${t.topic()}", "Move ${t.topic()}?", ['yes', 'no'], 1, Lease.of(Duration.ofHours(1)))
            }
            notify(ItTask, space: 'tasks', where: ['priority=7'], returns: Contribution) { ItTask t -> Contribution.to("load:${t.topic()}", t.priority()) }
            onEstimate(prefix: 'load:', settleTicks: 2, tolerance: 0.01d, resultSpace: 'findings', asMap: true, returns: ItFinding) { Map e ->
                [topic: e.epochId, summary: "estimate: ${Math.round((double) e.value)}"]
            }
        }
        runtime.bind(mover)
        space('tasks') << new ItTask('relocate', 6) << new ItTask('cpu', 7)
        assertThat(space('votes').read(VoteCapability.Proposal, timeout: '20s', where: [proposalId: 'motion:relocate'])).isPresent()
        assertThat(space('findings').read(ItFinding, timeout: '30s', where: [topic: 'load:cpu'])).hasValue(new ItFinding('load:cpu', 'estimate: 7'))
    }

    @Test
    @Timeout(60L)
    void joinAndReduceClausesBind() {
        def assembler = agent('clause-assembler') {
            join(space: 'tasks', key: 'topic', mode: 'leased', takeLease: '2s', pollTimeout: '200ms', resultSpace: 'data', returns: ItTally,
                    parts: [[value: ItTask], [value: ItFinding, keyTag: 'topic']]) { joined ->
                new ItTally(joined.key(), joined.get(ItTask).priority())
            }
            reduce(ItTally, ItTask, space: 'tasks', keyTag: 'topic', name: 'dslfold', lease: '1s', pollTimeout: 'PT0.2S', accumulatorLease: '2s') { ItTally acc, ItTask t ->
                new ItTally(t.topic(), (acc == null ? 0 : acc.points()) + t.priority())
            }
        }
        def bound = runtime.bind(assembler)
        assertThat(bound[0].card().actions()*.name()).containsExactlyInAnyOrder('join1', 'dslfold')
        Space tasks = space('tasks')
        tasks.write(new ItTask('pair', 4), TEN_MINUTES)
        tasks.write(new ItFinding('elsewhere', 'tagged part'), TEN_MINUTES, [topic: 'pair'])
        assertThat(space('data').read(ItTally, timeout: '20s', where: [topic: 'pair'])).hasValue(new ItTally('pair', 4))
    }

    @Test
    void theDslRefusesWhatTheAnnotationsCannotCarry() {
        assertThatThrownBy { agent('x') { take(ItTask, spce: 'tasks') { } } }
                .hasMessageContaining("unknown option 'spce' for @SpaceTake").hasMessageContaining('did you mean space').hasMessageContaining('pollTimeout')
        assertThatThrownBy { agent('x') { take(ItTask, lease: 10) { } } }.hasMessageContaining('@SpaceTake.lease wants a string')
        assertThatThrownBy { agent('x') { join(space: 'tasks', key: 'k', mode: 'sideways', parts: [ItTask]) { } } }.hasMessageContaining("mode wants one of [local, leased, ordered]")
        assertThatThrownBy { agent('x') { propose(ItTask, space: 'tasks') { } } }.hasMessageContaining('@Propose needs options:')
        assertThatThrownBy { agent('x') { take(ItTask, returns: 'Quote') { } } }.hasMessageContaining('returns: takes a class')
        assertThatThrownBy { agent('x') { take(ItTask, asMap: true) { } } }.hasMessageContaining('asMap: true needs returns:')
        assertThatThrownBy { agent('x') { take(ItTask, name: 'a') { }; take(ItTask, name: 'a') { } } }.hasMessageContaining("already has a clause named 'a'")
        assertThatThrownBy { agent('x') { } }.hasMessageContaining('has no clauses')
        assertThatThrownBy { agent('x', goal: 'y') { take(ItTask) { } } }.hasMessageContaining('unknown option [goal]')
        assertThatThrownBy { agent(null) { take(ItTask) { } } }.hasMessageContaining('needs a name')
    }

    @Test
    void hostileValuesAreRefusedBeforeAnyCompilation() {
        int before = InterfaceCache.compilations()
        assertThatThrownBy { agent('x') { take(ItTask, name: 'a(); System.exit(1); def b') { } } }.hasMessageContaining('must be a Java identifier')
        assertThatThrownBy { agent('x') { take(ItTask, name: 'class') { } } }.hasMessageContaining('must be a Java identifier')
        assertThatThrownBy { agent('x') { take(ItTask, produces: ['com.evil.Thing']) { } } }.hasMessageContaining('wants a class')
        // Strings are escaped, never spliced: a quote, a backslash, a newline, and non-ASCII all survive as attribute values.
        String hostile = "'); System.exit(1); ('\\\n​é"
        def spooky = agent('x') { take(ItTask, space: hostile, description: hostile, where: ["topic=${hostile}".toString()]) { } }
        def model = Agents.modelOf(spooky)
        assertThat(model.clauses[0].options.space).isEqualTo(hostile)
        def take = spooky.getClass().interfaces[0].getMethod('take1', ItTask).getAnnotation(ai.badmonkey.agentspaces.agent.annotation.SpaceTake)
        assertThat(take.space()).isEqualTo(hostile)
        assertThat(take.description()).isEqualTo(hostile)
        assertThat(take.where()).containsExactly("topic=${hostile}".toString())
        assertThat(InterfaceCache.compilations()).isEqualTo(before + 1)
        assertThat(InterfaceWriter.source(model)).contains("space = '\\u0027); System.exit(1); (\\u0027\\u005c\\u000a\\u200b\\u00e9'")
    }

    @Test
    void oneShapeCompilesOnceAndTwoBodiesShareTheInterface() {
        int before = InterfaceCache.compilations()
        def a = agent('shared') { take(ItTask, space: 'tasks', returns: ItFinding) { ItTask t -> new ItFinding(t.topic(), 'a') } }
        def b = agent('shared') { take(ItTask, space: 'tasks', returns: ItFinding) { ItTask t -> new ItFinding(t.topic(), 'b') } }
        def c = agent('shared') { take(ItTask, space: 'other', returns: ItFinding) { ItTask t -> new ItFinding(t.topic(), 'c') } }
        assertThat(a.getClass().interfaces[0]).isSameAs(b.getClass().interfaces[0])
        assertThat(a.getClass().interfaces[0]).isNotSameAs(c.getClass().interfaces[0])
        assertThat(InterfaceCache.compilations()).isEqualTo(before + 2)
        assertThat(a.getClass().interfaces[0].name).startsWith(InterfaceWriter.PACKAGE + '.Agent_')
        // The proxy's handler hands the cue to the body and returns its result.
        def iface = a.getClass().interfaces[0]
        assertThat(iface.getMethod('take1', ItTask).invoke(a, new ItTask('direct', 1))).isEqualTo(new ItFinding('direct', 'a'))
        assertThat(iface.getMethod('take1', ItTask).invoke(b, new ItTask('direct', 1))).isEqualTo(new ItFinding('direct', 'b'))
        assertThat(a == a).isTrue()
        assertThat(a == b).isFalse()
    }
}
