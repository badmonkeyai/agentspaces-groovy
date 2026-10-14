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

import groovy.transform.CompileStatic

/**
 * The agent DSL's entry point (SRAD section 3.3). {@code FleetScript} and the
 * shell expose it as {@code agent}; anywhere else call {@code Agents.agent}.
 */
@CompileStatic
final class Agents {

    private Agents() {
    }

    /** {@code agent('pricer', description: '...', goals: [...]) { clauses }}. */
    static Object agent(Map<String, ?> spec, String name,
                        @DelegatesTo(value = AgentBuilder, strategy = Closure.DELEGATE_FIRST) Closure<?> clauses) {
        ClauseProxy.instance(new AgentBuilder(name, spec ?: [:]).apply(clauses).model())
    }

    static Object agent(String name, @DelegatesTo(value = AgentBuilder, strategy = Closure.DELEGATE_FIRST) Closure<?> clauses) {
        agent([:], name, clauses)
    }

    /** {@code agent(name: 'pricer', description: '...') { clauses }}. */
    static Object agent(Map<String, ?> spec, @DelegatesTo(value = AgentBuilder, strategy = Closure.DELEGATE_FIRST) Closure<?> clauses) {
        Map<String, ?> given = spec ?: [:]
        Map<String, Object> rest = new LinkedHashMap<>()
        given.each { String k, Object v -> if (k != 'name') { rest.put(k, v) } }
        agent(rest, given.name == null ? null : given.name.toString(), clauses)
    }

    /** The model behind a DSL agent, for tests and tools; null for any other object. */
    static AgentModel modelOf(Object agent) {
        ClauseProxy.modelOf(agent)
    }
}
