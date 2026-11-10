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

import ai.badmonkey.agentspaces.agent.content.StoreReceipt
import ai.badmonkey.agentspaces.common.id.PeerId
import ai.badmonkey.agentspaces.pdt.Pdt
import ai.badmonkey.agentspaces.pdt.blob.PBlob
import ai.badmonkey.agentspaces.pdt.refs.Heads
import ai.badmonkey.agentspaces.pdt.refs.Ref
import ai.badmonkey.agentspaces.pdt.stores.Stores
import ai.badmonkey.agentspaces.pdt.values.PMap
import ai.badmonkey.agentspaces.pdt.values.PSet
import ai.badmonkey.agentspaces.pdt.values.PVector
import ai.badmonkey.agentspaces.planner.StoreChooser
import ai.badmonkey.agentspaces.runtime.FleetContext
import groovy.transform.CompileStatic

import java.time.Duration
import java.util.function.UnaryOperator

/**
 * The persistent trees in Groovy (srad/agentspaces-pdt.md PD-C-07): {@code pdt()} on a script
 * is the group's {@link Pdt} facade when {@code pdt { enabled }} is on, a {@link PMap} reads
 * property-style ({@code facts.total}, {@code facts['total']}, {@code 'total' in facts}) and
 * grows with {@code +} and shrinks with {@code -} as a new value, a {@link PVector} indexes
 * with {@code []}, and a {@link Ref} derefs as {@code ref.value} and swaps with a closure
 * ({@code ref.swap { it.assoc('total', 1) }}). Every value stays persistent: a read never
 * changes it and an operator returns a new one. On the content stores (PDT 2 phase 7, P2-C-04),
 * {@code pdt.pin(root, copies: 2, lease: '30d')} asks the stores to hold a root and
 * {@code pdt.status(root)} shows the copies they hold.
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

    // ---- content stores (PDT 2 phase 7, P2-C-04)

    /**
     * {@code pdt.pin(root, copies: 2, lease: '30d')}: asks the group's content stores to hold a root
     * (P2-S-11), a tree root this peer holds complete or an artifact root it knows. The stores' cards
     * choose where, and the facade writes one pin that this peer signs; pinning the root again renews
     * it. Options: {@code copies} (1), {@code lease} (30 days; anything a duration reads), {@code tier}
     * ({@code hot} or {@code cold}), {@code maxPrice} (per GiB-month), and {@code avoid} (peer ids).
     * Throws {@link IllegalStateException} when this peer does not hold the root or no store's card
     * fits yet.
     */
    static Stores.Pinning pin(Pdt self, Map<String, ?> options, String root) {
        StoreChooser.Need need = StoreChooser.Need.of(0, options.containsKey('copies') ? ((Number) options.copies).intValue() : 1)
        if (options.containsKey('tier')) {
            need = need.tier(options.tier.toString())
        }
        if (options.containsKey('maxPrice')) {
            need = need.maxPrice(((Number) options.maxPrice).longValue())
        }
        if (options.containsKey('avoid')) {
            need = need.avoid(((Collection<?>) options.avoid).collect { it instanceof PeerId ? (PeerId) it : PeerId.of(it.toString()) } as Set<PeerId>)
        }
        Duration lease = options.containsKey('lease') ? Coercions.duration(options.lease) : Duration.ofDays(30)
        self.storeFor(root, need, lease)
    }

    /** {@code pdt.pin(root)}: one copy for 30 days. */
    static Stores.Pinning pin(Pdt self, String root) {
        pin(self, [:], root)
    }

    /**
     * {@code pdt.status(root)}: what the content stores hold of a root, as this peer sees it (P2-S-07):
     * {@code [root:, copies:, holders: [[store:, bytes:, until:, blind:]], unplaced:]}. {@code copies}
     * counts the live receipts of stores this peer has not struck, {@code until} is the epoch millisecond
     * a receipt lapses, and {@code unplaced} is true when this peer struck a store that held the root and
     * found no other to place it on.
     */
    static Map<String, Object> status(Pdt self, String root) {
        Stores stores = self.stores()
        List<StoreReceipt> receipts = stores.receipts(root)
        [root    : root,
         copies  : receipts.size(),
         holders : receipts.collect { StoreReceipt r ->
             [store: r.holder().value(), bytes: r.bytes(), until: r.until(), blind: r.blind()] as Map<String, Object>
         },
         unplaced: stores.unplaced().contains(root)] as Map<String, Object>
    }
}
