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

import ai.badmonkey.agentspaces.runtime.PropertiesLoader
import ai.badmonkey.agentspaces.spring.AgentSpacesProperties
import org.junit.jupiter.api.Test

import java.lang.reflect.Method

import static org.assertj.core.api.Assertions.assertThat

/**
 * GR-Q-MNT-01, the DSL half: every setter of the starter's property model,
 * walked recursively, is a DSL keyword whose value the loader binds back. A
 * new starter property shows up here as a keyword the builder already
 * accepts and the loader already binds; a property the loader renames would
 * fail the bind and this test.
 */
class DslParityTest {

    @Test
    void everyStarterPropertyIsADslKeywordTheLoaderBindsBack() {
        FleetBuilder root = new FleetBuilder()
        List<String> visited = []
        fill(root, AgentSpacesProperties, 'agentspaces', visited)
        Map<String, Object> tree = root.tree()
        AgentSpacesProperties bound = PropertiesLoader.map(tree)
        assertThat(bound).isNotNull()
        assertThat(visited.size()).as('properties exercised').isGreaterThan(80)
        // Spot checks that the values travelled, one per shape.
        assertThat(bound.bind).isEqualTo('sample')
        assertThat(bound.tickMillis).isEqualTo(7L)
        assertThat(bound.roles).containsExactly('sample')
        assertThat(bound.groups).hasSize(1)
        assertThat(bound.groups[0].spaces).hasSize(1)
        assertThat(bound.groups[0].spaces[0].strategy).isEqualTo('sample')
        assertThat(bound.security.grants.raftVoter).containsExactly('sample')
        assertThat(bound.transport.tls.requireAttestation).isTrue()
        assertThat(bound.console.command.enabled).isTrue()
    }

    static void fill(FleetBuilder builder, Class<?> type, String path, List<String> visited) {
        Mirror.setters(type).each { String keyword, Method setter ->
            Class<?> valueType = Mirror.valueType(setter)
            Class<?> element = Mirror.elementType(setter)
            visited << "${path}.${keyword}".toString()
            if (element != null) {
                if (Mirror.scalar(element)) {
                    builder.keyword(keyword, [sample(element)] as Object[])
                } else {
                    builder.keyword(keyword, [{ FleetBuilder child -> DslParityTest.fill(child, element, "${path}.${keyword}[0]", visited) }] as Object[])
                }
            } else if (Mirror.nested(valueType)) {
                builder.keyword(keyword, [{ FleetBuilder child -> DslParityTest.fill(child, valueType, "${path}.${keyword}", visited) }] as Object[])
            } else {
                builder.keyword(keyword, [sample(valueType)] as Object[])
            }
        }
    }

    private static Object sample(Class<?> type) {
        if (type == boolean || type == Boolean) {
            return true
        }
        if (type == double || type == Double || type == float || type == Float) {
            return 7.5d
        }
        if (Number.isAssignableFrom(type) || type.primitive) {
            return 7
        }
        if (type.isEnum()) {
            return type.enumConstants[0]
        }
        if (type == java.time.Duration) {
            return '7s'
        }
        'sample'
    }
}
