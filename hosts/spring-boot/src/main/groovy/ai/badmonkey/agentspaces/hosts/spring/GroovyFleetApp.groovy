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

import ai.badmonkey.agentspaces.agent.annotation.SpaceNotify
import ai.badmonkey.agentspaces.agent.annotation.SpaceRef
import ai.badmonkey.agentspaces.agent.annotation.SpaceTake
import ai.badmonkey.agentspaces.api.space.Space
import ai.badmonkey.agentspaces.spring.SpaceAgent
import groovy.transform.CompileStatic
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.stereotype.Component

import java.util.concurrent.CopyOnWriteArrayList

/**
 * A Spring Boot application written in Groovy whose only AgentSpaces
 * knowledge is the starter, its properties, and two annotated beans: one
 * dynamic, one statically compiled. The post-processor must enroll both.
 */
@SpringBootApplication
class GroovyFleetApp {

    record HostTask(String topic, int priority) {}

    record HostFinding(String topic, String summary) {}

    record HostNote(String topic, String note) {}

    @Component
    @SpaceAgent(name = 'groovy-researcher', description = 'Researches topics from a Groovy bean', goals = ['research'])
    static class Researcher {
        final List<String> worked = new CopyOnWriteArrayList<>()

        @SpaceRef('findings')
        Space findings

        @SpaceTake(space = 'tasks', lease = 'PT10M', pollTimeout = 'PT0.2S', resultSpace = 'findings')
        HostFinding research(HostTask task) {
            worked << task.topic
            findings << new HostNote(task.topic, 'written through the Groovy extension module')
            new HostFinding(task.topic, "done: ${task.topic}")
        }
    }

    @Component
    @CompileStatic
    @SpaceAgent(name = 'groovy-auditor', description = 'Audits findings statically', goals = ['audit'])
    static class Auditor {
        final List<String> audited = new CopyOnWriteArrayList<>()

        @SpaceNotify(space = 'findings')
        void audit(HostFinding finding) {
            audited << finding.topic()
        }
    }
}
