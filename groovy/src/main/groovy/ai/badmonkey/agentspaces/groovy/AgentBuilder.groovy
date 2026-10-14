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

import ai.badmonkey.agentspaces.agent.annotation.Ballot
import ai.badmonkey.agentspaces.agent.annotation.BidFunction
import ai.badmonkey.agentspaces.agent.annotation.OnDecision
import ai.badmonkey.agentspaces.agent.annotation.OnEstimate
import ai.badmonkey.agentspaces.agent.annotation.OrderedTake
import ai.badmonkey.agentspaces.agent.annotation.Propose
import ai.badmonkey.agentspaces.agent.annotation.SpaceJoin
import ai.badmonkey.agentspaces.agent.annotation.SpaceNotify
import ai.badmonkey.agentspaces.agent.annotation.SpaceReduce
import ai.badmonkey.agentspaces.agent.annotation.SpaceTake
import ai.badmonkey.agentspaces.agent.join.Joined
import ai.badmonkey.agentspaces.capabilities.aggregate.PushSumAggregate
import ai.badmonkey.agentspaces.capabilities.vote.VoteCapability
import groovy.transform.CompileStatic

import java.lang.annotation.Annotation

/**
 * The agent DSL (SRAD section 3.3, GR-A-01 to GR-A-07): one clause per verb,
 * each naming the cue type, the verb's attributes as named arguments in
 * camel case, and a closure body.
 *
 * <pre>
 * def pricer = agent('pricer', description: 'Prices an order', goals: ['price orders']) {
 *     take(PriceRequest, space: 'tasks', lease: '10m', returns: Quote) { PriceRequest r -> new Quote(...) }
 *     ballot(space: 'votes') { p -> p.question().contains('ship') ? 'approve' : null }
 *     notify(Quote, space: 'tasks', resultSpace: 'ledger', produces: [Receipt]) { Quote q -> new Receipt(q.item()) }
 *     join(space: 'work', key: 'topic', parts: [[value: Task], [value: Finding, optional: true]], mode: 'ordered') { Joined j -> ... }
 *     reduce(Finding, Task, space: 'ledger', key: 'topic') { Finding acc, Task t -> ... }
 * }
 * runtime.bind(pricer)
 * </pre>
 *
 * Beyond the annotation's attributes a clause takes {@code name:} (the
 * method name; default the verb plus an index), {@code returns:} (the
 * declared return type; default {@code Object}, {@code String} for ballot
 * and propose, {@code double} for bid, the accumulator for reduce), and
 * {@code asMap: true} (the body receives record cues as maps and may return
 * a map, converted through {@code returns:}). The result is a
 * {@code java.lang.reflect.Proxy} over a generated annotated interface,
 * which the Java binder binds as it binds any object.
 */
@CompileStatic
class AgentBuilder {

    static final Set<String> CLAUSE_OPTIONS = ['name', 'returns', 'asMap'] as Set

    private final String name
    private final String description
    private final List<String> goals
    private final List<ClauseModel> clauses = []

    AgentBuilder(String name, Map<String, ?> spec = [:]) {
        if (!name) {
            throw new IllegalArgumentException('an agent needs a name')
        }
        Set<String> unknown = (spec.keySet() as Set<String>) - (['description', 'goals'] as Set<String>)
        if (unknown) {
            throw new IllegalArgumentException("agent '${name}': unknown option ${unknown.sort()}; an agent takes description: and goals:")
        }
        this.name = name
        this.description = spec.description == null ? '' : spec.description.toString()
        this.goals = spec.goals == null ? [] : (spec.goals instanceof Collection ? ((Collection<?>) spec.goals).collect { Object g -> g.toString() } : [spec.goals.toString()])
    }

    /** Applies the clauses closure. */
    AgentBuilder apply(@DelegatesTo(value = AgentBuilder, strategy = Closure.DELEGATE_FIRST) Closure<?> dsl) {
        Closure<?> body = (Closure<?>) dsl.clone()
        body.delegate = this
        body.resolveStrategy = Closure.DELEGATE_FIRST
        if (body.maximumNumberOfParameters > 0) {
            body.call(this)
        } else {
            body.call()
        }
        this
    }

    AgentModel model() {
        if (clauses.isEmpty()) {
            throw new IllegalArgumentException("agent '${name}' has no clauses; add take, notify, bid, ballot, onDecision, orderedTake, onEstimate, propose, join, or reduce")
        }
        new AgentModel(name, description, goals, List.copyOf(clauses))
    }

    // ---- verbs -----------------------------------------------------------------------------

