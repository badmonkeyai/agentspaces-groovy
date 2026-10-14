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

import ai.badmonkey.agentspaces.agent.annotation.Part
import groovy.transform.CompileStatic

import java.lang.annotation.Annotation
import java.lang.reflect.Method

/**
 * Annotation attributes from DSL options, by reflection over the annotation
 * (GR-A-02): an unknown option fails naming the annotation's attributes; each
 * value is checked against the attribute's type and written as source by
 * {@link #source}.
 */
@CompileStatic
final class Attributes {

    private Attributes() {
    }

    static Map<String, Method> of(Class<? extends Annotation> annotation) {
        Map<String, Method> out = new TreeMap<>()
        annotation.declaredMethods.each { Method m -> out.put(m.name, m) }
        out
    }

    static void validate(Class<? extends Annotation> annotation, Map<String, ?> options, String where) {
        Map<String, Method> attributes = of(annotation)
        options.each { String k, Object v ->
            Method attribute = attributes.get(k)
            if (attribute == null) {
                List<String> near = Mirror.nearest(k, attributes.keySet())
                throw new IllegalArgumentException("${where}: unknown option '${k}' for @${annotation.simpleName}" +
                        (near ? " (did you mean ${near.join(', ')}?)" : '') + "; its attributes: ${attributes.keySet().join(', ')}")
            }
            source(attribute.returnType, v, "${where} @${annotation.simpleName}.${k}")
        }
        attributes.values().each { Method attribute ->
            if (attribute.defaultValue == null && !options.containsKey(attribute.name)) {
                throw new IllegalArgumentException("${where}: @${annotation.simpleName} needs ${attribute.name}:")
            }
        }
    }

    /** The attribute value as Groovy annotation source, or a refusal naming the attribute. */
    static String source(Class<?> type, Object value, String what) {
        if (value == null) {
            throw new IllegalArgumentException("${what} cannot be null")
        }
        if (type == String) {
            if (value instanceof CharSequence || value instanceof Enum) {
                return Identifiers.literal(value.toString())
            }
            throw new IllegalArgumentException("${what} wants a string; got ${value.getClass().simpleName} ${value}")
        }
        if (type == String[]) {
            return '[' + list(value).collect { Object v -> source(String, v, what) }.join(', ') + ']'
        }
        if (type == int || type == Integer) {
            return value instanceof Number ? String.valueOf(((Number) value).intValue()) : fail(what, 'an int', value)
        }
        if (type == long || type == Long) {
            return value instanceof Number ? String.valueOf(((Number) value).longValue()) + 'L' : fail(what, 'a long', value)
        }
        if (type == double || type == Double) {
            return value instanceof Number ? String.valueOf(((Number) value).doubleValue()) + 'd' : fail(what, 'a double', value)
        }
        if (type == boolean || type == Boolean) {
            return value instanceof Boolean ? value.toString() : fail(what, 'a boolean', value)
        }
        if (type == Class) {
            return value instanceof Class ? Identifiers.classRef((Class<?>) value) : fail(what, 'a class', value)
        }
        if (type == Class[]) {
            return '[' + list(value).collect { Object v -> source(Class, v, what) }.join(', ') + ']'
        }
        if (type.isEnum()) {
            Enum<?> constant = value instanceof Enum ? (Enum<?>) value : enumConstant(type, value, what)
            return Identifiers.classRef(type) + '.' + constant.name()
        }
        if (type.isArray() && type.componentType.isEnum()) {
            return '[' + list(value).collect { Object v -> source(type.componentType, v, what) }.join(', ') + ']'
        }
        if (type == Part[]) {
            return '[' + list(value).collect { Object v -> part(v, what) }.join(', ') + ']'
        }
        throw new IllegalArgumentException("${what}: attributes of type ${type.simpleName} are not supported by the agent DSL")
    }

    private static String part(Object value, String what) {
        if (value instanceof Class) {
            return '@' + Identifiers.classRef(Part) + '(' + Identifiers.classRef((Class<?>) value) + ')'
        }
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("${what}: each part is a class or a map with value: and the @Part attributes; got ${value.getClass().simpleName}")
        }
        Map<String, Object> attrs = new LinkedHashMap<>()
        ((Map<?, ?>) value).each { Object k, Object v -> attrs.put(String.valueOf(k), v) }
        validate(Part, attrs, what)
        Map<String, Method> parts = of(Part)
        '@' + Identifiers.classRef(Part) + '(' + attrs.collect { String k, Object v -> "${k} = ${source(parts.get(k).returnType, v, what + '.' + k)}" }.join(', ') + ')'
    }

    private static Enum<?> enumConstant(Class<?> type, Object value, String what) {
        String name = value.toString().toUpperCase(Locale.ROOT)
        Object[] constants = type.enumConstants
        Enum<?> found = (Enum<?>) constants.find { Object c -> ((Enum<?>) c).name() == name }
        if (found == null) {
            throw new IllegalArgumentException("${what} wants one of ${constants.collect { Object c -> ((Enum<?>) c).name().toLowerCase(Locale.ROOT) }}; got '${value}'")
        }
        found
    }

    private static List<?> list(Object value) {
        value instanceof Collection ? ((Collection<?>) value).toList() : (value.getClass().isArray() ? (value as List) : [value])
    }

    private static String fail(String what, String wants, Object value) {
        throw new IllegalArgumentException("${what} wants ${wants}; got ${value.getClass().simpleName} ${value}")
    }
}
