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

import ai.badmonkey.agentspaces.agent.capability.AggregateClient
import ai.badmonkey.agentspaces.agent.capability.VoteClient
import ai.badmonkey.agentspaces.agent.remote.RemoteAction
import ai.badmonkey.agentspaces.api.space.Lease
import ai.badmonkey.agentspaces.api.space.Space
import ai.badmonkey.agentspaces.api.space.Subscription
import ai.badmonkey.agentspaces.api.space.TakenEntry
import ai.badmonkey.agentspaces.capabilities.vote.VoteCapability
import groovy.transform.CompileStatic

import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer
import java.util.function.Predicate

/**
 * The async face (SRAD section 3.5, GR-C-01): every blocking verb and client
 * wait has a twin returning a {@code CompletableFuture} on a virtual thread,
 * which Groovy 6's {@code await} accepts unchanged.
 *
 * <pre>
 * def taken = await space.takeAsync(Task, lease: '10m', timeout: '5s')
 * def (quote, score) = await(quotes.takeAsync(Quote, lease: '1m'), risk.readAsync(RiskScore, timeout: '5s'))
 * def decision = await votes.decisionAsync('m-1')
 * def estimate = await aggregate.settledAsync('epoch', ticks: 20, tolerance: 0.01)
 * </pre>
 */
@CompileStatic
final class AsyncExtensions {

    private AsyncExtensions() {
    }

    // ---- Space ------------------------------------------------------------------------------

    static <T> CompletableFuture<Optional<T>> readAsync(Space self, Map<String, ?> options, Class<T> type) {
        FleetExecutor.submit { SpaceExtensions.read(self, options, type) }
    }

    static <T> CompletableFuture<Optional<T>> readAsync(Space self, Class<T> type) {
        readAsync(self, [:], type)
    }

    static <T> CompletableFuture<Optional<T>> readAsync(Space self, Map<String, ?> options, Class<T> type, Closure<?> filter) {
        FleetExecutor.submit { SpaceExtensions.read(self, options, type, filter) }
    }

    static <T> CompletableFuture<List<T>> allAsync(Space self, Map<String, ?> options, Class<T> type) {
        FleetExecutor.submit { SpaceExtensions.all(self, options, type) }
    }

    static <T> CompletableFuture<List<T>> allAsync(Space self, Class<T> type) {
        allAsync(self, [:], type)
    }

    static <T> CompletableFuture<Optional<TakenEntry<T>>> takeAsync(Space self, Map<String, ?> options, Class<T> type) {
        FleetExecutor.submit { SpaceExtensions.take(self, options, type) }
    }

    static <T> CompletableFuture<Optional<TakenEntry<T>>> takeAsync(Space self, Class<T> type) {
        takeAsync(self, [:], type)
    }

    /** The take-and-complete form on a virtual thread; the future carries the body's result, or null when nothing matched. */
    static <T, R> CompletableFuture<R> takeAsync(Space self, Map<String, ?> options, Class<T> type, Closure<R> body) {
        FleetExecutor.submit { SpaceExtensions.take(self, options, type, body) }
    }

    static <T, R> CompletableFuture<R> takeAsync(Space self, Class<T> type, Closure<R> body) {
        takeAsync(self, [:], type, body)
    }

    static <T, R> CompletableFuture<R> useAsync(Space self, Map<String, ?> options, Class<T> type, Closure<R> body) {
        FleetExecutor.submit { SpaceExtensions.use(self, options, type, body) }
    }

    // ---- clients ----------------------------------------------------------------------------

    /**
     * The decision of a proposal, when it lands: the current decision when
     * one exists, else the first one the capability reports, under a
     * subscription of {@code lease} (default 1h) that closes with the future.
     */
    static CompletableFuture<VoteCapability.Decision> decisionAsync(VoteClient self, String proposalId, Object lease = null) {
        CompletableFuture<VoteCapability.Decision> future = new CompletableFuture<>()
        Optional<VoteCapability.Decision> now = self.decision(proposalId)
        if (now.isPresent()) {
            future.complete(now.get())
            return future
        }
        Lease subscriptionLease = lease == null ? Lease.of(Duration.ofHours(1)) : Coercions.lease(lease)
        Subscription subscription = self.capability().onDecision(
                { String id -> id == proposalId } as Predicate<String>,
                { VoteCapability.Decision d -> future.complete(d) } as Consumer<VoteCapability.Decision>,
                subscriptionLease)
        future.whenComplete { VoteCapability.Decision d, Throwable t -> subscription.close() }
        // The decision may have landed between the first look and the subscription.
        self.decision(proposalId).ifPresent { VoteCapability.Decision d -> future.complete(d) }
        future
    }

    /** The settled estimate of an epoch ({@code ticks}, {@code tolerance}, {@code timeout} as {@code settled}). */
    static CompletableFuture<OptionalDouble> settledAsync(AggregateClient self, Map<String, ?> options, String epochId) {
        FleetExecutor.submit { ClientExtensions.settled(self, options, epochId) }
    }

    static CompletableFuture<OptionalDouble> settledAsync(AggregateClient self, String epochId) {
        settledAsync(self, [:], epochId)
    }

    /** A remote action invoked on a virtual thread with a map converted through its input record. */
    static CompletableFuture<Optional<Object>> callAsync(RemoteAction self, Map<String, ?> input, Object timeout = null) {
        FleetExecutor.submit { ClientExtensions.call(self, input, timeout) }
    }
}
