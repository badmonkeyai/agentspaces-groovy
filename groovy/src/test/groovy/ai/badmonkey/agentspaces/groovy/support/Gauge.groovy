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
import ai.badmonkey.agentspaces.agent.annotation.OnEstimate
import ai.badmonkey.agentspaces.agent.annotation.SpaceNotify
import ai.badmonkey.agentspaces.agent.capability.Contribution
import ai.badmonkey.agentspaces.capabilities.aggregate.PushSumAggregate

/**
 * A task's priority is a {@code Contribution} to the epoch named by its
 * topic, and the settled estimate lands as a finding.
 */
@AgentSpec(name = 'gauge', description = "Contributes each task's priority and reports the estimate")
class Gauge {

    @SpaceNotify(space = 'tasks')
    Contribution feel(ItTask task) {
        Contribution.to("load:${task.topic}", task.priority)
    }

    @OnEstimate(prefix = 'load:', settleTicks = 2, tolerance = 0.01d, resultSpace = 'findings')
    ItFinding report(PushSumAggregate.Estimate estimate) {
        new ItFinding(estimate.epochId(), "estimate: ${Math.round(estimate.value())}")
    }
}
