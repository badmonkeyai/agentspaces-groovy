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
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.customizers.ImportCustomizer

import java.nio.file.Files
import java.nio.file.Path

/**
 * The shell (SRAD section 3.7, GR-O-01): a {@code groovysh} session over a
 * running peer, with {@code fleet} (the runtime), {@code spaces} (the
 * facade), {@code space(name)}, {@code group(name)}, {@code agent(...)},
 * {@code bind(agent)}, and the extension module in scope.
 *
 * <pre>
 * java -cp ... ai.badmonkey.agentspaces.groovy.FleetShell --yaml agentspaces.yaml
 * java -cp ... ai.badmonkey.agentspaces.groovy.FleetShell -e "bind '127.0.0.1:7591'; group('research') { seeds '127.0.0.1:7590'; space 'tasks' }"
 * fleet> space('tasks').all(ResearchTask).size()
 * fleet> space('tasks') << new ResearchTask('agentic memory', 3)
 * </pre>
 *
 * The shell runs as the configured identity and nothing more: it refuses a
 * configuration that enables the console or the A2A gateway, so it never
 * serves; closing the shell closes the peer. Needs {@code groovy-groovysh}
 * on the classpath (an optional dependency of this artifact).
 */
@CompileStatic
final class FleetShell {

    private FleetShell() {
    }

    static void main(String[] args) {
        System.exit(start(args))
    }

    /** Starts the peer named by the arguments and opens the shell; returns the shell's exit code. */
    static int start(String... args) {
        AgentSpacesProperties properties = properties(args.toList())
        refuseServing(properties)
        FleetRuntime runtime = FleetRuntime.start(properties)
        try {
            System.err.println(banner(runtime))
            return open(runtime)
        } finally {
            runtime.close()
        }
    }

    /** The configuration from {@code --yaml <path>}, {@code -e <dsl>}, a positional path, or {@code agentspaces.yaml}. */
    static AgentSpacesProperties properties(List<String> args) {
        int yaml = args.indexOf('--yaml')
        if (yaml >= 0 && yaml + 1 < args.size()) {
            return PropertiesLoader.yaml(Path.of(args[yaml + 1]))
        }
        int dsl = args.indexOf('-e')
        if (dsl >= 0 && dsl + 1 < args.size()) {
            Closure<?> body = (Closure<?>) new GroovyShell().evaluate("{ -> ${args[dsl + 1]} }")
            return Fleet.properties(body)
        }
        String positional = args.find { String a -> !a.startsWith('-') }
        Path path = Path.of(positional ?: 'agentspaces.yaml')
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("FleetShell needs a configuration: --yaml <path>, -e '<fleet dsl>', or a path; ${path} is not a file")
        }
        PropertiesLoader.yaml(path)
    }

    /** GR-O-01: the shell never serves. */
    static void refuseServing(AgentSpacesProperties properties) {
        if (properties.console.enabled) {
            throw new IllegalArgumentException('FleetShell refuses a configuration with console.enabled: a shell acts as the operator and serves nothing; run the console in its own peer')
        }
        if (properties.a2a.enabled) {
            throw new IllegalArgumentException('FleetShell refuses a configuration with a2a.enabled: a shell acts as the operator and serves nothing; run the gateway in its own peer')
        }
    }

    /** The banner naming the identity and the groups joined (GR-Q-SEC-04). */
    static String banner(FleetRuntime runtime) {
        "AgentSpaces shell: peer ${runtime.identity().peerId().value()} in ${runtime.spaces().groupNames()} at ${runtime.properties().bind ?: 'dial-only'}; " +
                'bindings: fleet, spaces, space(name), group(name), agent(name) { }, bind(agent). Closing the shell closes the peer.'
    }

    /** The bindings a shell or a script host exposes over a runtime. */
    static Map<String, Object> bindings(FleetRuntime runtime) {
        Map<String, Object> bindings = new LinkedHashMap<>()
        bindings.put('fleet', runtime)
        bindings.put('spaces', runtime.spaces())
        bindings.put('space', { String name -> runtime.context().group().space(name) })
        bindings.put('group', { String name = null -> (name == null ? runtime.context() : runtime.context(name)).group() })
        bindings.put('agent', { Object... a ->
            a.length == 3 ? Agents.agent((Map<String, ?>) a[0], (String) a[1], (Closure<?>) a[2])
                    : (a[0] instanceof Map ? Agents.agent((Map<String, ?>) a[0], (Closure<?>) a[1]) : Agents.agent((String) a[0], (Closure<?>) a[1]))
        })
        bindings.put('bind', { Object agent -> runtime.bind(agent) })
        bindings
    }

    /** The compiler configuration a shell session uses: the DSL statics and the entry-type packages imported. */
    static CompilerConfiguration compilerConfiguration() {
        CompilerConfiguration config = new CompilerConfiguration()
        ImportCustomizer imports = new ImportCustomizer()
        imports.addStaticStars(Where.name)
        imports.addImports(Fleet.name, Agents.name, 'ai.badmonkey.agentspaces.api.space.Lease',
                'ai.badmonkey.agentspaces.api.space.Template', 'java.time.Duration')
        config.addCompilationCustomizers(imports)
        config
    }

    @groovy.transform.CompileDynamic
    private static int open(FleetRuntime runtime) {
        Class<?> options
        Class<?> main
        try {
            options = Class.forName('org.apache.groovy.groovysh.GroovyshOptions')
            main = Class.forName('org.apache.groovy.groovysh.Main')
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException('FleetShell needs org.apache.groovy:groovy-groovysh on the classpath (an optional dependency of agentspaces-groovy)', e)
        }
        def built = options.builder()
                .bindings(bindings(runtime))
                .compilerConfiguration(compilerConfiguration())
                .prompt('fleet> ')
                .showBanner(false)
                .build()
        (int) main.start(built)
    }
}
