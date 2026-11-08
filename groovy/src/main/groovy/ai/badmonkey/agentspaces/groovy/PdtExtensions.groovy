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

import ai.badmonkey.agentspaces.pdt.Pdt
import ai.badmonkey.agentspaces.pdt.blob.PBlob
import ai.badmonkey.agentspaces.pdt.refs.Heads
import ai.badmonkey.agentspaces.pdt.refs.Ref
import ai.badmonkey.agentspaces.pdt.values.PMap
import ai.badmonkey.agentspaces.pdt.values.PSet
import ai.badmonkey.agentspaces.pdt.values.PVector
import ai.badmonkey.agentspaces.runtime.FleetContext
import groovy.transform.CompileStatic

import java.util.function.UnaryOperator

/**
 * The persistent trees in Groovy (srad/agentspaces-pdt.md PD-C-07): {@code pdt()} on a script
 * is the group's {@link Pdt} facade when {@code pdt { enabled }} is on, a {@link PMap} reads
 * property-style ({@code facts.total}, {@code facts['total']}, {@code 'total' in facts}) and
 * grows with {@code +} and shrinks with {@code -} as a new value, a {@link PVector} indexes
 * with {@code []}, and a {@link Ref} derefs as {@code ref.value} and swaps with a closure
 * ({@code ref.swap { it.assoc('total', 1) }}). Every value stays persistent: a read never
 * changes it and an operator returns a new one.
 */
@CompileStatic
final class PdtExtensions {

    private PdtExtensions() {
    }

    /** The group's facade; {@code pdt { enabled }} in the fleet DSL (with {@code content { enabled }}) turns it on. */
    static Pdt getPdt(FleetContext self) {
        self.requiredPdt()
    }

    // ---- PMap

    /** {@code facts.total}: the value under a text key, or null. */
    static Object propertyMissing(PMap self, String name) {
        ((PMap<Object, Object>) self).get(name).orElse(null)
    }

    /** {@code facts['total']} and {@code facts[42]}: the value under any key, or null. */
    static Object getAt(PMap self, Object key) {
        ((PMap<Object, Object>) self).get(key).orElse(null)
    }

    /** {@code 'total' in facts}: whether the map holds the key. */
    static boolean isCase(PMap self, Object key) {
        ((PMap<Object, Object>) self).containsKey(key)
    }

    /** {@code facts + [total: 1, owner: 'ana']}: a new map with every entry put. */
    static PMap plus(PMap self, Map<?, ?> entries) {
        PMap<Object, Object> out = (PMap<Object, Object>) self
        entries.each { k, v -> out = out.assoc(k, v) }
        out
    }

    /** {@code facts - 'total'}: a new map without the key. */
    static PMap minus(PMap self, Object key) {
        ((PMap<Object, Object>) self).dissoc(key)
    }

    /** {@code facts.size()} as for a Groovy map. */
    static int size(PMap self) {
        (int) self.count()
    }

    // ---- PSet and PVector

    static boolean isCase(PSet self, Object element) {
        ((PSet<Object>) self).contains(element)
    }

    static int size(PSet self) {
        (int) self.count()
    }

    /** {@code log[3]} and {@code log[-1]}: the element at an index, negative from the end. */
    static Object getAt(PVector self, int index) {
        long i = index < 0 ? self.count() + index : index
        ((PVector<Object>) self).nth(i)
    }

    static int size(PVector self) {
        (int) self.count()
    }

    /** {@code blob.bytes}: the whole blob. */
    static byte[] getBytes(PBlob self) {
        self.bytes()
    }

    // ---- Ref

    /** {@code ref.value}: the current value, one head's or the merge of the heads (3.6.1). */
    static <T> T getValue(Ref<T> self) {
        self.deref()
    }

    /** {@code ref.swap { it.assoc('total', 1) }}: a new version from the closure over the current value. */
    static <T> T swap(Ref<T> self, Closure<T> body) {
        self.swap({ T current -> body.call(current) } as UnaryOperator<T>)
    }

    /** {@code ref.history}: the versions seen, newest first (ten). */
    static List<Heads.Head> getHistory(Ref self) {
        self.history(10)
    }
}
