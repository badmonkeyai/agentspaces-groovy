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

import ai.badmonkey.agentspaces.spring.AgentSpacesProperties
import groovy.transform.CompileStatic

import java.lang.reflect.Method
import java.time.Duration

/**
 * The fleet DSL (SRAD section 3.2, GR-D-01): a builder whose keywords are the
 * camel-case names of the starter's properties, mirrored by reflection over
 * {@code AgentSpacesProperties}. A block opens a nested group; a repeated
 * keyword on a list property appends; a boolean keyword with no argument
 * means true; a millis property takes a duration string
 * ({@code tickMillis '250ms'}, or {@code tick '250ms'} through the alias
 * without the suffix); the singular of a list property adds one element
 * ({@code group('research') { }}, {@code space 'tasks'}). Those two aliases
 * are the only sugar. The result is the kebab-case tree
 * {@code PropertiesLoader.map} binds, so the DSL cannot drift from the starter.
 *
 * <pre>
 * Fleet.start {
 *     bind '127.0.0.1:7591'
 *     roles 'rendezvous'
 *     security { profile 'mtls'; grants { raftVoter 'z6Mk...' } }
 *     group('research') {
 *         seeds 'tls://10.0.0.5:7590', 'tls://10.0.0.6:7590'
 *         founding 'research-fleet-v1'
 *         space('tasks') { strategy 'LEASE_RACE'; admission 'credential' }
 *         space 'findings'
 *     }
 *     capabilities { orderedLog(); votesSpace 'votes' }
 * }
 * </pre>
 *
 * An unknown keyword fails at the DSL with the keyword, this level's known
 * keywords, and the nearest ones by spelling.
 */
@CompileStatic
class FleetBuilder {

    /** The POJO class this level mirrors. */
    final Class<?> type
    private final String path
    private final Map<String, Object> level = new LinkedHashMap<>()

    FleetBuilder() {
        this(AgentSpacesProperties, 'agentspaces')
    }

    FleetBuilder(Class<?> type, String path) {
        this.type = type
        this.path = path
    }

