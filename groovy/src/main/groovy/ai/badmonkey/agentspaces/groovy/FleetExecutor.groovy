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

import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The executor behind the async twins (SRAD GR-C-01, GR-C-02, GR-F-02): one
 * virtual thread per call. Virtual threads end with their task, so there is
 * nothing to close per runtime; a twin called on a closed space fails with
 * the space's own exception.
 */
@CompileStatic
final class FleetExecutor {

    private static volatile ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()

    private FleetExecutor() {
    }

    /** Runs a blocking call on a virtual thread; failures complete the future exceptionally with the Java exception. */
    static <T> CompletableFuture<T> submit(Callable<T> call) {
        CompletableFuture<T> future = new CompletableFuture<>()
        executor.execute {
            try {
                future.complete(call.call())
            } catch (Throwable t) {
                future.completeExceptionally(t)
            }
        }
        future
    }

    /** Replaces the executor (tests, or a host that wants its own); the default is virtual threads. */
    static void use(ExecutorService replacement) {
        executor = Objects.requireNonNull(replacement, 'executor')
    }

    static ExecutorService current() {
        executor
    }
}
