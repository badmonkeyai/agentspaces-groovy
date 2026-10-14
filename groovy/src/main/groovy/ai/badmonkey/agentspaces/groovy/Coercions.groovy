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

import ai.badmonkey.agentspaces.agent.annotation.Durations
import ai.badmonkey.agentspaces.api.space.Lease
import groovy.transform.CompileStatic

import java.lang.reflect.Constructor
import java.lang.reflect.RecordComponent
import java.time.Duration

/**
 * Coercions (SRAD GR-V-03, GR-V-04): strings and numbers to {@code Duration}
 * and {@code Lease} in the forms the annotations accept ({@code "10m"},
 * {@code "PT10M"}, millis); maps to records through the canonical
 * constructor by component name with numeric widening; records to maps.
 */
@CompileStatic
final class Coercions {

    private static final Map<Class<?>, Class<?>> WRAPPERS = [
            (int): Integer, (long): Long, (double): Double, (float): Float, (short): Short,
            (byte): Byte, (boolean): Boolean, (char): Character] as Map<Class<?>, Class<?>>

    private Coercions() {
    }

    /** A {@code Duration} from a {@code Duration}, a {@code Lease}, a number of millis, or a string {@code Durations.parse} reads. */
    static Duration duration(Object value) {
        switch (value) {
            case null: throw new IllegalArgumentException('a duration is required')
            case Duration: return (Duration) value
            case Lease: return ((Lease) value).duration()
            case Number: return Duration.ofMillis(((Number) value).longValue())
            case CharSequence: return Durations.parse(value.toString())
            default: throw new IllegalArgumentException("not a duration (try \"10m\", \"500ms\", \"30s\", ISO-8601 \"PT10M\", millis, a Duration, or a Lease): ${value} (${value.getClass().name})")
        }
    }

    /** A {@code Lease} from anything {@link #duration} reads. */
    static Lease lease(Object value) {
        value instanceof Lease ? (Lease) value : Lease.of(duration(value))
    }

    /**
     * A record from a map: the canonical constructor by component name, with
     * numeric widening (an Integer fills a long or a double, a BigDecimal
     * fills a double) and Groovy's string-to-enum and char coercions. An
     * unknown key, or a missing component, is refused by name.
     */
    static <T> T record(Class<T> type, Map<String, ?> values) {
        if (!type.isRecord()) {
            throw new IllegalArgumentException("${type.name} is not a record; entry types are Java (or Groovy) records")
        }
        RecordComponent[] components = type.getRecordComponents()
        Set<String> names = components*.name as Set<String>
        Set<String> unknown = (values.keySet() as Set<String>) - names
        if (unknown) {
            throw new IllegalArgumentException("${type.simpleName} has no component ${unknown.sort()}; components: ${names.sort()}")
        }
        Object[] args = new Object[components.length]
        Class<?>[] parameterTypes = new Class<?>[components.length]
        components.eachWithIndex { RecordComponent c, int i ->
            parameterTypes[i] = c.type
            if (!values.containsKey(c.name) && c.type.primitive) {
                throw new IllegalArgumentException("${type.simpleName}.${c.name} (${c.type.simpleName}) is required")
            }
            args[i] = widen(values.get(c.name), c.type, type.simpleName + '.' + c.name)
        }
        Constructor<T> canonical = type.getDeclaredConstructor(parameterTypes)
        canonical.accessible = true
        canonical.newInstance(args)
    }

    /** A record's components as an ordered map. */
    static Map<String, Object> map(Record record) {
        Map<String, Object> map = new LinkedHashMap<>()
        for (RecordComponent c : record.getClass().getRecordComponents()) {
            map.put(c.name, c.accessor.invoke(record))
        }
        map
    }

    @SuppressWarnings('GroovyAssignabilityCheck')
    private static Object widen(Object value, Class<?> target, String what) {
        if (value == null) {
            return null
        }
        if (value instanceof Number) {
            Number n = (Number) value
            switch (target) {
                case int: case Integer: return n.intValue()
                case long: case Long: return n.longValue()
                case double: case Double: return n.doubleValue()
                case float: case Float: return n.floatValue()
                case short: case Short: return n.shortValue()
                case byte: case Byte: return n.byteValue()
                case BigDecimal: return n instanceof BigDecimal ? n : new BigDecimal(n.toString())
                case BigInteger: return n instanceof BigInteger ? n : BigInteger.valueOf(n.longValue())
            }
        }
        if (target.isEnum() && value instanceof CharSequence) {
            return Enum.valueOf((Class<? extends Enum>) target, value.toString())
        }
        if (value instanceof Map && target.isRecord()) {
            return record((Class<?>) target, (Map<String, ?>) value)
        }
        if (target.isInstance(value)) {
            return value
        }
        if (target.primitive && (value instanceof Boolean || value instanceof Character)) {
            return value
        }
        try {
            Class<?> wrapper = target.primitive ? WRAPPERS.get(target) : target
            return org.codehaus.groovy.runtime.DefaultGroovyMethods.asType(value, wrapper)
        } catch (Exception e) {
            throw new IllegalArgumentException("${what} wants a ${target.simpleName}; got ${value.getClass().simpleName} ${value}", e)
        }
    }
}
