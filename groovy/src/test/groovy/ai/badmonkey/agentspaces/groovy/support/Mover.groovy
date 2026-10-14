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

import ai.badmonkey.agentspaces.agent.annotation.AgentSpec
import ai.badmonkey.agentspaces.agent.annotation.SpaceNotify
import ai.badmonkey.agentspaces.agent.capability.Motion
import ai.badmonkey.agentspaces.api.space.Lease

import java.time.Duration

/** A {@code @SpaceNotify} whose {@code Motion} return opens a vote in the votes space. */
@AgentSpec(name = 'mover', description = 'Moves a vote per task')
class Mover {

    @SpaceNotify(space = 'tasks')
    Motion open(ItTask task) {
        Motion.in('votes', "motion:${task.topic}", "Move ${task.topic}?", ['yes', 'no'], 1, Lease.of(Duration.ofHours(1)))
    }
}
