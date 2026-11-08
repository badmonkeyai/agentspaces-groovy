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
import ai.badmonkey.agentspaces.pdt.refs.Ref
import ai.badmonkey.agentspaces.pdt.values.PMap
import ai.badmonkey.agentspaces.pdt.values.PVector
import ai.badmonkey.agentspaces.runtime.FleetRuntime
import ai.badmonkey.agentspaces.runtime.PropertiesLoader
import org.codehaus.groovy.control.CompilerConfiguration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout

import java.time.Duration

import static org.assertj.core.api.Assertions.assertThat
import static org.assertj.core.api.Assertions.assertThatThrownBy

/**
 * The persistent trees through the Groovy face (srad/agentspaces-pdt.md PD-C-07): two peers
 * with {@code pdt { enabled }}, a swap with a closure on one, property-style reads of the map
 * on the other, the operators that return new values, a vector ref, and a script's
 * {@code pdt()}. A group without the trees says which key turns them on.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PdtExtensionsTest {

    private FleetRuntime seed
    private FleetRuntime peer

    private static Map<String, Object> withPdt(Map<String, Object> props) {
        props['agentspaces.content.enabled'] = 'true'
        props['agentspaces.content.store.backends[0]'] = 'memory'
        props['agentspaces.pdt.enabled'] = 'true'
        props['agentspaces.pdt.store.kind'] = 'memory'
        props
    }

    @BeforeAll
    void start() {
        int seedPort = Fleets.freePort()
        seed = FleetRuntime.start(PropertiesLoader.flat(withPdt(Fleets.fleet(seedPort, 0))))
        peer = FleetRuntime.start(PropertiesLoader.flat(withPdt(Fleets.fleet(Fleets.freePort(), Fleets.port(seed)))))
    }

    @AfterAll
    void stop() {
        peer?.close()
        seed?.close()
    }

    @Test
    @Timeout(90)
    void aSwapWithAClosureReadsPropertyStyleOnTheOtherPeer() {
        Pdt writer = seed.context().pdt
        Ref<PMap<Object, Object>> facts = writer.ref('facts', PMap)
        PMap<Object, Object> written = facts.swap { it + [total: 3, owner: 'ana'] }
        assertThat(written.total).isEqualTo(3)
        assertThat(written['owner']).isEqualTo('ana')
        assertThat('total' in written).isTrue()
        assertThat('missing' in written).isFalse()
        assertThat(written.missing).isNull()
        assertThat(written.size()).isEqualTo(2)

        Pdt reader = peer.context().pdt
        Ref<PMap<Object, Object>> remote = reader.ref('facts', PMap)
        assertThat(Fleets.await(Duration.ofSeconds(60)) { remote.value.total == 3 }).isTrue()
        PMap<Object, Object> seen = remote.value
        assertThat(seen.owner).isEqualTo('ana')
        assertThat(seen.root()).isEqualTo(written.root())

        // the operators return new values and leave the read one unchanged
        PMap<Object, Object> fewer = seen - 'owner'
        assertThat(fewer.owner).isNull()
        assertThat(fewer.total).isEqualTo(3)
        assertThat(seen.owner).isEqualTo('ana')
        assertThat((seen + [total: 4]).total).isEqualTo(4)
        assertThat(seen.total).isEqualTo(3)

        // a second swap on the reader lands on the writer, with the history newest first
        remote.swap { PMap<Object, Object> m -> m.assoc('total', 5) }
        assertThat(Fleets.await(Duration.ofSeconds(60)) { facts.value.total == 5 }).isTrue()
        // history walks the base links below the head: the first write is the one earlier version
        assertThat(facts.history*.root()).containsExactly(written.root())
        assertThat(facts.history[0].seq()).isLessThan(facts.heads().latest().seq())
    }

    @Test
    @Timeout(90)
    void aVectorRefIndexesAndAScriptReachesThePdt() {
        Ref<PVector<Object>> log = seed.context().pdt.ref('log', PVector)
        PVector<Object> three = log.swap { PVector<Object> v -> v.conj('a').conj('b').conj('c') }
        assertThat(three[0]).isEqualTo('a')
        assertThat(three[-1]).isEqualTo('c')
        assertThat(three.size()).isEqualTo(3)
        Ref<PVector<Object>> remote = peer.context().pdt.ref('log', PVector)
        assertThat(Fleets.await(Duration.ofSeconds(60)) { remote.value.size() == 3 }).isTrue()
        assertThat(remote.value[1]).isEqualTo('b')

        // a script joins as a third peer with the DSL's pdt { enabled } and reads the vector through pdt()
        CompilerConfiguration config = new CompilerConfiguration()
        config.scriptBaseClass = FleetScript.name
        Binding binding = new Binding()
        binding.setVariable('args', new String[0])
        int port = Fleets.freePort()
        FleetScript script = (FleetScript) new GroovyShell(PdtExtensionsTest.classLoader, binding, config).parse("""
            fleet {
                bind '127.0.0.1:$port'
                tick '200ms'
                group('it-fleet') { founding 'groovy-it-fleet-v1'; seeds '127.0.0.1:${Fleets.port(seed)}'; space('tasks') { settleWindow '100ms' } }
                content { enabled; store { backends 'memory' } }
                pdt { enabled; store { kind 'memory' } }
            }
            def log = pdt().ref('log', ai.badmonkey.agentspaces.pdt.values.PVector)
            def seen = null
            for (int i = 0; i < 300 && seen == null; i++) {
                def v = log.value
                if (v.size() == 3) { seen = v[2] } else { Thread.sleep(200) }
            }
            stop()
            seen
        """)
        assertThat(script.run()).isEqualTo('c')
    }

    @Test
    void aGroupWithoutTheTreesNamesTheKey() {
        FleetRuntime plain = Fleets.start(Fleets.freePort(), 0)
        try {
            assertThatThrownBy { plain.context().pdt }
                    .isInstanceOf(IllegalStateException)
                    .hasMessageContaining('agentspaces.pdt.enabled=true')
            assertThat(plain.pdts().isEmpty()).isTrue()
        } finally {
            plain.close()
        }
    }
}