    /** Applies a DSL closure to this level. */
    FleetBuilder apply(@DelegatesTo(value = FleetBuilder, strategy = Closure.DELEGATE_FIRST) Closure<?> dsl) {
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

    /** This level as a camel-case map (nested maps and lists for the nested properties). */
    Map<String, Object> camel() {
        level
    }

    /** This level as the kebab-case tree the loader binds. */
    Map<String, Object> tree() {
        (Map<String, Object>) toKebab(level)
    }

    /** The keywords this level accepts. */
    Set<String> keywords() {
        Mirror.setters(type).keySet()
    }

    /** {@code bind = '127.0.0.1:7591'} as well as {@code bind '127.0.0.1:7591'}. */
    void propertyMissing(String name, Object value) {
        keyword(name, [value] as Object[])
    }

    Object propertyMissing(String name) {
        Method setter = Mirror.setter(type, name)
        if (setter == null) {
            // A MissingPropertyException lets a DELEGATE_FIRST closure fall back to its owner, so a
            // script variable read inside a block (`seeds seed`) resolves there; when the owner has
            // no such name either, Groovy reports this message.
            throw new MissingPropertyException(unknown(name).message, name, FleetBuilder)
        }
        Class<?> valueType = Mirror.valueType(setter)
        if (valueType == boolean || valueType == Boolean) {
            // `aggregate` on its own line reads as a property access: it means true.
            keyword(name, new Object[0])
            return true
        }
        level.get(Mirror.property(type, name))
    }

    Object methodMissing(String name, Object args) {
        if (Mirror.setter(type, name) == null) {
            // As for properties: a MissingMethodException lets the closure try its owner (a script's own
            // method called inside a block) before Groovy reports this message.
            throw new DslMissingMethodException(name, (Object[]) args, unknown(name).message)
        }
        keyword(name, (Object[]) args)
    }

    /** A missing-method failure that carries the builder's message. */
    static class DslMissingMethodException extends MissingMethodException {
        private final String detail

        DslMissingMethodException(String name, Object[] args, String detail) {
            super(name, FleetBuilder, args)
            this.detail = detail
        }

        @Override
        String getMessage() {
            detail
        }
    }

    /** The {@code identity} group; declared because Groovy gives every object an {@code identity(Closure)} method. */
    FleetBuilder identity(@DelegatesTo(value = FleetBuilder, strategy = Closure.DELEGATE_FIRST) Closure<?> block) {
        keyword('identity', [block] as Object[])
    }

    FleetBuilder identity(Map<String, ?> values) {
        keyword('identity', [values] as Object[])
    }

    /** One keyword with its arguments, validated against the mirrored class. */
    FleetBuilder keyword(String name, Object[] args) {
        Method setter = Mirror.setter(type, name)
        if (setter == null) {
            throw unknown(name)
        }
        String property = Mirror.property(type, name)
        Class<?> valueType = Mirror.valueType(setter)
        Class<?> element = Mirror.elementType(setter)
        if (element != null) {
            list(property, element, args)
        } else if (Mirror.nested(valueType)) {
            nested(property, valueType, args)
        } else if (valueType == boolean || valueType == Boolean) {
            if (args.length == 0) {
                level.put(property, true)
            } else if (args.length == 1 && (args[0] instanceof Boolean || args[0] == null)) {
                level.put(property, args[0])
            } else {
                throw new IllegalArgumentException("${path}.${name} takes true, false, or nothing (meaning true); got ${describe(args)}")
            }
        } else {
            if (args.length != 1) {
                throw new IllegalArgumentException("${path}.${name} takes one value; got ${describe(args)}")
            }
            level.put(property, scalar(name, property, valueType, args[0]))
        }
        this
    }

    private void list(String property, Class<?> element, Object[] args) {
        List<Object> target = (List<Object>) level.computeIfAbsent(property) { String k -> new ArrayList<Object>() }
        if (Mirror.scalar(element)) {
            for (Object arg : args) {
                if (arg instanceof Collection) {
                    ((Collection<?>) arg).each { Object v -> target.add(scalar(property, property, element, v)) }
                } else if (arg instanceof Closure) {
                    throw new IllegalArgumentException("${path}.${property} is a list of ${element.simpleName}; it takes values, not a block")
                } else {
                    target.add(scalar(property, property, element, arg))
                }
            }
            return
        }
        // A list of property groups: `space 'tasks'`, `space('tasks') { }`, `space { }`, `space(name: 'x')`, `space(name: 'x') { }`.
        Map<String, Object> item = new LinkedHashMap<>()
        Closure<?> block = null
        boolean named = Mirror.setters(element).containsKey('name')
        for (Object arg : args) {
            if (arg instanceof Closure) {
                block = (Closure<?>) arg
            } else if (arg instanceof Map) {
                ((Map<?, ?>) arg).each { Object k, Object v -> item.put(String.valueOf(k), v) }
            } else if (named && (arg instanceof CharSequence)) {
                item.put('name', arg.toString())
            } else {
                throw new IllegalArgumentException("${path}.${property} takes ${named ? 'a name, ' : ''}a map, or a block; got ${describe([arg] as Object[])}")
            }
        }
        FleetBuilder child = new FleetBuilder(element, "${path}.${property}[${target.size()}]")
        item.each { String k, Object v -> child.keyword(k, [v] as Object[]) }
        if (block != null) {
            child.apply(block)
        }
        target.add(child.camel())
    }

    private void nested(String property, Class<?> valueType, Object[] args) {
        FleetBuilder child = new FleetBuilder(valueType, "${path}.${property}")
        Map<String, Object> existing = (Map<String, Object>) level.get(property)
        if (existing != null) {
            child.level.putAll(existing)
        }
        for (Object arg : args) {
            if (arg instanceof Closure) {
                child.apply((Closure<?>) arg)
            } else if (arg instanceof Map) {
                ((Map<?, ?>) arg).each { Object k, Object v -> child.keyword(String.valueOf(k), [v] as Object[]) }
            } else {
                throw new IllegalArgumentException("${path}.${property} is a group of properties; it takes a block or a map, not ${describe([arg] as Object[])}")
            }
        }
        level.put(property, child.camel())
    }

    private Object scalar(String keyword, String property, Class<?> valueType, Object value) {
        if (value == null) {
            return null
        }
        if (property.endsWith('Millis') && (valueType == long || valueType == Long || valueType == int || valueType == Integer)) {
            if (value instanceof Number) {
                return value
            }
            return Coercions.duration(value).toMillis()
        }
        if (valueType == Duration) {
            return Coercions.duration(value).toString()
        }
        if (valueType.isEnum() || valueType == String || valueType == Object || CharSequence.isAssignableFrom(valueType)) {
            return value instanceof CharSequence || value instanceof Enum || value instanceof Number || value instanceof Boolean
                    ? value.toString() : fail(keyword, valueType, value)
        }
        if (valueType == boolean || valueType == Boolean) {
            return value instanceof Boolean ? value : fail(keyword, valueType, value)
        }
        if (Number.isAssignableFrom(valueType) || valueType.primitive) {
            return value instanceof Number ? value : fail(keyword, valueType, value)
        }
        value
    }

    private Object fail(String keyword, Class<?> valueType, Object value) {
        throw new IllegalArgumentException("${path}.${keyword} wants a ${valueType.simpleName}; got ${value.getClass().simpleName} ${value}")
    }

    private IllegalArgumentException unknown(String name) {
        Set<String> known = keywords()
        List<String> near = Mirror.nearest(name, known)
        String hint = near ? " Did you mean ${near.join(', ')}?" : ''
        new IllegalArgumentException("'${name}' is not a property of ${path} (${type.simpleName}).${hint} Known: ${known.join(', ')}")
    }

    private static String describe(Object[] args) {
        args.length == 0 ? 'nothing' : args.collect { Object a -> a == null ? 'null' : "${a.getClass().simpleName} ${a instanceof Closure ? '{ }' : a}" }.join(', ')
    }

    private static Object toKebab(Object value) {
        if (value instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<>()
            ((Map<?, ?>) value).each { Object k, Object v -> out.put(Mirror.kebab(String.valueOf(k)), toKebab(v)) }
            return out
        }
        if (value instanceof List) {
            return ((List<?>) value).collect { Object v -> toKebab(v) }
        }
        if (value instanceof Long && ((Long) value) == ((Long) value).intValue()) {
            // YAML and the flat form parse small numbers as Integer; emit the same so trees compare equal.
            return ((Long) value).intValue()
        }
        value
    }
}
