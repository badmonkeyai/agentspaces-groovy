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

import ai.badmonkey.agentspaces.agent.reduce.Reductions
import ai.badmonkey.agentspaces.api.space.Lease
import ai.badmonkey.agentspaces.api.space.Space
import ai.badmonkey.agentspaces.api.space.Template
import ai.badmonkey.agentspaces.capabilities.orderedlog.OrderedTakes
import ai.badmonkey.agentspaces.capabilities.vote.VoteCapability
import ai.badmonkey.agentspaces.common.codec.CborCodec
import ai.badmonkey.agentspaces.common.id.PeerId
import ai.badmonkey.agentspaces.groovy.support.Bidder
import ai.badmonkey.agentspaces.groovy.support.Fleets
import ai.badmonkey.agentspaces.groovy.support.Forker
import ai.badmonkey.agentspaces.groovy.support.Gauge
import ai.badmonkey.agentspaces.groovy.support.ItFinding
import ai.badmonkey.agentspaces.groovy.support.ItTally
import ai.badmonkey.agentspaces.groovy.support.ItTask
import ai.badmonkey.agentspaces.groovy.support.LeasedJoiner
import ai.badmonkey.agentspaces.groovy.support.Mover
import ai.badmonkey.agentspaces.groovy.support.OrderedClerk
import ai.badmonkey.agentspaces.groovy.support.Panel
import ai.badmonkey.agentspaces.groovy.support.TagFolder
import ai.badmonkey.agentspaces.identity.FileKeystore
import ai.badmonkey.agentspaces.identity.PeerIdentity
import ai.badmonkey.agentspaces.runtime.FleetRuntime
import ai.badmonkey.agentspaces.runtime.PropertiesLoader
import ai.badmonkey.agentspaces.space.replicated.ReplicatedSpace
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

import java.nio.file.Files
import java.time.Duration

import static org.assertj.core.api.Assertions.assertThat

/**
 * Every binding annotation and return convention, proven through Groovy
 * classes bound by {@code FleetRuntime.bind} with each outcome read back from
 * a space (SRAD GR-B-01): {@code @SpaceNotify} with an {@code Entries} fork of
 * a {@code Tagged} entry and a {@code @SpaceRef} write; {@code @BidFunction}
 * on an AUCTION space; {@code @Propose}, {@code @Ballot}, {@code @OnDecision},
 * and a {@code @CapabilityRef} client in one object; a {@code Motion} return;
 * a {@code Contribution} return settled by {@code @OnEstimate};
 * {@code @SpaceJoin} in {@code LEASED} mode over a {@code keyTag} part;
 * {@code @SpaceReduce} with {@code keyTag} and {@code accumulatorLease}; and
 * {@code @OrderedTake} over a two-peer ordered log. The classes are the
 * Micronaut passthrough suite's, rewritten in Groovy.
 */
class AnnotationPassthroughTest {

    private static final Lease TEN_MINUTES = Lease.of(Duration.ofMinutes(10))

    private static Space space(FleetRuntime runtime, String name) {
        runtime.spaces().group('it-fleet').space(name)
    }

    @Test
    @Timeout(60L)
    void aNotifyForksATaggedEntryAndATallyAndWritesThroughItsSpaceRef() {
        Fleets.startPassthrough(Fleets.freePort()).withCloseable { FleetRuntime runtime ->
            assertThat(runtime.bind(new Forker())).hasSize(1)
            Space tasks = space(runtime, 'tasks')
            tasks.write(new ItTask('fork', 3), TEN_MINUTES)

            assertThat(Fleets.await(Duration.ofSeconds(20)) { !tasks.readAll(Template.of(ItTally), 10).isEmpty() }).isTrue()
            def findings = tasks.readAllEntries(Template.of(ItFinding), 10)
            assertThat(findings).singleElement().satisfies { entry ->
                assertThat(entry.value()).isEqualTo(new ItFinding('fork', 'forked'))
                assertThat(entry.tags()).containsEntry('kind', 'fork')
            }
            assertThat(tasks.readAll(Template.of(ItTally), 10)).containsExactly(new ItTally('fork', 3))
            assertThat(tasks.readAll(Template.of(ItTask), 10)).as('a notify never consumes its cue')
                    .containsExactly(new ItTask('fork', 3))
            assertThat(space(runtime, 'data').read(Template.of(ItFinding), Duration.ofSeconds(10)))
                    .hasValue(new ItFinding('ref:fork', 'via @SpaceRef'))
        }
    }

