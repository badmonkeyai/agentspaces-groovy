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
package ai.badmonkey.agentspaces.examples.groovy

import ai.badmonkey.agentspaces.agent.remote.RemoteActions
import ai.badmonkey.agentspaces.api.space.Lease
import ai.badmonkey.agentspaces.examples.fleet.ResearchFleet
import ai.badmonkey.agentspaces.examples.fleet.ResearchFleet.Finding
import ai.badmonkey.agentspaces.examples.fleet.ResearchFleet.ResearchTask
import ai.badmonkey.agentspaces.groovy.Fleet
import ai.badmonkey.agentspaces.groovy.FleetScript
import ai.badmonkey.agentspaces.runtime.FleetRuntime
import org.codehaus.groovy.control.CompilerConfiguration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture

import static org.assertj.core.api.Assertions.assertThat

/**
 * Example 21's fleet test (SRAD GR-O-03, GR-Q-INT-01): example 02's Java
 * peer founds the research fleet; worker.groovy joins it as a script;
 * the statically compiled Planner joins on a FleetRuntime; intake.groovy
 * writes tasks from a CSV, collects the findings over a channel, and
 * writes the report; a Java caller invokes the Groovy worker's card.
 */
class GroovyFleetTest {

    private static int freePort() {
        new ServerSocket(0).withCloseable { ServerSocket s -> s.localPort }
    }

    /** Named until, because {@code await} is a Groovy 6 keyword and {@code await(x) { }} would parse as one. */
    private static boolean until(Duration timeout, Closure<Boolean> condition) {
        long deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (condition.call()) {
                return true
            }
            Thread.sleep(100)
        }
        condition.call()
    }

    /** The script with its {@code @Grab} lines removed: the test classpath already carries the artifacts. */
    private static FleetScript script(String name, String... args) {
        String source = new File("scripts/${name}").text.readLines().findAll { !it.trim().startsWith('@Grab(') }.join('\n')
        CompilerConfiguration config = new CompilerConfiguration()
        config.scriptBaseClass = FleetScript.name
        Binding binding = new Binding()
        binding.setVariable('args', args)
        (FleetScript) new GroovyShell(GroovyFleetTest.classLoader, binding, config).parse(source, name)
    }

    @Test
    @Timeout(180L)
    void aGroovyScriptWorkerAPlannerClassAndAnIntakeScriptWorkExample02sFleetBesideJava() {
        int javaPort = freePort()
        ResearchFleet.Peer java = ResearchFleet.startPeer('java-seed', javaPort, 0)
        String seed = "127.0.0.1:$javaPort"
        FleetScript worker = script('worker.groovy', freePort().toString(), seed)
        CompletableFuture<Object> serving = CompletableFuture.supplyAsync { worker.run() }
        FleetRuntime planner = null
        try {
            assertThat(until(Duration.ofSeconds(30)) { worker.serving }).isTrue()
            planner = Fleet.start {
                bind "127.0.0.1:${freePort()}"
                tick '250ms'
                security { profile 'dev-local' }
                group('research-fleet') { founding 'research-fleet-demo-v1'; seeds seed; space('tasks') { settleWindow '150ms' } }
            }
            planner.bind(new Planner())

            // The Java peer writes a task; the Groovy script takes it; the planner plans it.
            java.space().write(new ResearchTask('agentic memory', 3), Lease.of(Duration.ofMinutes(10)))
            assertThat(until(Duration.ofSeconds(60)) { !java.space().readAll(ai.badmonkey.agentspaces.api.space.Template.of(Finding), 10).isEmpty() }).isTrue()
            Finding finding = java.space().readAll(ai.badmonkey.agentspaces.api.space.Template.of(Finding), 10).first()
            assertThat(finding.worker()).isEqualTo('groovy')
            assertThat(finding.summary()).isEqualTo('researched: agentic memory (groovy)')
            def plans = planner.context().group().space('tasks')
            assertThat(until(Duration.ofSeconds(60)) { plans.count(Plan) == 1 }).isTrue()
            assertThat(plans.read(Plan).get().nextStep()).startsWith('draft a brief on agentic memory')

            // A Java caller sees the script's card and invokes its action through the space.
            RemoteActions remote = RemoteActions.over(planner.context().group(), planner.identity().peerId())
            assertThat(until(Duration.ofSeconds(30)) { remote.available().any { it.card().agent().localName() == 'groovy-summarizer' } }).isTrue()
            def action = remote.available().find { it.card().agent().localName() == 'groovy-summarizer' }
            assertThat(action.name()).contains('groovy-summarizer')
            assertThat(action.card().actions()*.name()).containsExactly('take1')
            Optional<Object> answer = action.invoke(new ResearchTask('remote call', 1), Duration.ofSeconds(60))
            assertThat(answer).hasValueSatisfying { Object f -> assertThat(((Finding) f).summary()).isEqualTo('researched: remote call (groovy)') }

            // The intake script: CSV in, findings over a channel, a Markdown report with a parsed table.
            Path report = Files.createTempFile('report', '.md')
            FleetScript intake = script('intake.groovy', 'topics.csv', report.toString(), freePort().toString(), seed)
            intake.run()
            assertThat(report.toFile().text).contains('| tuple spaces | researched: tuple spaces (groovy) | groovy |').contains('| gossip protocols |')
            assertThat(plans.count(Plan)).isGreaterThanOrEqualTo(4)
        } finally {
            worker.stop()
            serving.get()
            planner?.close()
            java.close()
        }
    }
}
