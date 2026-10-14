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
package ai.badmonkey.agentspaces.groovy.support

import ai.badmonkey.agentspaces.runtime.FleetRuntime
import ai.badmonkey.agentspaces.runtime.PropertiesLoader
import groovy.transform.CompileStatic

import java.time.Duration
import java.util.function.BooleanSupplier

/** Builds test runtimes the way the sibling projects' suites do: one founded group over TCP on loopback. */
@CompileStatic
final class Fleets {

    private Fleets() {
    }

    static int freePort() {
        new ServerSocket(0).withCloseable { ServerSocket s -> s.localPort }
    }

    /** The it-fleet property set: one founded group with tasks, findings, and data spaces, plus extras. */
    static Map<String, Object> fleet(int port, int seedPort, String... extraSpaces) {
        Map<String, Object> props = [:]
        props['agentspaces.bind'] = "127.0.0.1:$port".toString()
        props['agentspaces.tick-millis'] = '200'
        props['agentspaces.groups[0].name'] = 'it-fleet'
        props['agentspaces.groups[0].founding'] = 'groovy-it-fleet-v1'
        int i = 0
        for (String space : (['tasks', 'findings', 'data'] + extraSpaces.toList())) {
            props["agentspaces.groups[0].spaces[$i].name".toString()] = space
            props["agentspaces.groups[0].spaces[$i].settle-window-millis".toString()] = '100'
            i++
        }
        if (seedPort > 0) {
            props['agentspaces.groups[0].seeds[0]'] = "127.0.0.1:$seedPort".toString()
        }
        props
    }

    /**
     * The it-fleet set plus a registered {@code votes} space (the vote
     * capability runs over it) and an AUCTION {@code bids} space.
     */
    static Map<String, Object> passthrough(int port, int seedPort = 0) {
        Map<String, Object> props = fleet(port, seedPort, 'votes')
        props['agentspaces.groups[0].spaces[4].name'] = 'bids'
        props['agentspaces.groups[0].spaces[4].strategy'] = 'AUCTION'
        props['agentspaces.groups[0].spaces[4].settle-window-millis'] = '300'
        props
    }

    static FleetRuntime start(int port, int seedPort, String... extraSpaces) {
        retrying(port) { int p -> FleetRuntime.start(PropertiesLoader.flat(fleet(p, seedPort, extraSpaces))) }
    }

    static FleetRuntime startPassthrough(int port, int seedPort = 0) {
        retrying(port) { int p -> FleetRuntime.start(PropertiesLoader.flat(passthrough(p, seedPort))) }
    }

    /** The port a started runtime listens on, for seeding another. */
    static int port(FleetRuntime runtime) {
        runtime.properties().bind.tokenize(':').last().toInteger()
    }

    /**
     * A free port can be taken between the probe and the bind: the first
     * attempt uses the port given, later ones a fresh port, so a seeded peer
     * should read {@link #port} from the runtime it seeds from.
     */
    private static FleetRuntime retrying(int port, Closure<FleetRuntime> start) {
        UncheckedIOException last = null
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                return start.call(attempt == 0 ? port : freePort())
            } catch (UncheckedIOException e) {
                if (!e.message.contains('cannot bind')) {
                    throw e
                }
                last = e
            }
        }
        throw last
    }

    /** Polls until the condition holds or the timeout passes. */
    static boolean await(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (condition.asBoolean) {
                return true
            }
            Thread.sleep(100)
        }
        condition.asBoolean
    }
}
