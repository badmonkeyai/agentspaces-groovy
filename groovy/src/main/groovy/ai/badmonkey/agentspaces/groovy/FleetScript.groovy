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

import ai.badmonkey.agentspaces.agent.AgentBinder
import ai.badmonkey.agentspaces.agent.AgentSpaces
import ai.badmonkey.agentspaces.api.space.Space
import ai.badmonkey.agentspaces.runtime.FleetContext
import ai.badmonkey.agentspaces.runtime.FleetRuntime
import groovy.transform.CompileStatic

import java.nio.file.Path
import java.util.concurrent.CountDownLatch

/**
 * The script base class (SRAD section 3.4, GR-D-04): one {@code .groovy}
 * file is a peer.
 *
 * <pre>
 * &#64;groovy.transform.BaseScript ai.badmonkey.agentspaces.groovy.FleetScript _
 *
 * fleet {
 *     bind '127.0.0.1:7591'
 *     group('research') { seeds '127.0.0.1:7590'; space 'tasks' }
 * }
 * bind new Summarizer()          // a class with the Java annotations, or an agent { }
 * serve()                        // until SIGINT or stop()
 * </pre>
 *
 * {@code fleet} starts the peer (from the DSL, a YAML path, YAML text, a
 * map, or, with no argument, the {@code --yaml} script argument or the
 * {@code AGENTSPACES_CONFIG} environment variable) and may run once per
 * script. {@code serve()} blocks until {@code stop()} or SIGINT. When the
 * script body ends, with or without {@code serve()}, the bound agents are
 * unbound and the peer closes; an in-flight take then lapses at its lease,
 * which is the binder's crash idiom.
 */
@CompileStatic
abstract class FleetScript extends Script {

    private FleetRuntime runtime
    private final List<AgentBinder.Bound> bound = []
    private final CountDownLatch stopped = new CountDownLatch(1)
    private Thread hook

    /** The script body; Groovy compiles it into this method because it is the base class's one abstract method. */
    abstract Object runScript()

    @Override
    Object run() {
        try {
            return runScript()
        } finally {
            close()
        }
    }

    // ---- starting -----------------------------------------------------------------------------

    /** Starts the peer from the fleet DSL. */
    FleetRuntime fleet(@DelegatesTo(value = FleetBuilder, strategy = Closure.DELEGATE_FIRST) Closure<?> dsl) {
        started(Fleet.start(dsl))
    }

    /** Starts the peer from a YAML file. */
    FleetRuntime fleet(Path yaml) {
        started(Fleet.start(yaml))
    }

    /** Starts the peer from YAML text, or from a YAML file when the text names one that exists. */
    FleetRuntime fleet(String yamlOrPath) {
        Path path = Path.of(yamlOrPath)
        started(java.nio.file.Files.isRegularFile(path) ? Fleet.start(path) : Fleet.start(yamlOrPath))
    }

    /** Starts the peer from the nested configuration map. */
    FleetRuntime fleet(Map<String, ?> tree) {
        started(Fleet.start(tree))
    }

    /**
     * Starts the peer from the {@code --yaml <path>} script argument, else
     * the {@code AGENTSPACES_CONFIG} environment variable (a path), else
     * {@code agentspaces.yaml} in the working directory.
     */
    FleetRuntime fleet() {
        started(Fleet.start(configPath()))
    }

    private FleetRuntime started(FleetRuntime started) {
        if (runtime != null) {
            started.close()
            throw new IllegalStateException("this script already started a peer (bind ${runtime.properties().bind}); fleet { } runs once per script")
        }
        runtime = started
        hook = new Thread({ -> stop() } as Runnable, 'fleet-script-shutdown')
        Runtime.runtime.addShutdownHook(hook)
        started
    }

