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
package ai.badmonkey.agentspaces.groovy.concurrent

import ai.badmonkey.agentspaces.agent.capability.AggregateClient
import ai.badmonkey.agentspaces.agent.capability.VoteClient
import ai.badmonkey.agentspaces.capabilities.vote.VoteCapability
import ai.badmonkey.agentspaces.groovy.AsyncExtensions
import groovy.concurrent.DataflowVariable
import groovy.transform.CompileStatic

/**
 * Dataflow twins (GR-C-07): a {@code DataflowVariable} bound once from a
 * decision or a settled estimate, so dataflow-style code awaits a vote or an
 * epoch like any other single-assignment variable.
 */
@CompileStatic
final class Dataflows {

    private Dataflows() {
    }

    static DataflowVariable<VoteCapability.Decision> decisionVar(VoteClient self, String proposalId, Object lease = null) {
        DataflowVariable<VoteCapability.Decision> variable = new DataflowVariable<>()
        AsyncExtensions.decisionAsync(self, proposalId, lease).whenComplete { VoteCapability.Decision d, Throwable t ->
            if (t != null) {
                variable.bindError(t)
            } else {
                variable.bind(d)
            }
        }
        variable
    }

    static DataflowVariable<Double> settledVar(AggregateClient self, Map<String, ?> options, String epochId) {
        DataflowVariable<Double> variable = new DataflowVariable<>()
        AsyncExtensions.settledAsync(self, options, epochId).whenComplete { OptionalDouble value, Throwable t ->
            if (t != null) {
                variable.bindError(t)
            } else if (value.isPresent()) {
                variable.bind(value.asDouble)
            } else {
                variable.bindError(new java.util.concurrent.TimeoutException("epoch ${epochId} did not settle in time".toString()))
            }
        }
        variable
    }

    static DataflowVariable<Double> settledVar(AggregateClient self, String epochId) {
        settledVar(self, [:], epochId)
    }
}
