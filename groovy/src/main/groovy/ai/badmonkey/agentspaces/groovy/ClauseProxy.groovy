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

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * The object the binder binds: a {@code java.lang.reflect.Proxy} over the
 * generated interface whose handler hands each cue to its clause's body
 * (records as records, or as maps under {@code asMap}) and returns what the
 * body returns (a map converted through {@code returns:} under {@code asMap}).
 */
@CompileStatic
final class ClauseProxy implements InvocationHandler {

    private final AgentModel model
    private final Map<String, ClauseModel> byName

    private ClauseProxy(AgentModel model) {
        this.model = model
        this.byName = model.clauses.collectEntries { ClauseModel c -> [c.name, c] } as Map<String, ClauseModel>
    }

    /** The bindable object for a model. */
    static Object instance(AgentModel model) {
        Class<?> iface = InterfaceCache.forModel(model).interfaceFor(model)
        Proxy.newProxyInstance(iface.classLoader, [iface] as Class<?>[], new ClauseProxy(model))
    }

    /** The model behind a DSL agent, or null for any other object. */
    static AgentModel modelOf(Object agent) {
        if (agent != null && Proxy.isProxyClass(agent.getClass())) {
            InvocationHandler handler = Proxy.getInvocationHandler(agent)
            if (handler instanceof ClauseProxy) {
                return ((ClauseProxy) handler).model
            }
        }
        null
    }

    @Override
    Object invoke(Object proxy, Method method, Object[] args) {
        switch (method.name) {
            case 'toString': return "agent '${model.name}' (${byName.keySet().join(', ')})".toString()
            case 'hashCode': return System.identityHashCode(proxy)
            case 'equals': return proxy.is(args[0])
        }
        ClauseModel clause = byName.get(method.name)
        if (clause == null) {
            throw new UnsupportedOperationException("no clause for ${method.name}")
        }
        Object[] cues = args == null ? new Object[0] : args
        Object[] passed = new Object[cues.length]
        for (int i = 0; i < cues.length; i++) {
            passed[i] = clause.asMap && cues[i] instanceof Record ? Coercions.map((Record) cues[i]) : cues[i]
        }
        Closure<?> body = (Closure<?>) clause.body.clone()
        int arity = body.maximumNumberOfParameters
        Object[] handed = arity >= passed.length ? passed : Arrays.copyOf(passed, Math.max(arity, 0))
        Object result
        switch (handed.length) {
            case 0: result = body.call(); break
            case 1: result = body.call(handed[0]); break
            case 2: result = body.call(handed[0], handed[1]); break
            default: result = body.call(handed[0], handed[1], handed[2])
        }
        coerce(clause, result)
    }

    /**
     * What the body returned, as the binder expects it: a map through
     * {@code returns:} under {@code asMap}; a Groovy list or array as an
     * {@code Entries} fork; a GString as a String; a number widened to the
     * declared numeric return (a bid's double).
     */
    static Object coerce(ClauseModel clause, Object result) {
        if (result == null) {
            return null
        }
        if (clause.asMap && result instanceof Map && clause.returns.isRecord()) {
            return Coercions.record(clause.returns, (Map<String, ?>) result)
        }
        if ((result instanceof List || result.getClass().isArray()) && (clause.returns == Object || clause.returns == List)) {
            return ai.badmonkey.agentspaces.agent.Entries.of(result instanceof List ? (List<?>) result : (result as List))
        }
        if (result instanceof CharSequence && !(result instanceof String) && (clause.returns == String || clause.returns == Object)) {
            return result.toString()
        }
        if (result instanceof Number && (clause.returns.primitive || Number.isAssignableFrom(clause.returns))) {
            return Coercions.record(ValueBox, [value: result]).value()
        }
        result
    }

    /** A one-component record, so the number widening of {@code Coercions.record} applies to a bare value. */
    record ValueBox(double value) {}
}