    /** The path the no-argument {@code fleet()} reads, resolved from the script arguments and the environment. */
    Path configPath() {
        List<String> arguments = scriptArguments()
        int at = arguments.indexOf('--yaml')
        if (at >= 0 && at + 1 < arguments.size()) {
            return Path.of(arguments[at + 1])
        }
        String env = System.getenv('AGENTSPACES_CONFIG')
        if (env) {
            return Path.of(env)
        }
        Path.of('agentspaces.yaml')
    }

    private List<String> scriptArguments() {
        Object args = binding.hasVariable('args') ? binding.getVariable('args') : null
        if (args instanceof String[]) {
            return (args as String[]).toList()
        }
        if (args instanceof Collection) {
            return ((Collection<?>) args).collect { Object a -> String.valueOf(a) }
        }
        []
    }

    // ---- the running peer -------------------------------------------------------------------------

    /** The running peer, or a failure naming {@code fleet}. */
    FleetRuntime getFleet() {
        if (runtime == null) {
            throw new IllegalStateException('no peer yet: call fleet { } (or fleet(yaml)) first')
        }
        runtime
    }

    /** The facade over every joined group. */
    AgentSpaces getSpaces() {
        fleet.spaces()
    }

    /** The one joined group, or the named one. */
    AgentSpaces.GroupContext group(String name = null) {
        FleetContext context = name == null ? fleet.context() : fleet.context(name)
        context.group()
    }

    /** A space of the one joined group, or of {@code groupName} when named. */
    Space space(String name, String groupName = null) {
        group(groupName).space(name)
    }

    /** {@code agent('pricer', description: '...') { take(Task, space: 'tasks') { Task t -> ... } }}: the agent DSL. */
    Object agent(Map<String, ?> spec, String name, @DelegatesTo(value = AgentBuilder, strategy = Closure.DELEGATE_FIRST) Closure<?> clauses) {
        Agents.agent(spec, name, clauses)
    }

    Object agent(String name, @DelegatesTo(value = AgentBuilder, strategy = Closure.DELEGATE_FIRST) Closure<?> clauses) {
        Agents.agent(name, clauses)
    }

    Object agent(Map<String, ?> spec, @DelegatesTo(value = AgentBuilder, strategy = Closure.DELEGATE_FIRST) Closure<?> clauses) {
        Agents.agent(spec, clauses)
    }

    /** Binds an agent (a class with the Java annotations, or an agent { }) through the Java binder. */
    List<AgentBinder.Bound> bind(Object agent) {
        List<AgentBinder.Bound> result = fleet.bind(agent)
        bound.addAll(result)
        result
    }

    /** Every agent this script bound. */
    List<AgentBinder.Bound> getBound() {
        List.copyOf(bound)
    }

    // ---- lifecycle --------------------------------------------------------------------------------

    /** Blocks until {@code stop()} or SIGINT. */
    void serve() {
        fleet
        stopped.await()
    }

    /** Blocks until {@code stop()}, SIGINT, or the timeout; returns whether the peer stopped. */
    boolean serve(Object timeout) {
        fleet
        stopped.await(Coercions.duration(timeout).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /** Unbinds every agent, closes the peer, and releases {@code serve()}. Idempotent. */
    void stop() {
        close()
        stopped.countDown()
    }

    /** Whether the peer is running. */
    boolean isServing() {
        runtime != null && runtime.isRunning()
    }

    private synchronized void close() {
        if (runtime == null) {
            return
        }
        FleetRuntime closing = runtime
        runtime = null
        for (AgentBinder.Bound b : bound) {
            try {
                b.close()
            } catch (Exception ignored) {
                // closing the runtime closes the binder too
            }
        }
        bound.clear()
        try {
            closing.close()
        } finally {
            if (hook != null && Thread.currentThread() != hook) {
                try {
                    Runtime.runtime.removeShutdownHook(hook)
                } catch (IllegalStateException ignored) {
                    // the JVM is already shutting down
                }
            }
            stopped.countDown()
        }
    }
}
