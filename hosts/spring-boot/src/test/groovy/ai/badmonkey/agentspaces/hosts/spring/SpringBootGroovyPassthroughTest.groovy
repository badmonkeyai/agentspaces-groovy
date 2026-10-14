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
package ai.badmonkey.agentspaces.hosts.spring

import ai.badmonkey.agentspaces.agent.AgentSpaces
import ai.badmonkey.agentspaces.api.space.Lease
import ai.badmonkey.agentspaces.api.space.Template
import ai.badmonkey.agentspaces.hosts.spring.GroovyFleetApp.HostFinding
import ai.badmonkey.agentspaces.hosts.spring.GroovyFleetApp.HostNote
import ai.badmonkey.agentspaces.hosts.spring.GroovyFleetApp.HostTask
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext

import java.time.Duration

import static org.assertj.core.api.Assertions.assertThat

/** GR-Q-INT-03, Spring Boot: real Spring Boot boots the starter and enrolls Groovy beans. */
class SpringBootGroovyPassthroughTest {

    private static int freePort() {
        new ServerSocket(0).withCloseable { ServerSocket s -> s.localPort }
    }

    /** Named until: await is a Groovy 6 keyword. */
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

    @Test
    @Timeout(120L)
    void theStarterEnrollsDynamicAndStaticGroovyBeans() {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(GroovyFleetApp)
                .properties([
                        'spring.main.web-application-type': 'none',
                        'agentspaces.bind': "127.0.0.1:${freePort()}".toString(),
                        'agentspaces.tick-millis': '200',
                        'agentspaces.groups[0].name': 'it-fleet',
                        'agentspaces.groups[0].founding': 'groovy-spring-it-v1',
                        'agentspaces.groups[0].spaces[0].name': 'tasks',
                        'agentspaces.groups[0].spaces[0].settle-window-millis': '100',
                        'agentspaces.groups[0].spaces[1].name': 'findings',
                        'agentspaces.groups[0].spaces[1].settle-window-millis': '100'] as Map<String, Object>)
                .run()
        context.withCloseable {
            AgentSpaces spaces = context.getBean(AgentSpaces)
            def tasks = spaces.group('it-fleet').space('tasks')
            def findings = spaces.group('it-fleet').space('findings')
            tasks.write(new HostTask('spring groovy', 1), Lease.of(Duration.ofMinutes(10)))

            assertThat(findings.read(Template.of(HostFinding), Duration.ofSeconds(20))).hasValueSatisfying { HostFinding f ->
                assertThat(f.summary()).isEqualTo('done: spring groovy')
            }
            assertThat(findings.read(Template.of(HostNote), Duration.ofSeconds(20))).isPresent()
            assertThat(context.getBean(GroovyFleetApp.Researcher).worked).containsExactly('spring groovy')
            assertThat(until(Duration.ofSeconds(20)) { context.getBean(GroovyFleetApp.Auditor).audited == ['spring groovy'] }).isTrue()
            // The extension module applies to the injected spaces.
            assertThat(tasks.all(HostTask)).isEmpty()
            assertThat(findings.count(HostFinding)).isEqualTo(1)
        }
    }
}
