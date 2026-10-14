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

import ai.badmonkey.agentspaces.agent.capability.AggregateClient
import ai.badmonkey.agentspaces.agent.capability.VoteClient
import ai.badmonkey.agentspaces.api.space.Space
import ai.badmonkey.agentspaces.api.space.TakenEntry
import ai.badmonkey.agentspaces.groovy.support.Fleets
import ai.badmonkey.agentspaces.groovy.support.ItFinding
import ai.badmonkey.agentspaces.groovy.support.ItTally
import ai.badmonkey.agentspaces.groovy.support.ItTask
import ai.badmonkey.agentspaces.runtime.FleetRuntime
import groovy.concurrent.AsyncScope
import groovy.concurrent.Awaitable
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout

import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

import static ai.badmonkey.agentspaces.groovy.Agents.agent
import static org.assertj.core.api.Assertions.assertThat
import static org.assertj.core.api.Assertions.assertThatThrownBy

/**
 * The async face (GR-C-01 to GR-C-03): the twins return futures Groovy 6's
 * {@code await} takes unchanged, and a bound body may fan out with
 * {@code AsyncScope} and await the twins.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AsyncTest {

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
    void theTwinsAreFuturesAwaitTakesUnchanged() {
        Space tasks = space('tasks')
        tasks << new ItTask('async-a', 1) << new ItTask('async-b', 2)

        CompletableFuture<Optional<ItTask>> read = tasks.readAsync(ItTask, where: [topic: 'async-a'])
        Optional<ItTask> first = await read
        assertThat(first).hasValueSatisfying { assertThat(it.priority()).isEqualTo(1) }
        List<ItTask> everything = await tasks.allAsync(ItTask, where: [priority: Where.gte(1)])
        assertThat(everything.size()).isGreaterThanOrEqualTo(2)
        def (Optional<ItTask> a, Optional<ItTask> b) = await(tasks.readAsync(ItTask, where: [topic: 'async-a']), tasks.readAsync(ItTask, where: [topic: 'async-b']))
        assertThat(a.get().topic()).isEqualTo('async-a')
        assertThat(b.get().topic()).isEqualTo('async-b')

        Optional<TakenEntry<ItTask>> taken = await tasks.takeAsync(ItTask, lease: '30s', timeout: '2s', where: [topic: 'async-a'])
        assertThat(taken).isPresent()
        tasks.complete(taken.get())
        ItFinding result = await tasks.takeAsync(ItTask, lease: '30s', where: [topic: 'async-b']) { ItTask t -> new ItFinding(t.topic(), 'async body') }
        assertThat(result).isEqualTo(new ItFinding('async-b', 'async body'))
        Optional<ItTask> none = await Awaitable.orTimeoutMillis(tasks.readAsync(ItTask, timeout: '100ms', where: [topic: 'nope']), 5000)
        assertThat(none).isEmpty()

        // A failing twin completes exceptionally with the Java exception, which await unwraps.
        assertThatThrownBy { def failing = await tasks.allAsync(ItTask, where: [nope: 1]) }
                .isInstanceOf(IllegalArgumentException).hasMessageContaining("no field 'nope'")
        assertThatThrownBy { tasks.allAsync(ItTask, where: [nope: 1]).join() }.isInstanceOf(CompletionException)
    }

    @Test
    @Timeout(60L)
    void decisionAndSettledTwinsResolveWhenTheCapabilitiesAnswer() {
        VoteClient votes = runtime.spaces().group('it-fleet').capability(VoteClient)
        CompletableFuture decision = votes.decisionAsync('async:ship')
        assertThat(decision.isDone()).isFalse()
        votes.propose(id: 'async:ship', question: 'ship?', options: ['yes', 'no'], quorum: 1)
        votes.cast('async:ship', 'yes')
        def decided = await decision
        assertThat(decided.winner()).isEqualTo('yes')
        def again = await votes.decisionAsync('async:ship')
        assertThat(again.winner()).as('a decided proposal resolves at once').isEqualTo('yes')

        AggregateClient aggregate = runtime.spaces().group('it-fleet').capability(AggregateClient)
        aggregate.start('async:load', 4.0d)
        OptionalDouble settled = await aggregate.settledAsync('async:load', ticks: 2, tolerance: 0.01, timeout: '30s')
        assertThat(settled).hasValue(4.0d)
    }

    /**
     * GR-C-03: a DSL body fans out under AsyncScope, awaiting two twins; a
     * failure of one cancels the other, the body throws, and the take lapses.
     */
    @Test
    @Timeout(90L)
    void aBoundBodyFansOutUnderAsyncScopeAndTheLeaseFollowsTheOutcome() {
        Space tasks = space('tasks')
        Space data = space('data')
        Space findings = space('findings')
        def planner = agent('planner') {
            take(ItTask, space: 'tasks', lease: '3s', pollTimeout: 'PT0.2S', where: ['priority=9'], resultSpace: 'findings', returns: ItFinding) { ItTask t ->
                AsyncScope.withScope { scope ->
                    def tally = scope.async { await data.readAsync(ItTally, timeout: '10s', where: [topic: t.topic()]) }
                    def mark = scope.async { await data.readAsync(ItFinding, timeout: '10s', where: [topic: t.topic()]) }
                    Optional<ItTally> points = await tally
                    Optional<ItFinding> marked = await mark
                    new ItFinding(t.topic(), "planned ${points.get().points()} ${marked.get().summary()}")
                }
            }
            take(ItTask, space: 'tasks', lease: '2s', pollTimeout: 'PT0.2S', where: ['priority=8'], returns: ItFinding) { ItTask t ->
                AsyncScope.withScope { scope ->
                    // A cancelled twin's blocking call still runs to its own timeout (the binder renews the take
                    // lease meanwhile), so a fan-out keeps its timeouts short.
                    def slow = scope.async { await data.readAsync(ItTally, timeout: '3s', where: [topic: 'never-written']) }
                    def failing = scope.async { throw new IllegalStateException('fan-out failed') }
                    def f = await failing
                    def sl = await slow
                    new ItFinding(t.topic(), 'never')
                }
            }
        }
        runtime.bind(planner)

        data << new ItTally('plan', 7) << new ItFinding('plan', 'ok')
        tasks << new ItTask('plan', 9)
        assertThat(findings.read(ItFinding, timeout: '30s', where: [topic: 'plan'])).hasValue(new ItFinding('plan', 'planned 7 ok'))

        tasks << new ItTask('doomed', 8)
        assertThat(Fleets.await(Duration.ofSeconds(10)) { tasks.count(ItTask, where: [topic: 'doomed']) == 0 }).as('taken').isTrue()
        assertThat(Fleets.await(Duration.ofSeconds(30)) { tasks.count(ItTask, where: [topic: 'doomed']) == 1 }).as('the lease lapsed after the body threw').isTrue()
        assertThat(findings.all(ItFinding, where: [topic: 'doomed'])).isEmpty()
    }

    @Test
    void theScopeCancelsTheSiblingWhenOneChildFails() {
        def sibling = null
        assertThatThrownBy {
            AsyncScope.withScope { scope ->
                sibling = scope.async { await Awaitable.delay(30_000) }
                def failing = scope.async { throw new IllegalStateException('boom') }
                def f = await failing
                f
            }
        }.hasMessageContaining('boom')
        assertThat(Fleets.await(Duration.ofSeconds(5)) { sibling.isCancelled() || sibling.isDone() }).isTrue()
    }
}
