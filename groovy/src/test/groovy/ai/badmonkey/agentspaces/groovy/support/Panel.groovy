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
package ai.badmonkey.agentspaces.groovy.support

import ai.badmonkey.agentspaces.agent.annotation.AgentSpec
import ai.badmonkey.agentspaces.agent.annotation.Ballot
import ai.badmonkey.agentspaces.agent.annotation.CapabilityRef
import ai.badmonkey.agentspaces.agent.annotation.OnDecision
import ai.badmonkey.agentspaces.agent.annotation.Propose
import ai.badmonkey.agentspaces.agent.capability.VoteClient
import ai.badmonkey.agentspaces.capabilities.vote.VoteCapability

/**
 * One object that proposes, votes, and records: a task opens a vote, the
 * ballot approves it, and the decision lands as a finding that carries the
 * tally read through the injected {@code @CapabilityRef} client. The tally
 * is a Java map inside a GString, so Groovy renders it as
 * {@code [approve:1, deny:0]} where Java prints {@code {approve=1, deny=0}}
 * (compatibility table, GR-B-04).
 */
@AgentSpec(name = 'panel', description = 'Opens, votes on, and records a vote per task')
class Panel {

    @CapabilityRef
    VoteClient votes

    @Propose(space = 'tasks', vote = 'votes', prefix = 'task:', key = 'topic',
            options = ['approve', 'deny'], quorum = 1)
    String open(ItTask task) {
        "Approve ${task.topic}?"
    }

    @Ballot(space = 'votes', prefix = 'task:')
    String judge(VoteCapability.Proposal proposal) {
        'approve'
    }

    @OnDecision(space = 'votes', prefix = 'task:', resultSpace = 'findings')
    ItFinding record(VoteCapability.Decision decision) {
        new ItFinding(decision.proposalId(),
                "decided: ${decision.winner()} tally ${votes.tally(decision.proposalId())}")
    }
}
