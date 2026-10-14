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

import ai.badmonkey.agentspaces.api.space.Lease
import ai.badmonkey.agentspaces.api.space.Space
import ai.badmonkey.agentspaces.api.space.SpaceEvent
import ai.badmonkey.agentspaces.api.space.Subscription
import ai.badmonkey.agentspaces.api.space.TakenEntry
import ai.badmonkey.agentspaces.api.space.Template
import ai.badmonkey.agentspaces.groovy.support.Fleets
import ai.badmonkey.agentspaces.groovy.support.ItFinding
import ai.badmonkey.agentspaces.groovy.support.ItTask
import ai.badmonkey.agentspaces.runtime.FleetRuntime
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout

import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

import static ai.badmonkey.agentspaces.groovy.Where.anyOf
import static ai.badmonkey.agentspaces.groovy.Where.gte
import static ai.badmonkey.agentspaces.groovy.Where.present
import static org.assertj.core.api.Assertions.assertThat
import static org.assertj.core.api.Assertions.assertThatThrownBy

/**
 * The extension module (GR-V-01 to GR-V-09) over one running peer; each test
 * uses its own space so the rows do not see each other's entries.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExtensionsTest {

    private FleetRuntime runtime

    @BeforeAll
    void start() {
        runtime = Fleets.start(Fleets.freePort(), 0, 'write', 'read', 'take', 'lapse', 'notify', 'ginq', 'use')
    }

    @AfterAll
    void stop() {
        runtime.close()
    }

    private Space space(String name) {
        runtime.spaces().group('it-fleet').space(name)
    }

    @Test
    void leftShiftWritesUnderTheDefaultLeaseAndWriteTakesStringsAndNamedArguments() {
        Space s = space('write')
        assertThat(s.defaultLease).isEqualTo(Lease.of(Duration.ofMinutes(10)))
        s.defaultLease = '2h'
        assertThat(s.defaultLease).isEqualTo(Lease.of(Duration.ofHours(2)))

        s << new ItTask('a', 1) << new ItTask('b', 2)
        def handle = s.write(new ItTask('c', 3), '30m')
        assertThat(handle.entryId()).isNotNull()
        s.write(new ItTask('d', 4), lease: '1h', tags: [region: 'eu', shard: 7])
        handle.renew('5m')

        assertThat(s.all(ItTask)*.topic()).containsExactlyInAnyOrder('a', 'b', 'c', 'd')
        def entries = s.entries(ItTask, tags: [region: 'eu'])
        assertThat(entries).singleElement().satisfies { e ->
            assertThat(e.value().topic()).isEqualTo('d')
            assertThat(e.tags()).containsEntry('shard', '7')
            assertThat(e.lease()).isNotNull()
        }
        assertThat(s.entries(ItTask, tags: [shard: present()])).hasSize(1)
        assertThat(s.issued(ItTask)*.issuer()).allMatch { it.peer() == runtime.identity().peerId() }
        assertThat(s.count(ItTask)).isEqualTo(4)
        handle.cancel()
        assertThat(Fleets.await(Duration.ofSeconds(5)) { s.count(ItTask) == 3 }).isTrue()
    }

    @Test
    void readAndAllTakeWhereMapsAndFilterClosures() {
        Space s = space('read')
        (1..5).each { s << new ItTask("t$it", it) }

        assertThat(s.read(ItTask, where: [topic: 't3'])).hasValueSatisfying { assertThat(it.priority()).isEqualTo(3) }
        assertThat(s.all(ItTask, where: [priority: gte(4)])*.topic()).containsExactlyInAnyOrder('t4', 't5')
        assertThat(s.all(ItTask, where: [priority: { it % 2 == 0 }])*.topic()).containsExactlyInAnyOrder('t2', 't4')
        assertThat(s.all(ItTask, where: [topic: anyOf('t1', 't2')])).hasSize(2)
        assertThat(s.all(ItTask, where: [topic: anyOf(['t1'])])).hasSize(1)
        assertThat(s.all(ItTask, limit: 2)).hasSize(2)

        // The filter closure: the entry is the delegate, so components read as properties; or declare the parameter.
        assertThat(s.all(ItTask) { priority > 3 }*.topic()).containsExactlyInAnyOrder('t4', 't5')
        assertThat(s.all(ItTask) { ItTask t -> t.topic().endsWith('1') }*.topic()).containsExactly('t1')
        assertThat(s.read(ItTask) { priority == 5 }).hasValueSatisfying { assertThat(it.topic()).isEqualTo('t5') }
        assertThat(s.read(ItTask, timeout: '300ms') { priority == 99 }).isEmpty()
        assertThat(s.read(ItTask, timeout: '300ms', where: [priority: 99])).isEmpty()
        assertThat(s.entries(ItTask) { priority == 1 }).hasSize(1)

        // Unknown field, unknown shape: the Java refusals, by name.
        assertThatThrownBy { s.all(ItTask, where: [prio: 1]) }.hasMessageContaining("no field 'prio'")
        assertThatThrownBy { s.all(ItTask, where: 'priority=1') }.hasMessageContaining('where: must be a map')

        // The list is unmodifiable and GINQ-ready (GR-V-07).
        assertThatThrownBy { s.all(ItTask).add(new ItTask('x', 1)) }.isInstanceOf(UnsupportedOperationException)
        def hot = GQ {
            from t in s.all(ItTask)
            where t.priority() >= 4
            orderby t.priority() in desc
            select t.topic()
        }
        assertThat(hot.toList()).containsExactly('t5', 't4')
    }

    @Test
    void takeTakesNamedArgumentsAndTheBodyFormCompletesWithTheResult() {
        Space s = space('take')
        s << new ItTask('plain', 1) << new ItTask('body', 2) << new ItTask('none', 3) << new ItTask('two', 4)

        Optional<TakenEntry<ItTask>> taken = s.take(ItTask, lease: '30s', timeout: '2s', where: [topic: 'plain'])
        assertThat(taken).isPresent()
        taken.get().renew('10s')
        s.complete(taken.get(), new ItFinding('plain', 'done'), '1h')
        assertThat(s.read(ItFinding, where: [topic: 'plain'])).isPresent()

        ItFinding result = s.take(ItTask, lease: '30s', where: [topic: 'body']) { ItTask t -> new ItFinding(t.topic(), 'from the body') }
        assertThat(result).isEqualTo(new ItFinding('body', 'from the body'))
        assertThat(s.read(ItFinding, where: [topic: 'body'])).hasValue(result)

        // A null body result completes without a result; a (entry, taken) body sees the handle.
        assertThat(s.take(ItTask, where: [topic: 'none']) { ItTask t -> null }).isNull()
        assertThat(s.take(ItTask, where: [topic: 'two']) { ItTask t, TakenEntry<ItTask> h -> h.entryId() != null ? 'seen' : 'missed' }).isEqualTo('seen')
        assertThat(s.take(ItTask, timeout: '200ms') { ItTask t -> 'never' }).isNull()
        assertThat(Fleets.await(Duration.ofSeconds(5)) { s.count(ItTask) == 0 }).as('every take completed').isTrue()
        assertThat(s.all(ItFinding)*.topic()).containsExactlyInAnyOrder('plain', 'body')
    }

    @Test
    @Timeout(60L)
    void aBodyThatThrowsLeavesTheTakeToLapseAndTheEntryReappears() {
        Space s = space('lapse')
        s << new ItTask('fragile', 1)
        assertThatThrownBy { s.take(ItTask, lease: '1s') { ItTask t -> throw new IllegalStateException('boom') } }
                .hasMessage('boom')
        assertThat(s.count(ItTask)).as('claimed entries are invisible').isEqualTo(0)
        assertThat(Fleets.await(Duration.ofSeconds(20)) { s.count(ItTask) == 1 }).as('the lease lapsed and the entry is back').isTrue()
    }

    @Test
    void useCompletesWithoutAResult() {
        Space s = space('use')
        s << new ItTask('u', 1)
        String seen = s.use(ItTask, lease: '30s') { TakenEntry<ItTask> taken -> taken.entry().topic() }
        assertThat(seen).isEqualTo('u')
        assertThat(Fleets.await(Duration.ofSeconds(5)) { s.count(ItTask) == 0 }).isTrue()
        assertThat(s.all(ItFinding)).isEmpty()
        assertThat(s.use(ItTask, timeout: '200ms') { 'never' }).isNull()
    }

    @Test
    @Timeout(60L)
    void notifyDeliversToAClosureWithKindAndFilterOptions() {
        Space s = space('notify')
        List<String> all = new CopyOnWriteArrayList<>()
        List<String> written = new CopyOnWriteArrayList<>()
        List<String> high = new CopyOnWriteArrayList<>()
        Subscription everything = s.notify(ItTask, lease: '1h') { SpaceEvent<ItTask> e -> all << "${e.kind()}:${e.entry().topic()}".toString() }
        Subscription writes = s.notify(ItTask, on: 'written') { e, ItTask t -> written << t.topic() }
        Subscription filtered = s.notify(ItTask, on: [SpaceEvent.Kind.WRITTEN], filter: { priority > 5 }) { e -> high << e.entry().topic() }

        s << new ItTask('low', 1) << new ItTask('high', 9)
        s.take(ItTask, where: [topic: 'low']) { ItTask t -> null }
        assertThat(Fleets.await(Duration.ofSeconds(20)) { all.size() >= 4 }).as(all.toString()).isTrue()
        assertThat(all).contains('WRITTEN:low', 'WRITTEN:high', 'TAKEN:low', 'COMPLETED:low')
        assertThat(written).containsExactlyInAnyOrder('low', 'high')
        assertThat(high).containsExactly('high')
        everything.renew('1h')
        [everything, writes, filtered]*.close()
    }

    @Test
    void actingViewsNeedAReplicatedSpace() {
        Space s = space('read')
        assertThat(s.acting(runtime.identity().agentIdentity('viewer'))).isNotNull()
        Space plain = [name: { 'x' }] as Space
        assertThatThrownBy { plain.acting(runtime.identity().agentIdentity('viewer')) }.hasMessageContaining('only a ReplicatedSpace')
    }
}
