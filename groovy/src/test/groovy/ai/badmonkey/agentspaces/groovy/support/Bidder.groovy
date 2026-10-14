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
import ai.badmonkey.agentspaces.agent.annotation.BidFunction
import ai.badmonkey.agentspaces.agent.annotation.SpaceTake
import groovy.transform.CompileStatic

/**
 * Prices and takes from the AUCTION space: a take on an AUCTION space is
 * refused without a bid function, so a finding that names the bid proves
 * the {@code @BidFunction} priced the entry first. Statically compiled.
 */
@CompileStatic
@AgentSpec(name = 'bidder', description = "Bids a task's priority and takes what it wins")
class Bidder {

    private volatile double lastBid = -1

    @BidFunction(space = 'bids')
    double bid(ItTask task) {
        lastBid = task.priority() * 1.5d
        lastBid
    }

    @SpaceTake(space = 'bids', lease = 'PT10M', pollTimeout = 'PT0.2S', resultSpace = 'findings')
    ItFinding win(ItTask task) {
        new ItFinding(task.topic(), "won at bid $lastBid".toString())
    }
}
