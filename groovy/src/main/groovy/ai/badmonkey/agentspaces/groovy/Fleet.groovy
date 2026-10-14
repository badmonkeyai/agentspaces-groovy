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

import ai.badmonkey.agentspaces.runtime.FleetRuntime
import ai.badmonkey.agentspaces.runtime.PropertiesLoader
import ai.badmonkey.agentspaces.spring.AgentSpacesProperties
import groovy.transform.CompileStatic

import java.nio.file.Path

/**
 * Starts a peer from the starter's configuration (SRAD GR-D-01, GR-D-02):
 * the fleet DSL closure, a YAML file, YAML text, or the nested map, all
 * through {@code agentspaces-runtime}'s loader, so every form binds the same
 * {@code AgentSpacesProperties} and an unknown key fails naming it.
 */
@CompileStatic
final class Fleet {

    private Fleet() {
    }

    /** A peer from the fleet DSL. */
    static FleetRuntime start(@DelegatesTo(value = FleetBuilder, strategy = Closure.DELEGATE_FIRST) Closure<?> dsl) {
        FleetRuntime.start(properties(dsl))
    }

    /** A peer from a YAML file whose {@code agentspaces:} section carries the starter's keys. */
    static FleetRuntime start(Path yaml) {
        FleetRuntime.start(PropertiesLoader.yaml(yaml))
    }

    /** A peer from the nested {@code agentspaces} subtree (kebab-case keys, lists for repeated sections). */
    static FleetRuntime start(Map<String, ?> tree) {
        FleetRuntime.start(PropertiesLoader.map((Map<String, Object>) tree))
    }

    /** A peer from YAML text. */
    static FleetRuntime start(String yaml) {
        FleetRuntime.start(properties(yaml))
    }

    /** The DSL as the kebab-case tree the loader binds. */
    static Map<String, Object> tree(@DelegatesTo(value = FleetBuilder, strategy = Closure.DELEGATE_FIRST) Closure<?> dsl) {
        new FleetBuilder().apply(dsl).tree()
    }

    /** The DSL bound to the starter's properties. */
    static AgentSpacesProperties properties(@DelegatesTo(value = FleetBuilder, strategy = Closure.DELEGATE_FIRST) Closure<?> dsl) {
        PropertiesLoader.map(tree(dsl))
    }

    /** YAML text bound to the starter's properties. */
    static AgentSpacesProperties properties(String yaml) {
        PropertiesLoader.map(PropertiesLoader.agentspacesSubtree(PropertiesLoader.tree(new StringReader(yaml))))
    }

    /** A YAML file bound to the starter's properties. */
    static AgentSpacesProperties properties(Path yaml) {
        PropertiesLoader.yaml(yaml)
    }
}