    AgentBuilder take(Map<String, ?> options = [:], Class<?> cue, Closure<?> body) {
        clause(SpaceTake, options, [cue], Object, body)
    }

    AgentBuilder notify(Map<String, ?> options = [:], Class<?> cue, Closure<?> body) {
        clause(SpaceNotify, options, [cue], Object, body)
    }

    AgentBuilder bid(Map<String, ?> options = [:], Class<?> cue, Closure<?> body) {
        clause(BidFunction, options, [cue], double, body)
    }

    /** The cue is the proposal itself. */
    AgentBuilder ballot(Map<String, ?> options = [:], Closure<?> body) {
        clause(Ballot, options, [VoteCapability.Proposal], String, body)
    }

    AgentBuilder ballot(Map<String, ?> options = [:], Class<?> cue, Closure<?> body) {
        clause(Ballot, options, [cue], String, body)
    }

    /** The cue is the decision. */
    AgentBuilder onDecision(Map<String, ?> options = [:], Closure<?> body) {
        clause(OnDecision, options, [VoteCapability.Decision], Object, body)
    }

    AgentBuilder onDecision(Map<String, ?> options = [:], Class<?> cue, Closure<?> body) {
        clause(OnDecision, options, [cue], Object, body)
    }

    AgentBuilder orderedTake(Map<String, ?> options = [:], Class<?> cue, Closure<?> body) {
        clause(OrderedTake, options, [cue], Object, body)
    }

    /** The cue is the estimate. */
    AgentBuilder onEstimate(Map<String, ?> options = [:], Closure<?> body) {
        clause(OnEstimate, options, [PushSumAggregate.Estimate], Object, body)
    }

    AgentBuilder onEstimate(Map<String, ?> options = [:], Class<?> cue, Closure<?> body) {
        clause(OnEstimate, options, [cue], Object, body)
    }

    /** The body returns the question; {@code options:} and {@code quorum:} are required by the annotation. */
    AgentBuilder propose(Map<String, ?> options = [:], Class<?> cue, Closure<?> body) {
        clause(Propose, options, [cue], String, body)
    }

    /** The cue is the {@code Joined}; {@code key:} and {@code parts:} are required by the annotation. */
    AgentBuilder join(Map<String, ?> options = [:], Closure<?> body) {
        clause(SpaceJoin, options, [Joined], Object, body)
    }

    AgentBuilder join(Map<String, ?> options = [:], Class<?> cue, Closure<?> body) {
        clause(SpaceJoin, options, [cue], Object, body)
    }

    /** The accumulator type, then the element type; the body receives both and returns the next accumulator. */
    AgentBuilder reduce(Map<String, ?> options = [:], Class<?> accumulator, Class<?> element, Closure<?> body) {
        clause(SpaceReduce, options, [accumulator, element], accumulator, body)
    }

    // ---- the clause ------------------------------------------------------------------------

    private AgentBuilder clause(Class<? extends Annotation> verb, Map<String, ?> options, List<Class<?>> cues,
                                Class<?> defaultReturn, Closure<?> body) {
        Objects.requireNonNull(body, 'a clause needs a body closure')
        cues.each { Class<?> c -> Objects.requireNonNull(c, 'a clause needs its cue type') }
        Map<String, Object> attributes = new LinkedHashMap<>()
        options.each { String k, Object v ->
            if (!CLAUSE_OPTIONS.contains(k)) {
                attributes.put(k, v)
            }
        }
        Attributes.validate(verb, attributes, "agent '${name}'")
        Object returnsOption = options.get('returns')
        Class<?> returns = defaultReturn
        if (returnsOption != null) {
            if (!(returnsOption instanceof Class)) {
                throw new IllegalArgumentException("agent '${name}': returns: takes a class; got ${returnsOption.getClass().simpleName}")
            }
            returns = (Class<?>) returnsOption
        }
        boolean asMap = Where.truth(options.get('asMap'))
        if (asMap && (returns == Object)) {
            throw new IllegalArgumentException("agent '${name}': asMap: true needs returns: to name the record a returned map converts to, or produces: with one type")
        }
        String methodName = options.get('name') == null
                ? Mirror.decapitalize(verb.simpleName.replace('Space', '')) + (clauses.size() + 1)
                : options.get('name').toString()
        Identifiers.requireIdentifier(methodName, "agent '${name}' clause name")
        if (clauses.any { ClauseModel c -> c.name == methodName }) {
            throw new IllegalArgumentException("agent '${name}' already has a clause named '${methodName}'")
        }
        clauses << new ClauseModel(verb, methodName, List.copyOf(cues), attributes, returns, asMap, body)
        this
    }
}
