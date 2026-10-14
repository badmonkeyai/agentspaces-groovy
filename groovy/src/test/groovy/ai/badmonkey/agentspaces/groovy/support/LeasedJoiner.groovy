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
import ai.badmonkey.agentspaces.agent.annotation.Part
import ai.badmonkey.agentspaces.agent.annotation.SpaceJoin
import ai.badmonkey.agentspaces.agent.join.Joined

/**
 * A {@code LEASED} join of a task (keyed by its field) and a finding (keyed by
 * its {@code topic} tag), fired from a take of the join's ticket.
 */
@AgentSpec(name = 'leased-joiner', description = 'Joins a task with its tagged finding')
class LeasedJoiner {

    @SpaceJoin(space = 'tasks', key = 'topic', mode = SpaceJoin.Mode.LEASED,
            takeLease = '2s', pollTimeout = '200ms', resultSpace = 'data',
            parts = [@Part(ItTask), @Part(value = ItFinding, keyTag = 'topic')])
    ItTally assemble(Joined joined) {
        new ItTally(joined.key(), joined.get(ItTask).priority)
    }
}
