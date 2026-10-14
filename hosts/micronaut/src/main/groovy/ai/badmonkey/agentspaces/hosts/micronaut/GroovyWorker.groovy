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

import ai.badmonkey.agentspaces.agent.annotation.SpaceTake
import ai.badmonkey.agentspaces.micronaut.SpaceAgent
import jakarta.inject.Singleton

import java.util.concurrent.CopyOnWriteArrayList

/** A Groovy bean the Micronaut library must enroll: @SpaceAgent as the stereotype, the Java verb on the method. */
@Singleton
@SpaceAgent(name = 'mn-groovy-worker', description = 'Works tasks from a Groovy Micronaut bean', goals = ['work'])
class GroovyWorker {

    record MnTask(String topic, int priority) {}

    record MnFinding(String topic, String summary) {}

    final List<String> worked = new CopyOnWriteArrayList<>()

    @SpaceTake(space = 'tasks', lease = 'PT10M', pollTimeout = 'PT0.2S', resultSpace = 'findings')
    MnFinding work(MnTask task) {
        worked << task.topic()
        new MnFinding(task.topic(), "done: ${task.topic()}".toString())
    }
}
