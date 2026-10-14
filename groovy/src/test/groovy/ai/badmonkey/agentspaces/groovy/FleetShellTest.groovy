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
import ai.badmonkey.agentspaces.runtime.FleetRuntime
import ai.badmonkey.agentspaces.spring.AgentSpacesProperties
import org.junit.jupiter.api.Test

import java.nio.file.Files

import static org.assertj.core.api.Assertions.assertThat
import static org.assertj.core.api.Assertions.assertThatThrownBy

/** The shell's non-interactive half (GR-O-01, GR-Q-SEC-04): configuration, refusals, bindings, banner. */
class FleetShellTest {

    @Test
    void theShellReadsYamlTheDslOrAPathAndRefusesAServingConfiguration() {
        def yaml = Files.createTempFile('shell', '.yaml')
        yaml.text = '''
            agentspaces:
              bind: 127.0.0.1:7599
              groups: [{ name: it-fleet, founding: f, spaces: [{ name: tasks }] }]
            '''.stripIndent()
        assertThat(FleetShell.properties(['--yaml', yaml.toString()]).bind).isEqualTo('127.0.0.1:7599')
        assertThat(FleetShell.properties([yaml.toString()]).groups[0].name).isEqualTo('it-fleet')
        AgentSpacesProperties fromDsl = FleetShell.properties(['-e', "bind '127.0.0.1:7598'; group('g') { founding 'f'; space 'tasks' }"])
        assertThat(fromDsl.bind).isEqualTo('127.0.0.1:7598')
        assertThatThrownBy { FleetShell.properties(['/no/such/file.yaml']) }.hasMessageContaining('needs a configuration')

        AgentSpacesProperties serving = FleetShell.properties(['-e', "bind '127.0.0.1:7598'; console { enabled() }; group('g') { founding 'f'; space 'tasks' }"])
        assertThatThrownBy { FleetShell.refuseServing(serving) }.hasMessageContaining('console.enabled').hasMessageContaining('serves nothing')
        AgentSpacesProperties gateway = FleetShell.properties(['-e', "bind '127.0.0.1:7598'; a2a { enabled() }; group('g') { founding 'f'; space 'tasks' }"])
        assertThatThrownBy { FleetShell.refuseServing(gateway) }.hasMessageContaining('a2a.enabled')
        FleetShell.refuseServing(fromDsl)
    }

    @Test
    void theBindingsAndTheBannerNameThePeer() {
        Fleets.start(Fleets.freePort(), 0).withCloseable { FleetRuntime runtime ->
            Map<String, Object> bindings = FleetShell.bindings(runtime)
            assertThat(bindings.keySet()).containsExactly('fleet', 'spaces', 'space', 'group', 'agent', 'bind')
            assertThat(bindings.fleet).isSameAs(runtime)
            assertThat(((Closure) bindings.space).call('tasks').name()).isEqualTo('tasks')
            assertThat(((Closure) bindings.group).call().name()).isEqualTo('it-fleet')
            def worker = ((Closure) bindings.agent).call('shell-worker', { take(ai.badmonkey.agentspaces.groovy.support.ItTask, space: 'tasks') { t -> null } })
            assertThat(((Closure) bindings.bind).call(worker)).hasSize(1)
            assertThat(FleetShell.banner(runtime)).contains(runtime.identity().peerId().value()).contains('it-fleet').contains('Closing the shell closes the peer')
            assertThat(FleetShell.compilerConfiguration().compilationCustomizers).hasSize(1)
        }
    }
}
