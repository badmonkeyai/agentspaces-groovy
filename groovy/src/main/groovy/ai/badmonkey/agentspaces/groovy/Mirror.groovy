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

import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.util.concurrent.ConcurrentHashMap

/**
 * Reflection over the starter's property POJO ({@code AgentSpacesProperties}
 * and its nested classes): the setters a level has, the type each takes, the
 * element type of a list, and the kebab-case key the loader binds. The fleet
 * DSL mirrors the POJO through this and nothing else, so a new starter
 * property is a new DSL keyword with no change here (GR-D-01, GR-Q-MNT-01).
 */
@CompileStatic
final class Mirror {

    private static final Map<Class<?>, Map<String, Method>> SETTERS = new ConcurrentHashMap<>()

    private Mirror() {
    }

    /** The settable properties of a level, by camel-case name. */
    static Map<String, Method> setters(Class<?> type) {
        SETTERS.computeIfAbsent(type) { Class<?> t ->
            Map<String, Method> found = new TreeMap<>()
            for (Method m : t.methods) {
                if (m.name.length() > 3 && m.name.startsWith('set') && m.parameterCount == 1
                        && !java.lang.reflect.Modifier.isStatic(m.modifiers) && m.declaringClass != Object) {
                    found.put(decapitalize(m.name.substring(3)), m)
                }
            }
            found
        }
    }

    /**
     * The setter for a keyword, or null. Two aliases, and no other sugar: a
     * keyword without the {@code Millis} suffix of a millis property stands
     * for it ({@code tick} for {@code tickMillis}), and the singular of a
     * list property adds one element ({@code group} for {@code groups},
     * {@code space} for {@code spaces}).
     */
    static Method setter(Class<?> type, String keyword) {
        Map<String, Method> all = setters(type)
        all.get(property(type, keyword))
    }

    /** The property name a keyword stands for, through the two aliases. */
    static String property(Class<?> type, String keyword) {
        Map<String, Method> all = setters(type)
        if (all.containsKey(keyword)) {
            return keyword
        }
        if (all.containsKey(keyword + 'Millis')) {
            return keyword + 'Millis'
        }
        String plural = keyword.endsWith('y') ? keyword[0..-2] + 'ies' : keyword + 's'
        if (all.containsKey(plural) && List.isAssignableFrom(valueType(all.get(plural)))) {
            return plural
        }
        keyword
    }

    /** The parameter type of a setter. */
    static Class<?> valueType(Method setter) {
        setter.parameterTypes[0]
    }

    /** The element type of a {@code List<T>} setter, or null when the setter takes no list. */
    static Class<?> elementType(Method setter) {
        if (!List.isAssignableFrom(valueType(setter))) {
            return null
        }
        Type generic = setter.genericParameterTypes[0]
        if (generic instanceof ParameterizedType) {
            Type arg = ((ParameterizedType) generic).actualTypeArguments[0]
            if (arg instanceof Class) {
                return (Class<?>) arg
            }
        }
        Object
    }

    /** Whether a type is a value the DSL takes as is: strings, numbers, booleans, enums, durations. */
    static boolean scalar(Class<?> type) {
        type.primitive || type == String || Number.isAssignableFrom(type) || type == Boolean
                || type.isEnum() || type == java.time.Duration || CharSequence.isAssignableFrom(type)
                || type == Object
    }

    /** Whether a type is a nested property group the DSL opens with a block. */
    static boolean nested(Class<?> type) {
        !scalar(type) && !List.isAssignableFrom(type) && !Map.isAssignableFrom(type) && !type.isArray()
    }

    /** {@code tickMillis} to {@code tick-millis}, as Jackson's KEBAB_CASE strategy does. */
    static String kebab(String camel) {
        StringBuilder out = new StringBuilder()
        for (char c : camel.toCharArray()) {
            if (Character.isUpperCase(c)) {
                out.append('-').append(Character.toLowerCase(c))
            } else {
                out.append(c)
            }
        }
        out.toString()
    }

    static String decapitalize(String name) {
        name.length() > 1 && Character.isUpperCase(name.charAt(1)) ? name : (name[0].toLowerCase() + name.substring(1))
    }

    /** The known names within edit distance three of a stranger, nearest first. */
    static List<String> nearest(String stranger, Collection<String> known) {
        known.collect { String k -> [k, distance(stranger.toLowerCase(), k.toLowerCase())] }
                .findAll { List pair -> ((Integer) pair[1]) <= 3 }
                .sort { List a, List b -> ((Integer) a[1]) <=> ((Integer) b[1]) ?: ((String) a[0]) <=> ((String) b[0]) }
                .collect { List pair -> (String) pair[0] }
    }

    private static int distance(String a, String b) {
        int[] prev = (0..b.length()).collect { it } as int[]
        for (int i = 1; i <= a.length(); i++) {
            int[] cur = new int[b.length() + 1]
            cur[0] = i
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            prev = cur
        }
        prev[b.length()]
    }
}