    @Test
    @Timeout(60L)
    void aBidFunctionPricesTheAuctionSpaceTheTakeWins() {
        Fleets.startPassthrough(Fleets.freePort()).withCloseable { FleetRuntime runtime ->
            runtime.bind(new Bidder())
            space(runtime, 'bids').write(new ItTask('auction', 2), TEN_MINUTES)

            assertThat(space(runtime, 'findings').read(Template.of(ItFinding), Duration.ofSeconds(20)))
                    .hasValue(new ItFinding('auction', 'won at bid 3.0'))
            assertThat(Fleets.await(Duration.ofSeconds(10)) { space(runtime, 'bids').readAll(Template.of(ItTask), 10).isEmpty() }).isTrue()
        }
    }

    @Test
    @Timeout(60L)
    void aProposeOpensAVoteTheBallotCastsAndTheDecisionLandsWithTheClientsTally() {
        Fleets.startPassthrough(Fleets.freePort()).withCloseable { FleetRuntime runtime ->
            runtime.bind(new Panel())
            Space votes = space(runtime, 'votes')
            space(runtime, 'tasks').write(new ItTask('merge', 1), TEN_MINUTES)

            assertThat(Fleets.await(Duration.ofSeconds(20)) { !votes.readAll(Template.of(VoteCapability.Proposal), 10).isEmpty() }).isTrue()
            assertThat(votes.readAll(Template.of(VoteCapability.Proposal), 10)).singleElement().satisfies { proposal ->
                assertThat(proposal.proposalId()).isEqualTo('task:merge')
                assertThat(proposal.question()).isEqualTo('Approve merge?')
                assertThat(proposal.options()).containsExactly('approve', 'deny')
                assertThat(proposal.quorum()).isEqualTo(1)
            }
            assertThat(Fleets.await(Duration.ofSeconds(20)) { !votes.readAll(Template.of(VoteCapability.Ballot), 10).isEmpty() }).isTrue()
            assertThat(votes.readAll(Template.of(VoteCapability.Ballot), 10)).singleElement().satisfies { ballot ->
                assertThat(ballot.proposalId()).isEqualTo('task:merge')
                assertThat(ballot.option()).isEqualTo('approve')
            }
            assertThat(space(runtime, 'findings').read(Template.of(ItFinding), Duration.ofSeconds(20)))
                    .hasValue(new ItFinding('task:merge', 'decided: approve tally [approve:1, deny:0]'))
        }
    }

    @Test
    @Timeout(60L)
    void aMotionReturnOpensAVoteAndWritesNoEntry() {
        Fleets.startPassthrough(Fleets.freePort()).withCloseable { FleetRuntime runtime ->
            runtime.bind(new Mover())
            Space votes = space(runtime, 'votes')
            space(runtime, 'tasks').write(new ItTask('relocate', 1), TEN_MINUTES)

            assertThat(Fleets.await(Duration.ofSeconds(20)) { !votes.readAll(Template.of(VoteCapability.Proposal), 10).isEmpty() }).isTrue()
            assertThat(votes.readAll(Template.of(VoteCapability.Proposal), 10)).singleElement().satisfies { proposal ->
                assertThat(proposal.proposalId()).isEqualTo('motion:relocate')
                assertThat(proposal.question()).isEqualTo('Move relocate?')
                assertThat(proposal.options()).containsExactly('yes', 'no')
            }
            assertThat(space(runtime, 'tasks').readAll(Template.of(ItFinding), 10)).isEmpty()
        }
    }

    @Test
    @Timeout(60L)
    void aContributionFeedsTheAggregateAndOnEstimateReportsWhenItSettles() {
        Fleets.startPassthrough(Fleets.freePort()).withCloseable { FleetRuntime runtime ->
            runtime.bind(new Gauge())
            space(runtime, 'tasks').write(new ItTask('cpu', 7), TEN_MINUTES)

            assertThat(space(runtime, 'findings').read(Template.of(ItFinding), Duration.ofSeconds(30)))
                    .hasValue(new ItFinding('load:cpu', 'estimate: 7'))
        }
    }

    @Test
    @Timeout(60L)
    void aLeasedJoinFiresFromItsTicketOverAFieldKeyedAndATagKeyedPart() {
        Fleets.startPassthrough(Fleets.freePort()).withCloseable { FleetRuntime runtime ->
            runtime.bind(new LeasedJoiner())
            Space tasks = space(runtime, 'tasks')
            tasks.write(new ItTask('pair', 4), TEN_MINUTES)
            tasks.write(new ItFinding('elsewhere', 'tagged part'), TEN_MINUTES, [topic: 'pair'])

            assertThat(space(runtime, 'data').read(Template.of(ItTally), Duration.ofSeconds(20)))
                    .hasValue(new ItTally('pair', 4))
        }
    }

