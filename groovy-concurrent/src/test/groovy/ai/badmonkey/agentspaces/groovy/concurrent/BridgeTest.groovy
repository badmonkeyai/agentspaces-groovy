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
package ai.badmonkey.agentspaces.groovy.concurrent

import ai.badmonkey.agentspaces.agent.capability.AggregateClient
import ai.badmonkey.agentspaces.agent.capability.VoteClient
import ai.badmonkey.agentspaces.api.space.Space
import ai.badmonkey.agentspaces.api.space.SpaceEvent
import ai.badmonkey.agentspaces.api.space.Subscription
import ai.badmonkey.agentspaces.groovy.concurrent.support.Fleets
import ai.badmonkey.agentspaces.groovy.concurrent.support.ItFinding
import ai.badmonkey.agentspaces.groovy.concurrent.support.ItTask
import ai.badmonkey.agentspaces.runtime.FleetRuntime
import groovy.concurrent.ChannelClosedException
import groovy.concurrent.ChannelSelect
import groovy.concurrent.DataflowVariable
import org.apache.groovy.runtime.async.AsyncSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout

import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

import static org.assertj.core.api.Assertions.assertThat
import static org.assertj.core.api.Assertions.assertThatThrownBy

/** The bridge (GR-C-04 to GR-C-07, GR-Q-REL-03) over one passthrough runtime. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BridgeTest {

    private FleetRuntime runtime

    @BeforeAll
    void start() {
        runtime = FleetRuntime.start(ai.badmonkey.agentspaces.runtime.PropertiesLoader.flat(
                Fleets.passthrough(Fleets.freePort()) + ['agentspaces.groups[0].spaces[5].name': 'events', 'agentspaces.groups[0].spaces[6].name': 'fan', 'agentspaces.groups[0].spaces[7].name': 'lease', 'agentspaces.groups[0].spaces[8].name': 'left', 'agentspaces.groups[0].spaces[9].name': 'right']))
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
    void aChannelDeliversTheSubscriptionsEventsAndClosingItUnsubscribes() {
        Space s = space('events')
        def channel = s.channel(ItTask, lease: '1h', buffer: 8, on: 'written', filter: { priority > 1 })
        s << new ItTask('low', 1) << new ItTask('high', 2) << new ItTask('higher', 3)
        List<String> seen = []
        for await (SpaceEvent<ItTask> e in channel) {
            seen << e.entry().topic()
            if (seen.size() == 2) {
                break
            }
        }
        assertThat(seen).containsExactly('high', 'higher')
        assertThat(channel.closed).isFalse()
        channel.close()
        assertThat(channel.closed).isTrue()
        s << new ItTask('after', 5)
        assertThatThrownBy { AsyncSupport.await(channel.receive()) }.isInstanceOf(ChannelClosedException)
    }

    @Test
    @Timeout(60L)
    void aChannelClosesWhenItsLeaseWouldLapseUnlessRenewed() {
        Space s = space('lease')
        def channel = s.channel(ItTask, lease: '1s')
        assertThat(Fleets.await(Duration.ofSeconds(10)) { channel.closed }).as('closed at the lease').isTrue()
        def renewed = s.channel(ItTask, lease: '1s')
        Thread.sleep(600)
        renewed.renew('5s')
        Thread.sleep(1500)
        assertThat(renewed.closed).as('renewed past the first lease').isFalse()
        renewed.close()
    }

    @Test
    @Timeout(60L)
    void aBroadcastFeedsEverySubscriberFromOneSubscription() {
        Space s = space('fan')
        def fan = s.broadcast(ItTask, on: ['written'])
        def a = fan.subscribe()
        def b = fan.subscribe(4)
        assertThat(fan.subscriberCount).isEqualTo(2)
        s << new ItTask('shared', 1)
        assertThat(AsyncSupport.await(a.receive()).entry().topic()).isEqualTo('shared')
        assertThat(AsyncSupport.await(b.receive()).entry().topic()).isEqualTo('shared')
        fan.close()
        assertThat(fan.closed).isTrue()
    }

    @Test
    @Timeout(60L)
    void selectTakesTheFirstOfTwoSpacesOrTimesOut() {
        def left = space('left').channel(ItTask, on: 'written')
        def right = space('right').channel(ItFinding, on: 'written')
        ChannelSelect.Result timedOut = Selects.firstOf('300ms', left, right).join()
        assertThat(timedOut.timeout).isTrue()
        space('right') << new ItFinding('r', 'first')
        ChannelSelect.Result result = Selects.firstOf('10s', left, right).join()
        assertThat(result.timeout).isFalse()
        assertThat(result.index).isEqualTo(1)
        assertThat(((SpaceEvent<ItFinding>) result.value).entry().summary()).isEqualTo('first')
        [left, right]*.close()
    }

    @Test
    @Timeout(60L)
    void dataflowVariablesBindFromADecisionAndASettledEpoch() {
        VoteClient votes = runtime.spaces().group('it-fleet').capability(VoteClient)
        DataflowVariable decision = votes.decisionVar('bridge:ship')
        assertThat(decision.bound).isFalse()
        votes.propose(id: 'bridge:ship', question: 'ship?', options: ['yes', 'no'], quorum: 1)
        votes.cast('bridge:ship', 'yes')
        assertThat(AsyncSupport.await(decision).winner()).isEqualTo('yes')

        AggregateClient aggregate = runtime.spaces().group('it-fleet').capability(AggregateClient)
        aggregate.start('bridge:load', 2.5d)
        DataflowVariable<Double> settled = aggregate.settledVar('bridge:load', ticks: 2, tolerance: 0.01, timeout: '30s')
        assertThat(AsyncSupport.await(settled)).isEqualTo(2.5d)
    }

    @Test
    void awaitingASubscriptionIsRefusedWithAMessageRatherThanHanging() {
        Bridge.install()
        Subscription subscription = space('events').notify(ItTask) { e -> }
        assertThatThrownBy { AsyncSupport.await(subscription) }.hasMessageContaining('not awaitable').hasMessageContaining('space.channel')
        subscription.close()
    }

    @Test
    void nothingInTheCoreArtifactImportsTheIncubatingToolkit() {
        // GR-C-04: the core jar's classes never reference groovy.concurrent; only this module does.
        File core = new File(ai.badmonkey.agentspaces.groovy.Fleet.protectionDomain.codeSource.location.toURI())
        List<String> offenders = []
        if (core.isFile()) {
            new java.util.jar.JarFile(core).withCloseable { jar ->
                jar.entries().each { entry ->
                    if (entry.name.endsWith('.class') && new String(jar.getInputStream(entry).bytes, 'ISO-8859-1').contains('groovy/concurrent/')) {
                        offenders << entry.name
                    }
                }
            }
        } else {
            core.eachFileRecurse(groovy.io.FileType.FILES) { File f ->
                if (f.name.endsWith('.class') && new String(f.bytes, 'ISO-8859-1').contains('groovy/concurrent/')) {
                    offenders << f.name
                }
            }
        }
        assertThat(offenders).isEmpty()
    }
}
