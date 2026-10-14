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

import ai.badmonkey.agentspaces.agent.annotation.AgentSpec
import groovy.transform.CompileStatic

import java.lang.reflect.Method
import java.security.MessageDigest

/**
 * The generated interface's source (SRAD section 6.3, GR-A-04): a package,
 * {@code @AgentSpec}, one annotated abstract method per clause, nothing else.
 * Every name is a validated identifier or the binary name of a real class;
 * every string attribute is an escaped literal.
 */
@CompileStatic
final class InterfaceWriter {

    static final String PACKAGE = 'ai.badmonkey.agentspaces.groovy.generated'

    private InterfaceWriter() {
    }

    /** The interface name for a model: a hash over the spec and every clause except its body. */
    static String nameFor(AgentModel model) {
        'Agent_' + hash(shape(model))
    }

    static String source(AgentModel model) {
        source(model, nameFor(model))
    }

    static String source(AgentModel model, String interfaceName) {
        Identifiers.requireIdentifier(interfaceName, 'interface name')
        StringBuilder src = new StringBuilder()
        src.append('package ').append(PACKAGE).append('\n\n')
        src.append('@').append(Identifiers.classRef(AgentSpec)).append('(')
                .append('name = ').append(Identifiers.literal(model.name))
                .append(', description = ').append(Identifiers.literal(model.description))
                .append(', goals = [').append(model.goals.collect { String g -> Identifiers.literal(g) }.join(', ')).append('])\n')
        src.append('interface ').append(interfaceName).append(' {\n')
        for (ClauseModel clause : model.clauses) {
            Map<String, Method> attributes = Attributes.of(clause.verb)
            src.append('    @').append(Identifiers.classRef(clause.verb))
            if (!clause.options.isEmpty()) {
                src.append('(').append(clause.options.collect { String k, Object v ->
                    "${k} = ${Attributes.source(attributes.get(k).returnType, v, clause.name + '.' + k)}"
                }.join(', ')).append(')')
            }
            src.append('\n    ')
            src.append(Identifiers.classRef(clause.returns)).append(' ')
                    .append(Identifiers.requireIdentifier(clause.name, 'clause name')).append('(')
            clause.cues.eachWithIndex { Class<?> cue, int i ->
                if (i > 0) {
                    src.append(', ')
                }
                src.append(Identifiers.classRef(cue)).append(' arg').append(i)
            }
            src.append(')\n')
        }
        src.append('}\n')
        src.toString()
    }

    /** What the hash covers: everything but the bodies. */
    static String shape(AgentModel model) {
        StringBuilder s = new StringBuilder()
        s.append(model.name).append('|').append(model.description).append('|').append(model.goals.join(','))
        for (ClauseModel c : model.clauses) {
            s.append('|').append(c.verb.name).append(' ').append(c.name).append(' ').append(c.cues*.name.join(','))
                    .append(' -> ').append(c.returns.name).append(' ').append(c.asMap).append(' ')
                    .append(c.options.collect { String k, Object v -> "${k}=${v}" }.join(';'))
        }
        s.toString()
    }

    private static String hash(String text) {
        MessageDigest digest = MessageDigest.getInstance('SHA-256')
        byte[] bytes = digest.digest(text.getBytes('UTF-8'))
        StringBuilder hex = new StringBuilder()
        for (int i = 0; i < 12; i++) {
            hex.append(String.format('%02x', bytes[i] & 0xff))
        }
        hex.toString()
    }
}
