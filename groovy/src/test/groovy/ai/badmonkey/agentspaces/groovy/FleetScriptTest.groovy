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
import ai.badmonkey.agentspaces.groovy.support.ItFinding
import ai.badmonkey.agentspaces.groovy.support.ItTask
import ai.badmonkey.agentspaces.runtime.FleetRuntime
import org.codehaus.groovy.control.CompilerConfiguration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CompletableFuture

import static org.assertj.core.api.Assertions.assertThat
import static org.assertj.core.api.Assertions.assertThatThrownBy

/**
 * The script base class (GR-D-04, GR-D-06, GR-Q-REL-02): scripts run through
 * a {@code GroovyShell} with {@code FleetScript} as the base, the way
 * {@code @BaseScript} or the shell would run them.
 */
class FleetScriptTest {

    private static final String IMPORTS = '''
        import ai.badmonkey.agentspaces.groovy.support.*
        import ai.badmonkey.agentspaces.agent.annotation.*
    '''

    private static GroovyShell shell(String... args) {
        CompilerConfiguration config = new CompilerConfiguration()
        config.scriptBaseClass = FleetScript.name
        Binding binding = new Binding()
        binding.setVariable('args', args)
        new GroovyShell(FleetScriptTest.classLoader, binding, config)
    }

    @Test
    @Timeout(90L)
    void aScriptStartsAPeerBindsAnAgentServesUntilStoppedAndClosesOnTheWayOut() {
        int port = Fleets.freePort()
        FleetScript script = (FleetScript) shell().parse(IMPORTS + """
            fleet {
                bind '127.0.0.1:$port'
                tick '200ms'
                group('it-fleet') { founding 'groovy-it-fleet-v1'; space('tasks') { settleWindow '100ms' } }
            }
            bind new ai.badmonkey.agentspaces.groovy.FloorTest.DynamicSummarizer()
            serve()
            'served'
        """)
        CompletableFuture<Object> result = CompletableFuture.supplyAsync { script.run() }
        assertThat(Fleets.await(Duration.ofSeconds(20)) { script.serving }).isTrue()
        assertThat(script.bound).hasSize(1)

        FleetRuntime observer = Fleets.start(Fleets.freePort(), port)
        try {
            def tasks = observer.context().group().space('tasks')
            tasks << new ItTask('scripted', 3)
            assertThat(Fleets.await(Duration.ofSeconds(30)) { tasks.count(ItFinding) == 1 }).isTrue()
            assertThat(tasks.read(ItFinding).get().summary()).isEqualTo('dynamic summary of scripted')
        } finally {
            observer.close()
        }
        FleetRuntime runtime = script.fleet
        script.stop()
        assertThat(result.get()).isEqualTo('served')
        assertThat(runtime.isRunning()).isFalse()
        assertThat(script.serving).isFalse()
        assertThatThrownBy { script.fleet }.hasMessageContaining('call fleet { }')
    }

    @Test
    @Timeout(60L)
    void aScriptWithoutServeClosesItsPeerWhenTheBodyEndsAndASecondFleetIsRefused() {
        int port = Fleets.freePort()
        FleetScript script = (FleetScript) shell().parse(IMPORTS + """
            def runtime = fleet {
                bind '127.0.0.1:$port'
                group('it-fleet') { founding 'groovy-it-fleet-v1'; space 'tasks' }
            }
            space('tasks') << new ItTask('once', 1)
            def seen = space('tasks').count(ItTask)
            def second = null
            try { fleet { bind '127.0.0.1:${Fleets.freePort()}'; group('x') { founding 'y'; space 'z' } } } catch (IllegalStateException e) { second = e.message }
            [runtime: runtime, seen: seen, second: second, running: runtime.isRunning()]
        """)
        Map result = (Map) script.run()
        assertThat(result.seen).isEqualTo(1)
        assertThat(result.running).isEqualTo(true)
        assertThat((String) result.second).contains('already started a peer').contains("127.0.0.1:$port".toString())
        assertThat(((FleetRuntime) result.runtime).isRunning()).as('closed when the body ended').isFalse()
    }

    @Test
    @Timeout(60L)
    void theNoArgumentFleetReadsTheYamlArgument() {
        def yaml = Files.createTempFile('fleet', '.yaml')
        yaml.text = """
            agentspaces:
              bind: 127.0.0.1:${Fleets.freePort()}
              groups:
                - name: it-fleet
                  founding: groovy-it-fleet-v1
                  spaces: [{ name: tasks }]
            """.stripIndent()
        FleetScript script = (FleetScript) shell('--yaml', yaml.toString()).parse('''
            fleet()
            [path: configPath().toString(), group: group().name(), spaces: group().spaceNames()]
        ''')
        Map result = (Map) script.run()
        assertThat(result.path).isEqualTo(yaml.toString())
        assertThat(result.group).isEqualTo('it-fleet')
        assertThat(result.spaces).isEqualTo(['tasks'] as Set)
        assertThat(new FleetScript() { Object runScript() { null } }.configPath().toString()).isEqualTo('agentspaces.yaml')
    }

    /**
     * GR-Q-REL-02: stopping a script mid-take unbinds the agent and closes
     * the peer; the taken entry's lease then lapses and the entry reappears
     * to another peer, which is the binder's crash idiom.
     */
    @Test
    @Timeout(120L)
    void stoppingMidTakeLetsTheLeaseLapseSoTheEntryReappears() {
        int port = Fleets.freePort()
        FleetScript script = (FleetScript) shell().parse(IMPORTS + """
            @AgentSpec(name = 'slow')
            class Slow {
                @SpaceTake(space = 'tasks', lease = '3s', pollTimeout = 'PT0.2S')
                ItFinding take(ItTask t) { Thread.sleep(60_000); new ItFinding(t.topic, 'never') }
            }
            fleet {
                bind '127.0.0.1:$port'
                tick '200ms'
                group('it-fleet') { founding 'groovy-it-fleet-v1'; space('tasks') { settleWindow '100ms' } }
            }
            bind new Slow()
            serve()
        """)
        CompletableFuture<Object> result = CompletableFuture.supplyAsync { script.run() }
        assertThat(Fleets.await(Duration.ofSeconds(20)) { script.serving }).isTrue()
        FleetRuntime observer = Fleets.start(Fleets.freePort(), port)
        try {
            def tasks = observer.context().group().space('tasks')
            tasks << new ItTask('slow', 1)
            assertThat(Fleets.await(Duration.ofSeconds(30)) { tasks.count(ItTask) == 0 }).as('taken, so invisible').isTrue()
            script.stop()
            result.get()
            assertThat(Fleets.await(Duration.ofSeconds(30)) { tasks.count(ItTask) == 1 }).as('the lease lapsed and the task is back').isTrue()
        } finally {
            observer.close()
        }
    }
}
