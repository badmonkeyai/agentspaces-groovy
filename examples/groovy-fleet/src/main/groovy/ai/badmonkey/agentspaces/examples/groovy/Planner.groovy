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

import ai.badmonkey.agentspaces.agent.annotation.AgentSpec
import ai.badmonkey.agentspaces.agent.annotation.SpaceNotify
import ai.badmonkey.agentspaces.examples.fleet.ResearchFleet.Finding
import groovy.transform.CompileStatic

/**
 * Example 21's class-form agent: statically compiled, the Java annotations
 * on a Groovy class, checked at compile time by {@code FleetChecker} (a
 * misspelt component in {@code where} would fail this file's compilation).
 * Every finding becomes a plan.
 */
@CompileStatic(extensions = 'ai.badmonkey.agentspaces.groovy.FleetChecker')
@AgentSpec(name = 'planner', description = 'Turns each finding into the next step', goals = ['plan'])
class Planner {

    @SpaceNotify(space = 'tasks', where = ['worker!=planner'], produces = [Plan])
    Plan plan(Finding finding) {
        new Plan(finding.topic(), "draft a brief on ${finding.topic()} from: ${finding.summary()}".toString(), 'planner')
    }
}
