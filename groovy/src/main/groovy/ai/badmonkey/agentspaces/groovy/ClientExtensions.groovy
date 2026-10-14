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
import ai.badmonkey.agentspaces.capabilities.aggregate.PushSumAggregate
import groovy.transform.CompileStatic

import java.time.Duration

/**
 * The typed capability clients with named arguments (GR-V-08):
 * {@code votes.propose(id: 'm-1', question: 'ship?', options: ['yes', 'no'], quorum: 2, lease: '1h')},
 * {@code votes.cast('m-1', 'yes')}, {@code aggregate.settled('epoch', ticks: 20, tolerance: 0.01, timeout: '30s')},
 * {@code action.call([item: 'x'], '30s')} with a map converted through the action's input record.
 */
@CompileStatic
final class ClientExtensions {

    private ClientExtensions() {
    }

    /** Opens a vote: {@code id}, {@code question}, {@code options}, {@code quorum} required; {@code lease} default 1h. */
    static VoteClient propose(VoteClient self, Map<String, ?> options) {
        for (String key : ['id', 'question', 'options', 'quorum']) {
            if (!options.containsKey(key)) {
                throw new IllegalArgumentException("propose needs ${key}: (id, question, options, quorum; lease optional)")
            }
        }
        self.propose(options.id.toString(), options.question.toString(),
                ((Collection<?>) options.options).collect { Object o -> o.toString() },
                ((Number) options.quorum).intValue(), leaseOr(options.lease, Duration.ofHours(1)))
    }

    /** Casts a ballot under {@code lease} (default 1h). */
    static VoteClient cast(VoteClient self, String proposalId, String option, Object lease = null) {
        self.castBallot(proposalId, option, leaseOr(lease, Duration.ofHours(1)))
    }

    /** The estimate once the epoch settles: {@code ticks} (default 8), {@code tolerance} (default 0.001), {@code timeout} (default 30s). */
    static OptionalDouble settled(AggregateClient self, Map<String, ?> options, String epochId) {
        int ticks = options.containsKey('ticks') ? ((Number) options.ticks).intValue() : 8
        double tolerance = options.containsKey('tolerance') ? ((Number) options.tolerance).doubleValue() : 0.001d
        Duration timeout = options.containsKey('timeout') ? Coercions.duration(options.timeout) : Duration.ofSeconds(30)
        self.awaitSettled(epochId, new PushSumAggregate.Settle(ticks, tolerance), timeout)
    }

    static OptionalDouble settled(AggregateClient self, String epochId) {
        settled(self, [:], epochId)
    }

    /** Invokes a remote action with a map converted through its input record; {@code timeout} default 30s. */
    static Optional<Object> call(RemoteAction self, Map<String, ?> input, Object timeout = null) {
        Object entry = Coercions.record(self.inputType(), input)
        self.invoke(entry, timeout == null ? Duration.ofSeconds(30) : Coercions.duration(timeout))
    }

    private static Lease leaseOr(Object value, Duration fallback) {
        value == null ? Lease.of(fallback) : Coercions.lease(value)
    }
}
