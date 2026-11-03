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
 *
 * <p>Each form takes a second, optional block over the runtime's options,
 * which delegates to {@link FleetRuntime.Builder}: {@code participant},
 * {@code clock}, {@code scheduler}, {@code embedder}, {@code schemaRegistry},
 * and {@code identity}, the options the Java runtime exposes.
 *
 * <pre>
 * Fleet.start({
 *     bind '127.0.0.1:7591'
 *     group('research') { seeds '127.0.0.1:7590'; space 'tasks' }
 * }) {
 *     participant modelServer                 // FleetRuntime.Builder.participant
 *     clock InstantSource.system()            // FleetRuntime.Builder.clock
 * }
 * </pre>
 *
 * <p>The runtime builds the peer with the starter's build methods and no
 * Spring context, so three starter features have no hook here. The fleet
 * console and the A2A gateway are served by the starter's auto-configurations
 * ({@code AgentSpacesConsoleAutoConfiguration}, {@code AgentSpacesA2aAutoConfiguration});
 * {@code console { enabled }} and {@code a2a { enabled }} are keywords the
 * DSL accepts, and every {@code start} refuses them naming the starter, so a
 * peer never believes it serves what it does not. {@code BeaconLocator} beans
 * and {@code PeerNodeCustomizer}s are Spring beans; the runtime joins with
 * each group's seeds, multicast when enabled, and the default bootstrap chain.
 */
@CompileStatic
final class Fleet {

    private Fleet() {
    }

    /** A peer from the fleet DSL. */
    static FleetRuntime start(@DelegatesTo(value = FleetBuilder, strategy = Closure.DELEGATE_FIRST) Closure<?> dsl) {
        builder(properties(dsl)).start()
    }

    /** A peer from the fleet DSL, with the runtime's options from a block over {@link FleetRuntime.Builder}. */
    static FleetRuntime start(@DelegatesTo(value = FleetBuilder, strategy = Closure.DELEGATE_FIRST) Closure<?> dsl,
                              @DelegatesTo(value = FleetRuntime.Builder, strategy = Closure.DELEGATE_FIRST) Closure<?> runtime) {
        start(properties(dsl), runtime)
    }

    /** A peer from a YAML file whose {@code agentspaces:} section carries the starter's keys. */
    static FleetRuntime start(Path yaml) {
        builder(PropertiesLoader.yaml(yaml)).start()
    }

    /** A peer from a YAML file, with the runtime's options. */
    static FleetRuntime start(Path yaml,
                              @DelegatesTo(value = FleetRuntime.Builder, strategy = Closure.DELEGATE_FIRST) Closure<?> runtime) {
        start(PropertiesLoader.yaml(yaml), runtime)
    }

    /** A peer from the nested {@code agentspaces} subtree (kebab-case keys, lists for repeated sections). */
    static FleetRuntime start(Map<String, ?> tree) {
        builder(PropertiesLoader.map((Map<String, Object>) tree)).start()
    }

    /** A peer from the nested subtree, with the runtime's options. */
    static FleetRuntime start(Map<String, ?> tree,
                              @DelegatesTo(value = FleetRuntime.Builder, strategy = Closure.DELEGATE_FIRST) Closure<?> runtime) {
        start(PropertiesLoader.map((Map<String, Object>) tree), runtime)
    }

    /** A peer from YAML text. */
    static FleetRuntime start(String yaml) {
        builder(properties(yaml)).start()
    }

    /** A peer from YAML text, with the runtime's options. */
    static FleetRuntime start(String yaml,
                              @DelegatesTo(value = FleetRuntime.Builder, strategy = Closure.DELEGATE_FIRST) Closure<?> runtime) {
        start(properties(yaml), runtime)
    }

    /** A peer from bound properties, with the runtime's options; a null block means none. */
    static FleetRuntime start(AgentSpacesProperties properties,
                              @DelegatesTo(value = FleetRuntime.Builder, strategy = Closure.DELEGATE_FIRST) Closure<?> runtime) {
        FleetRuntime.Builder builder = builder(properties)
        if (runtime != null) {
            Closure<?> body = (Closure<?>) runtime.clone()
            body.delegate = builder
            body.resolveStrategy = Closure.DELEGATE_FIRST
            if (body.maximumNumberOfParameters > 0) {
                body.call(builder)
            } else {
                body.call()
            }
        }
        builder.start()
    }

    /**
     * The Java runtime's builder over bound properties, after the refusals
     * of {@link #refuseUnserved}; {@code start()} on it builds and starts
     * the peer.
     */
    static FleetRuntime.Builder builder(AgentSpacesProperties properties) {
        refuseUnserved(properties)
        FleetRuntime.builder(properties)
    }

    /**
     * Refuses a configuration that enables the fleet console or the A2A
     * gateway: the runtime does not serve either, the starter's
     * auto-configurations do, and a peer that silently ignored the key would
     * claim a service it does not run.
     */
    static void refuseUnserved(AgentSpacesProperties properties) {
        if (properties.console.enabled) {
            throw new IllegalArgumentException('agentspaces.console.enabled=true: the Groovy runtime (FleetRuntime) builds the peer'
                    + ' from the starter\'s build methods without Spring and does not start the fleet console; the Spring Boot'
                    + ' starter serves it (AgentSpacesConsoleAutoConfiguration), so run the console peer there and leave'
                    + ' console.enabled off in Fleet.start, FleetScript, and FleetShell')
        }
        if (properties.a2a.enabled) {
            throw new IllegalArgumentException('agentspaces.a2a.enabled=true: the Groovy runtime (FleetRuntime) does not start the'
                    + ' A2A gateway; the Spring Boot starter serves it (AgentSpacesA2aAutoConfiguration), so run the gateway'
                    + ' peer there and leave a2a.enabled off in Fleet.start, FleetScript, and FleetShell')
        }
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
