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
package ai.badmonkey.agentspaces.hosts.micronaut

import ai.badmonkey.agentspaces.agent.AgentSpaces
import ai.badmonkey.agentspaces.api.space.Lease
import ai.badmonkey.agentspaces.api.space.Template
import ai.badmonkey.agentspaces.micronaut.enroll.FleetEnroller
import io.micronaut.context.ApplicationContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

import java.time.Duration

import static org.assertj.core.api.Assertions.assertThat

/** GR-Q-INT-03, Micronaut: a Groovy bean compiled through micronaut-inject-groovy is enrolled by the library. */
class MicronautGroovyPassthroughTest {

    private static int freePort() {
        new ServerSocket(0).withCloseable { ServerSocket s -> s.localPort }
    }

    @Test
    @Timeout(120L)
    void theLibraryEnrollsAGroovyBean() {
        ApplicationContext context = ApplicationContext.run([
                'agentspaces.bind': "127.0.0.1:${freePort()}".toString(),
                'agentspaces.tick-millis': '200',
                'agentspaces.groups[0].name': 'it-fleet',
                'agentspaces.groups[0].founding': 'groovy-micronaut-it-v1',
                'agentspaces.groups[0].spaces[0].name': 'tasks',
                'agentspaces.groups[0].spaces[0].settle-window-millis': '100',
                'agentspaces.groups[0].spaces[1].name': 'findings',
                'agentspaces.groups[0].spaces[1].settle-window-millis': '100'] as Map<String, Object>)
        context.withCloseable {
            assertThat(context.getBean(FleetEnroller).enrolled()).singleElement().isInstanceOf(GroovyWorker)
            AgentSpaces spaces = context.getBean(AgentSpaces)
            def tasks = spaces.group('it-fleet').space('tasks')
            tasks.write(new GroovyWorker.MnTask('micronaut groovy', 1), Lease.of(Duration.ofMinutes(10)))
            assertThat(spaces.group('it-fleet').space('findings').read(Template.of(GroovyWorker.MnFinding), Duration.ofSeconds(20)))
                    .hasValueSatisfying { f -> assertThat(f.summary()).isEqualTo('done: micronaut groovy') }
            assertThat(context.getBean(GroovyWorker).worked).containsExactly('micronaut groovy')
            assertThat(tasks.count(GroovyWorker.MnTask)).isEqualTo(0)
        }
    }
}
