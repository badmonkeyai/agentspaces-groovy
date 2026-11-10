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

import ai.badmonkey.agentspaces.groovy.support.Fleets
import ai.badmonkey.agentspaces.pdt.Pdt
import ai.badmonkey.agentspaces.pdt.stores.Stores
import ai.badmonkey.agentspaces.pdt.values.PMap
import ai.badmonkey.agentspaces.runtime.FleetRuntime
import ai.badmonkey.agentspaces.runtime.PropertiesLoader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

import java.time.Duration

import static org.assertj.core.api.Assertions.assertThat
import static org.assertj.core.api.Assertions.assertThatThrownBy

/**
 * Pin and status on the content stores through the Groovy face (PDT 2 phase 7, P2-C-04): one runtime is a
 * content store, started by the fleet DSL's {@code store { enabled }}; the other writes a map ref, pins its
 * root with {@code pdt.pin(root, copies: 1, lease: '2h')}, and reads the store's receipt with
 * {@code pdt.status(root)}, over real sockets.
 */
class PdtStoreExtensionsTest {

    private static Map<String, Object> withPdt(Map<String, Object> props) {
        props['agentspaces.content.enabled'] = 'true'
        props['agentspaces.content.store.backends[0]'] = 'memory'
        props['agentspaces.pdt.enabled'] = 'true'
        props['agentspaces.pdt.store.kind'] = 'memory'
        props
    }

    @Test
    @Timeout(180)
    void aMemberPinsARootOnAStoreAndReadsItsStatus() {
        int seedPort = Fleets.freePort()
        FleetRuntime store = Fleet.start {
            bind "127.0.0.1:$seedPort"
            tick '200ms'
            group('it-fleet') { founding 'groovy-it-fleet-v1'; space('tasks') { settleWindow '100ms' } }
            content { enabled; store { backends 'memory' } }
            pdt { enabled; store { kind 'memory' } }
            store { enabled; name 'site-a'; capacity '64MiB'; acceptRequesters 'open' }
        }
        FleetRuntime member = FleetRuntime.start(PropertiesLoader.flat(withPdt(Fleets.fleet(Fleets.freePort(), seedPort))))
        try {
            String storePeer = store.identity().peerId().value()
            Pdt pdt = member.context().pdt
            PMap<Object, Object> written = pdt.ref('invoices', PMap).swap { it + ['inv-1': 'paid'] }
            String root = written.root()
            assertThat(pdt.status(root)).isEqualTo([root: root, copies: 0, holders: [], unplaced: false])

            Stores.Pinning pinned = null
            assertThat(Fleets.await(Duration.ofSeconds(60)) {
                try {
                    pinned = pdt.pin(root, copies: 1, lease: '2h')
                    true
                } catch (IllegalStateException noCardYet) {
                    false
                }
            }).as('the store card reaches the member').isTrue()
            assertThat(pinned.pin().root()).isEqualTo(root)
            assertThat(pinned.pin().lease()).isEqualTo(7200L)
            assertThat(pinned.chosen()*.issuer()*.value()).containsExactly(storePeer)

            assertThat(Fleets.await(Duration.ofSeconds(60)) { pdt.status(root).copies == 1 }).isTrue()
            Map<String, Object> status = pdt.status(root)
            Map<String, Object> holder = ((List<Map<String, Object>>) status.holders)[0]
            assertThat(holder.store).isEqualTo(storePeer)
            assertThat((long) holder.bytes).isPositive()
            assertThat((long) holder.until).isGreaterThan(System.currentTimeMillis())
            assertThat(holder.blind).isEqualTo(false)

            // the one-argument form pins one copy for 30 days, and renews the root the store holds
            assertThat(pdt.pin(root).pin().lease()).isEqualTo(Duration.ofDays(30).toSeconds())
            assertThatThrownBy { pdt.pin(root, copies: 0) }.isInstanceOf(IllegalArgumentException)
        } finally {
            member.close()
            store.close()
        }
    }
}
