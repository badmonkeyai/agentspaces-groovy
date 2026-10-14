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

import ai.badmonkey.agentspaces.agent.annotation.AgentSpec
import ai.badmonkey.agentspaces.agent.annotation.SpaceTake
import ai.badmonkey.agentspaces.agent.remote.RemoteActions
import ai.badmonkey.agentspaces.api.space.Lease
import ai.badmonkey.agentspaces.api.space.Space
import ai.badmonkey.agentspaces.api.space.Template
import ai.badmonkey.agentspaces.groovy.support.Fleets
import ai.badmonkey.agentspaces.groovy.support.ItFinding
import ai.badmonkey.agentspaces.groovy.support.ItTask
import ai.badmonkey.agentspaces.runtime.FleetRuntime
import groovy.transform.CompileStatic
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

import java.time.Duration

import static org.assertj.core.api.Assertions.assertThat

/**
 * The floor (SRAD GR-B-01, scenario 2): a Groovy class carrying the Java
 * annotations, one statically compiled and one dynamic, binds through
 * {@code FleetRuntime.bind} and serves a peer on another JVM-side runtime,
 * with a Groovy record as the entry type a Java peer would name the same way.
 */
class FloorTest {

    @CompileStatic
    @AgentSpec(name = 'static-summarizer', description = 'Summarizes under @CompileStatic', goals = ['summarize'])
    static class StaticSummarizer {
        @SpaceTake(space = 'tasks', pollTimeout = 'PT0.2S', where = ['priority=2'])
        ItFinding summarize(ItTask task) {
            new ItFinding(task.topic(), "static summary of ${task.topic()}".toString())
        }
    }

    @AgentSpec(name = 'dynamic-summarizer', description = 'Summarizes dynamically', goals = ['summarize'])
    static class DynamicSummarizer {
        @SpaceTake(space = 'tasks', pollTimeout = 'PT0.2S', where = ['priority=3'])
        ItFinding summarize(ItTask task) {
            new ItFinding(task.topic, "dynamic summary of ${task.topic}")
        }
    }

    @Test
    @Timeout(120L)
    void groovyClassesWithTheJavaAnnotationsBindAndServeAnotherPeer() {
        FleetRuntime worker = Fleets.start(Fleets.freePort(), 0)
        FleetRuntime client = Fleets.start(Fleets.freePort(), Fleets.port(worker))
        try {
            def bound = worker.bind(new StaticSummarizer()) + worker.bind(new DynamicSummarizer())
            assertThat(bound).hasSize(2)
            assertThat(bound*.card()*.agent()*.localName()).containsExactlyInAnyOrder('static-summarizer', 'dynamic-summarizer')
            assertThat(bound[0].card().actions()).singleElement()
                    .satisfies { a -> assertThat(a.name()).isEqualTo('summarize') }

            def fleet = client.context()
            assertThat(Fleets.await(Duration.ofSeconds(30)) {
                RemoteActions.over(fleet.group(), fleet.peerId()).available().size() == 2
            }).as('both cards reach the client').isTrue()

            Space tasks = fleet.group().space('tasks')
            tasks.write(new ItTask('offsite', 2), Lease.of(Duration.ofMinutes(5)))
            tasks.write(new ItTask('retro', 3), Lease.of(Duration.ofMinutes(5)))
            assertThat(Fleets.await(Duration.ofSeconds(30)) {
                tasks.readAll(Template.of(ItFinding), 10).size() == 2
            }).isTrue()
            assertThat(tasks.readAll(Template.of(ItFinding), 10)*.summary())
                    .containsExactlyInAnyOrder('static summary of offsite', 'dynamic summary of retro')
            assertThat(tasks.readAll(Template.of(ItTask), 10)).as('both takes completed').isEmpty()
        } finally {
            client.close()
            worker.close()
        }
    }
}