    @Test
    @Timeout(60L)
    void aTagKeyedReduceFoldsPerTagAndItsAccumulatorLapsesWhenIdle() {
        Fleets.startPassthrough(Fleets.freePort()).withCloseable { FleetRuntime runtime ->
            runtime.bind(new TagFolder())
            Space tasks = space(runtime, 'tasks')
            tasks.write(new ItTask('field-ignored', 1), TEN_MINUTES, [topic: 'fold'])
            tasks.write(new ItTask('field-ignored', 2), TEN_MINUTES, [topic: 'fold'])

            assertThat(Fleets.await(Duration.ofSeconds(20)) { current(tasks).map { it.value().points() == 3 }.orElse(false) }).isTrue()
            def folded = current(tasks).orElseThrow()
            assertThat(folded.tags()).containsEntry(Reductions.KEY_TAG, 'fold').containsEntry(Reductions.REDUCE_TAG, 'tagfold')
            assertThat(Reductions.steps(folded)).isEqualTo(2L)

            assertThat(Fleets.await(Duration.ofSeconds(20)) { current(tasks).isEmpty() })
                    .as('the accumulator lapses after its 2s idle lease').isTrue()
            tasks.write(new ItTask('field-ignored', 5), TEN_MINUTES, [topic: 'fold'])
            assertThat(Fleets.await(Duration.ofSeconds(20)) { current(tasks).map { it.value().points() == 5 }.orElse(false) })
                    .as('the next element starts again from null').isTrue()
        }
    }

    private static Optional<Space.Entry<ItTally>> current(Space tasks) {
        Reductions.current(tasks, ItTally, 'tagfold', 'fold')
    }

    /**
     * Two peers over TCP, each registering an ordered-log coordinator naming
     * both as the log's members and binding a clerk to it: every task is
     * confirmed by exactly one clerk and drains from both replicas. One peer
     * cannot do this (a single-member Raft log never elects itself).
     */
    @Test
    @Timeout(180L)
    void anOrderedTakeConfirmsEachTaskOnceThroughTheLogAcrossTwoPeers() {
        def keystores = Files.createTempDirectory('ordered-keys')
        PeerIdentity first = FileKeystore.loadOrCreate(keystores.resolve('a'))
        PeerIdentity second = FileKeystore.loadOrCreate(keystores.resolve('b'))
        List<PeerId> members = [first.peerId(), second.peerId()]
        int seedPort = Fleets.freePort()
        FleetRuntime a = FleetRuntime.builder(PropertiesLoader.flat(Fleets.passthrough(seedPort))).identity(first).start()
        FleetRuntime b = FleetRuntime.builder(PropertiesLoader.flat(Fleets.passthrough(Fleets.freePort(), seedPort))).identity(second).start()
        try {
            OrderedClerk clerkA = new OrderedClerk()
            OrderedClerk clerkB = new OrderedClerk()
            wireOrdered(a, members, 1L)
            wireOrdered(b, members, 2L)
            a.bind(clerkA)
            b.bind(clerkB)
            Space tasksA = space(a, 'tasks')
            Space tasksB = space(b, 'tasks')
            ['first', 'second', 'third'].eachWithIndex { String topic, int i -> tasksA.write(new ItTask(topic, i + 1), TEN_MINUTES) }

            assertThat(Fleets.await(Duration.ofSeconds(120)) {
                space(a, 'findings').readAll(Template.of(ItFinding), 10).size() == 3 &&
                        space(b, 'findings').readAll(Template.of(ItFinding), 10).size() == 3
            }).as('every task confirmed once, visible on both replicas').isTrue()
            assertThat(clerkA.confirmed + clerkB.confirmed).containsExactlyInAnyOrder('first', 'second', 'third')
            assertThat(Fleets.await(Duration.ofSeconds(30)) {
                tasksA.readAll(Template.of(ItTask), 10).isEmpty() && tasksB.readAll(Template.of(ItTask), 10).isEmpty()
            }).as('the tasks drain from both replicas').isTrue()
        } finally {
            b.close()
            a.close()
        }
    }

    private static void wireOrdered(FleetRuntime runtime, List<PeerId> members, long seed) {
        def group = runtime.spaces().group('it-fleet')
        ReplicatedSpace tasks = (ReplicatedSpace) group.space('tasks')
        OrderedTakes ordered = OrderedTakes.over(group.pipes(), CborCodec.defaultCodec(), runtime.identity(), 'app',
                members, runtime.clock(), seed, tasks)
        group.ordered('tasks', ordered)
    }
}
