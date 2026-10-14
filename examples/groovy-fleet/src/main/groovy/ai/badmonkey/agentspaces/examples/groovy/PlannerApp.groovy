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

import ai.badmonkey.agentspaces.groovy.Fleet
import ai.badmonkey.agentspaces.runtime.FleetRuntime
import groovy.transform.CompileStatic

import java.nio.file.Path

/** Runs the planner as a peer from agentspaces.yaml: {@code java -cp ... PlannerApp [agentspaces.yaml]}. */
@CompileStatic
final class PlannerApp {

    static void main(String[] args) {
        FleetRuntime runtime = Fleet.start(Path.of(args ? args[0] : 'agentspaces.yaml'))
        runtime.bind(new Planner())
        println "planner on ${runtime.properties().bind}; Ctrl-C to stop"
        Runtime.runtime.addShutdownHook(new Thread({ -> runtime.close() } as Runnable))
        Thread.currentThread().join()
    }
}
