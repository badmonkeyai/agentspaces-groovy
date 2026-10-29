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

import ai.badmonkey.agentspaces.agent.AgentSpaces
import ai.badmonkey.agentspaces.agent.content.Manifest
import ai.badmonkey.agentspaces.content.Content
import ai.badmonkey.agentspaces.groovy.support.Fleets
import ai.badmonkey.agentspaces.runtime.FleetRuntime
import ai.badmonkey.agentspaces.runtime.PropertiesLoader
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout

import java.time.Duration

import static org.assertj.core.api.Assertions.assertThat
import static org.assertj.core.api.Assertions.assertThatThrownBy

/**
 * The content swarm through the Groovy face: two peers with {@code content { enabled }}, a
 * publish with named options, a fetch with a closure, the bytes property, and a space write
 * above the escalation bound that arrives on the other peer as an artifact.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContentExtensionsTest {

    private FleetRuntime seed
    private FleetRuntime peer

    private static Map<String, Object> withContent(Map<String, Object> props) {
        props['agentspaces.content.enabled'] = 'true'
        props['agentspaces.content.piece-size'] = '65536'
        props['agentspaces.content.escalate-above'] = '262144'
        props['agentspaces.content.store.backends[0]'] = 'memory'
        props
    }

    @BeforeAll
    void start() {
        int seedPort = Fleets.freePort()
        seed = FleetRuntime.start(PropertiesLoader.flat(withContent(Fleets.fleet(seedPort, 0, 'blobs'))))
        peer = FleetRuntime.start(PropertiesLoader.flat(withContent(Fleets.fleet(Fleets.freePort(), Fleets.port(seed), 'blobs'))))
    }

    @AfterAll
    void stop() {
        peer?.close()
        seed?.close()
    }

    @Test
    @Timeout(90)
    void publishWithOptionsFetchWithAClosureAndReadTheBytes() {
        Content publisher = seed.context().group().content
        byte[] data = new byte[2 * 65536 + 100]
        new Random(29).nextBytes(data)
        Manifest manifest = publisher.publish(data, name: 'checkpoint.bin', mediaType: 'application/octet-stream',
                pieceSize: '64k', attrs: [run: 42])
        assertThat(manifest.pieces()).isEqualTo(3)
        assertThat(manifest.name()).isEqualTo('checkpoint.bin')
        assertThat(manifest.attrs()).containsEntry('run', '42')

        Content fetcher = peer.context().group().content
        assertThat(Fleets.await(Duration.ofSeconds(30)) { fetcher.describe(manifest.root()).isPresent() }).isTrue()
        byte[] fetched = fetcher.fetch(manifest.root(), timeout: '60s') { it.bytes }
        assertThat(fetched).isEqualTo(data)
        assertThat(fetcher.fetch(manifest.root()).await('1s')).isTrue()
        assertThat(fetcher.fetch(manifest.root()).text).hasSize(new String(data, 'UTF-8').length())
        assertThat(Fleets.await(Duration.ofSeconds(30)) { fetcher.describe(manifest.root()).get().completeHolders() >= 2 }).isTrue()
    }

    @Test
    @Timeout(90)
    void aSpaceWriteAboveTheBoundTravelsAsAnArtifact() {
        def blobs = seed.context().group().space('blobs')
        byte[] big = new byte[300 * 1024]
        new Random(7).nextBytes(big)
        blobs << new ItBlob('big', big)
        def remote = peer.context().group().space('blobs')
        assertThat(Fleets.await(Duration.ofSeconds(60)) { !remote.all(ItBlob).isEmpty() }).isTrue()
        assertThat(remote.all(ItBlob)[0].data()).isEqualTo(big)
    }

    @Test
    void aGroupWithoutTheSwarmSaysSo() {
        AgentSpaces.GroupContext group = seed.context().group()
        assertThat(group.content).isNotNull()
        assertThatThrownBy { ContentExtensions.publish(group.content, [:], 42) }
                .isInstanceOf(IllegalArgumentException)
                .hasMessageContaining('publish takes bytes')
    }

    record ItBlob(String name, byte[] data) {}
}
