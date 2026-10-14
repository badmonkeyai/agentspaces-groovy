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

import javax.lang.model.SourceVersion

/** The grammar the generated source admits (GR-A-04): Java identifiers for names, real classes for types, escaped literals for strings. */
@CompileStatic
final class Identifiers {

    private Identifiers() {
    }

    static String requireIdentifier(String value, String what) {
        if (value == null || !SourceVersion.isIdentifier(value) || SourceVersion.isKeyword(value)) {
            throw new IllegalArgumentException("${what} must be a Java identifier; got '${value}'")
        }
        value
    }

    /** A class reference as source: the binary name of a real class, never a string from configuration. */
    static String classRef(Class<?> type) {
        if (type.isArray() || type.isAnonymousClass() || type.isLocalClass() || type.name.contains('/')) {
            throw new IllegalArgumentException("${type} cannot be named in a generated interface")
        }
        if (type.primitive) {
            return type.name
        }
        for (String part : type.name.split('\\.')) {
            for (String nested : part.split('\\$')) {
                if (nested && !SourceVersion.isIdentifier(nested) && !nested.isInteger()) {
                    throw new IllegalArgumentException("${type.name} cannot be named in a generated interface")
                }
            }
        }
        type.name.replace('$', '.')
    }

    /** A Groovy single-quoted string literal with every non-printable, quote, and backslash escaped. */
    static String literal(String value) {
        StringBuilder out = new StringBuilder("'")
        for (char c : value.toCharArray()) {
            if (c == ('\'' as char) || c == ('\\' as char) || c < (' ' as char) || c > ('~' as char)) {
                out.append(String.format('\\u%04x', (int) c))
            } else {
                out.append(c)
            }
        }
        out.append("'").toString()
    }
}
