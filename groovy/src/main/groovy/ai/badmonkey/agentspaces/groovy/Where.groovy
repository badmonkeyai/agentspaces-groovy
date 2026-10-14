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

import ai.badmonkey.agentspaces.api.space.Matcher
import ai.badmonkey.agentspaces.api.space.Matchers
import ai.badmonkey.agentspaces.api.space.Template
import groovy.transform.CompileStatic
import org.codehaus.groovy.runtime.DefaultGroovyMethods

import java.util.function.Predicate

/**
 * The template DSL (SRAD GR-V-05): {@code where:} and {@code tags:} maps over
 * the Java matchers, and the filter closure. Each matcher function stands for
 * the {@code Matchers} static of the same name; {@code anyOf} stands for
 * {@code Matchers.in}, because {@code in} is a Groovy keyword, and
 * {@code present} stands for {@code Matchers.notNull} on a tag.
 *
 * <pre>
 * import static ai.badmonkey.agentspaces.groovy.Where.*
 * space.read(Task, where: [priority: gte(2), topic: 'x'], tags: [region: 'eu'])
 * space.read(Task) { priority > 2 }      // a filter over the decoded entry, judged on this replica
 * </pre>
 *
 * A plain value in a {@code where:} map means {@code eq}; a closure means
 * {@code predicate} over the field's value; a {@code Matcher} passes through.
 * The filter closure of {@code read}, {@code all}, {@code entries}, and
 * {@code notify} runs after the template matched, on this replica; a take
 * claim is judged by the fabric's template alone, so {@code take} accepts
 * {@code where:} and {@code tags:} and no filter closure.
 */
@CompileStatic
final class Where {

    private Where() {
    }

    static Matcher eq(Object expected) { Matchers.eq(expected) }

    static Matcher ne(Object unexpected) { Matchers.ne(unexpected) }

    static Matcher gt(Comparable<?> bound) { Matchers.gt(bound) }

    static Matcher gte(Comparable<?> bound) { Matchers.gte(bound) }

    static Matcher lt(Comparable<?> bound) { Matchers.lt(bound) }

    static Matcher lte(Comparable<?> bound) { Matchers.lte(bound) }

    /** {@code Matchers.in}: the value is one of the alternatives. */
    static Matcher anyOf(Object... alternatives) { Matchers.in(alternatives) }

    /** {@code Matchers.in} over a collection. */
    static Matcher anyOf(Collection<?> alternatives) { Matchers.in(alternatives.toArray()) }

    static Matcher contains(CharSequence fragment) { Matchers.contains(fragment) }

    static Matcher isNull() { Matchers.isNull() }

    static Matcher notNull() { Matchers.notNull() }

    /** On a tag: the tag is present, whatever its value. */
    static Matcher present() { Matchers.notNull() }

    /** {@code Matchers.predicate} over a field's value, with Groovy truth. */
    static Matcher predicate(Closure<?> test) {
        Matchers.predicate({ Object v -> truth(test.call(v)) } as Predicate<Object>)
    }

    /** A field or tag spec to a matcher: a Matcher as is, a closure as a predicate, null as isNull, anything else as eq. */
    static Matcher matcher(Object spec) {
        if (spec instanceof Matcher) {
            return (Matcher) spec
        }
        if (spec instanceof Closure) {
            return predicate((Closure<?>) spec)
        }
        if (spec == null) {
            return Matchers.isNull()
        }
        Matchers.eq(spec)
    }

    /**
     * A template from a type and the DSL options: {@code where} (field to
     * spec) and {@code tags} (tag key to spec). Other keys are ignored here
     * so one option map serves a verb and its template.
     */
    static <T> Template<T> template(Class<T> type, Map<String, ?> options = null) {
        Template<T> template = Template.of(type)
        Object where = options?.get('where')
        if (where != null) {
            if (!(where instanceof Map)) {
                throw new IllegalArgumentException("where: must be a map of field to value, matcher, or closure; got ${where.getClass().simpleName}")
            }
            ((Map<?, ?>) where).each { Object field, Object spec ->
                template = template.where(String.valueOf(field), matcher(spec))
            }
        }
        Object tags = options?.get('tags')
        if (tags != null) {
            if (!(tags instanceof Map)) {
                throw new IllegalArgumentException("tags: must be a map of tag key to value, matcher, or closure; got ${tags.getClass().simpleName}")
            }
            ((Map<?, ?>) tags).each { Object key, Object spec ->
                template = template.whereTag(String.valueOf(key), matcher(spec))
            }
        }
        template
    }

    /**
     * A filter over a decoded entry: the closure runs with the entry as its
     * delegate (so {@code priority > 2} reads the record's component) and as
     * its argument when it declares one.
     */
    static Predicate<Object> filter(Closure<?> test) {
        { Object entry ->
            Closure<?> call = (Closure<?>) test.clone()
            call.resolveStrategy = Closure.DELEGATE_FIRST
            call.delegate = entry
            truth(call.maximumNumberOfParameters > 0 ? call.call(entry) : call.call())
        } as Predicate<Object>
    }

    static boolean truth(Object value) {
        value instanceof Boolean ? (Boolean) value : DefaultGroovyMethods.asBoolean(value)
    }
}
