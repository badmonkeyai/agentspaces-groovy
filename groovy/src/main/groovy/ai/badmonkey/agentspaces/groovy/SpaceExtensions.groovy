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

import ai.badmonkey.agentspaces.api.space.EntryHandle
import ai.badmonkey.agentspaces.api.space.Lease
import ai.badmonkey.agentspaces.api.space.Space
import ai.badmonkey.agentspaces.api.space.SpaceEvent
import ai.badmonkey.agentspaces.api.space.SpaceListener
import ai.badmonkey.agentspaces.api.space.Subscription
import ai.badmonkey.agentspaces.api.space.TakenEntry
import ai.badmonkey.agentspaces.api.space.Template
import ai.badmonkey.agentspaces.api.spi.AgentIdentity
import ai.badmonkey.agentspaces.space.ConsistencyHint
import ai.badmonkey.agentspaces.space.replicated.ReplicatedSpace
import groovy.transform.CompileStatic

import java.time.Duration
import java.util.function.Predicate

/**
 * The Space verbs in Groovy's idiom (SRAD section 3.1, GR-V-01 to GR-V-06).
 * Every method calls the Java method it stands for after coercing its
 * arguments; none holds state beyond the per-space default lease.
 *
 * <pre>
 * space << entry                                        // write under the default lease
 * space.write(entry, '10m')                             // or lease: '10m', tags: [region: 'eu']
 * space.read(Task, where: [priority: gte(2)])           // Optional&lt;Task&gt;
 * space.read(Task) { priority > 2 }                     // with a local filter
 * space.all(Task, limit: 50, tags: [region: 'eu'])      // List
 * space.take(Task, lease: '10m', timeout: '2s')         // Optional&lt;TakenEntry&lt;Task&gt;&gt;
 * space.take(Task, lease: '10m') { Task t -> new Quote(...) }   // take, run, complete with the result
 * space.use(Task, lease: '10m') { TakenEntry&lt;Task&gt; taken -> ... } // take, run, complete without a result
 * space.notify(Task, lease: '1h') { SpaceEvent e -> ... }         // Subscription
 * </pre>
 *
 * Options: {@code where} and {@code tags} (the template DSL of {@link Where}),
 * {@code timeout} (read, take), {@code lease} (write, take, notify),
 * {@code resultLease} (the take-and-complete form), {@code limit} (all,
 * issued, entries; default 100), {@code fresh} (a {@code ReplicatedSpace}
 * pulls once before answering), {@code on} (notify: the event kinds to
 * deliver).
 */
@CompileStatic
final class SpaceExtensions {

    static final int DEFAULT_LIMIT = 100
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(1)

    private SpaceExtensions() {
    }

    // ---- default lease (GR-V-02) ------------------------------------------------------------

    /** The lease {@code space << entry} writes under; ten minutes until set. */
    static Lease getDefaultLease(Space self) {
        Defaults.leaseOf(self)
    }

    /** Sets the lease {@code space << entry} writes under ({@code space.defaultLease = '10m'}). */
    static void setDefaultLease(Space self, Object lease) {
        Defaults.lease(self, Coercions.lease(lease))
    }

    // ---- write -----------------------------------------------------------------------------

    /** Writes under the space's default lease and returns the space, so writes chain. */
    static Space leftShift(Space self, Object entry) {
        self.write(entry, Defaults.leaseOf(self))
        self
    }

    /** {@code space.write(entry, '10m')}. */
    static EntryHandle write(Space self, Object entry, Object lease) {
        self.write(entry, Coercions.lease(lease))
    }

    /** {@code space.write(entry, lease: '10m', tags: [region: 'eu'])}; the lease defaults to the space's. */
    static EntryHandle write(Space self, Map<String, ?> options, Object entry) {
        Lease lease = options.containsKey('lease') ? Coercions.lease(options.lease) : Defaults.leaseOf(self)
        Map<String, String> tags = stringTags(options.tags)
        tags.isEmpty() ? self.write(entry, lease) : self.write(entry, lease, tags)
    }

    // ---- read, all, issued, entries --------------------------------------------------------

    /** The first match, with {@code timeout:}, {@code where:}, {@code tags:}, {@code fresh:}. */
    static <T> Optional<T> read(Space self, Map<String, ?> options, Class<T> type) {
        Template<T> template = Where.template(type, options)
        Duration timeout = options.containsKey('timeout') ? Coercions.duration(options.timeout) : null
        if (fresh(options) && self instanceof ReplicatedSpace) {
            return ((ReplicatedSpace) self).read(template, ConsistencyHint.FRESH, timeout ?: Duration.ZERO)
        }
        timeout == null ? self.read(template) : self.read(template, timeout)
    }

    static <T> Optional<T> read(Space self, Class<T> type) {
        read(self, [:], type)
    }

    /**
     * The first match that passes the filter closure (judged on this replica
     * over the decoded entry, the entry as the closure's delegate); with
     * {@code timeout:} the read polls until the deadline.
     */
    static <T> Optional<T> read(Space self, Map<String, ?> options, Class<T> type, Closure<?> filter) {
        Predicate<Object> test = Where.filter(filter)
        Map<String, ?> scan = withoutTimeout(options)
        long deadline = options.containsKey('timeout') ? System.nanoTime() + Coercions.duration(options.timeout).toNanos() : Long.MIN_VALUE
        while (true) {
            Optional<T> found = all(self, scan, type).stream().filter(test).findFirst()
            if (found.isPresent() || System.nanoTime() >= deadline) {
                return found
            }
            Thread.sleep(50)
        }
    }

    static <T> Optional<T> read(Space self, Class<T> type, Closure<?> filter) {
        read(self, [:], type, filter)
    }

    /** Every current match as an unmodifiable list ({@code limit:} default 100), GINQ-ready. */
    static <T> List<T> all(Space self, Map<String, ?> options, Class<T> type) {
        Template<T> template = Where.template(type, options)
        int limit = limit(options)
        if (fresh(options) && self instanceof ReplicatedSpace) {
            return List.copyOf(((ReplicatedSpace) self).readAll(template, ConsistencyHint.FRESH, limit))
        }
        List.copyOf(self.readAll(template, limit))
    }

    static <T> List<T> all(Space self, Class<T> type) {
        all(self, [:], type)
    }

    static <T> List<T> all(Space self, Map<String, ?> options, Class<T> type, Closure<?> filter) {
        Predicate<Object> test = Where.filter(filter)
        List.copyOf(all(self, options, type).findAll { T t -> test.test(t) })
    }

    static <T> List<T> all(Space self, Class<T> type, Closure<?> filter) {
        all(self, [:], type, filter)
    }

    /** Every current match with its writer and attestation ({@code Space.Issued}). */
    static <T> List<Space.Issued<T>> issued(Space self, Map<String, ?> options, Class<T> type) {
        List.copyOf(self.readAllIssued(Where.template(type, options), limit(options)))
    }

    static <T> List<Space.Issued<T>> issued(Space self, Class<T> type) {
        issued(self, [:], type)
    }

    /** Every current match with its metadata ({@code Space.Entry}: id, issuer, tags, lease, stamp). */
    static <T> List<Space.Entry<T>> entries(Space self, Map<String, ?> options, Class<T> type) {
        List.copyOf(self.readAllEntries(Where.template(type, options), limit(options)))
    }

    static <T> List<Space.Entry<T>> entries(Space self, Class<T> type) {
        entries(self, [:], type)
    }

    static <T> List<Space.Entry<T>> entries(Space self, Map<String, ?> options, Class<T> type, Closure<?> filter) {
        Predicate<Object> test = Where.filter(filter)
        List.copyOf(entries(self, options, type).findAll { Space.Entry<T> e -> test.test(e.value()) })
    }

    static <T> List<Space.Entry<T>> entries(Space self, Class<T> type, Closure<?> filter) {
        entries(self, [:], type, filter)
    }

    /** How many entries of the type match, up to {@code limit:}. */
    static int count(Space self, Map<String, ?> options, Class<?> type) {
        all(self, options, type).size()
    }

    static int count(Space self, Class<?> type) {
        count(self, [:], type)
    }

    // ---- take, use, complete ---------------------------------------------------------------

    /** A take under {@code lease:} (default 10m) waiting {@code timeout:} (default 1s), with {@code where:} and {@code tags:}. */
    static <T> Optional<TakenEntry<T>> take(Space self, Map<String, ?> options, Class<T> type) {
        self.take(Where.template(type, options), takeLease(options), timeout(options))
    }

    static <T> Optional<TakenEntry<T>> take(Space self, Class<T> type) {
        take(self, [:], type)
    }

    /**
     * The take-and-complete form (GR-V-06): takes one entry, hands it to the
     * body, and completes the take with the body's result under
     * {@code resultLease:} (default 1h), or without a result when the body
     * returns null; a body that throws leaves the take to lapse, which is how
     * the binder treats a {@code @SpaceTake} method. Returns the result, or
     * null when nothing matched. Runs on the caller's thread.
     */
    static <T, R> R take(Space self, Map<String, ?> options, Class<T> type, Closure<R> body) {
        Optional<TakenEntry<T>> taken = take(self, options, type)
        if (!taken.isPresent()) {
            return null
        }
        R result = body.maximumNumberOfParameters > 1
                ? body.call(taken.get().entry(), taken.get())
                : body.call(taken.get().entry())
        if (result == null) {
            self.complete(taken.get())
        } else {
            self.complete(taken.get(), result, resultLease(options))
        }
        result
    }

    static <T, R> R take(Space self, Class<T> type, Closure<R> body) {
        take(self, [:], type, body)
    }

    /**
     * Takes one entry, hands the {@code TakenEntry} to the body, and completes
     * it without a result when the body returns; a throw leaves it to lapse.
     * Returns the body's result, or null when nothing matched.
     */
    static <T, R> R use(Space self, Map<String, ?> options, Class<T> type, Closure<R> body) {
        Optional<TakenEntry<T>> taken = take(self, options, type)
        if (!taken.isPresent()) {
            return null
        }
        R result = body.call(taken.get())
        self.complete(taken.get())
        result
    }

    static <T, R> R use(Space self, Class<T> type, Closure<R> body) {
        use(self, [:], type, body)
    }

    /** {@code space.complete(taken, result, '1h')}. */
    static EntryHandle complete(Space self, TakenEntry<?> taken, Object result, Object lease) {
        self.complete(taken, result, Coercions.lease(lease))
    }

    /** {@code space.complete(taken, result, lease: '1h', tags: [..])}. */
    static EntryHandle complete(Space self, Map<String, ?> options, TakenEntry<?> taken, Object result) {
        Map<String, String> tags = stringTags(options.tags)
        tags.isEmpty() ? self.complete(taken, result, resultLease(options)) : self.complete(taken, result, resultLease(options), tags)
    }

    // ---- notify ----------------------------------------------------------------------------

    /**
     * A subscription under {@code lease:} (default 1h) delivering matching
     * events to the closure ({@code SpaceEvent}, or {@code (event, entry)}),
     * with {@code where:}, {@code tags:}, {@code on:} (the kinds to deliver,
     * default all), and an optional filter over the entry.
     */
    static <T> Subscription notify(Space self, Map<String, ?> options, Class<T> type, Closure<?> listener) {
        Set<SpaceEvent.Kind> kinds = kinds(options.on)
        Predicate<Object> test = options.filter instanceof Closure ? Where.filter((Closure<?>) options.filter) : null
        SpaceListener<T> delivery = { SpaceEvent<T> event ->
            if (kinds != null && !kinds.contains(event.kind())) {
                return
            }
            if (test != null && !test.test(event.entry())) {
                return
            }
            if (listener.maximumNumberOfParameters > 1) {
                listener.call(event, event.entry())
            } else {
                listener.call(event)
            }
        } as SpaceListener<T>
        Lease lease = options.containsKey('lease') ? Coercions.lease(options.lease) : Lease.of(Duration.ofHours(1))
        self.notify(Where.template(type, options), delivery, lease)
    }

    static <T> Subscription notify(Space self, Class<T> type, Closure<?> listener) {
        notify(self, [:], type, listener)
    }

    // ---- views -----------------------------------------------------------------------------

    /** A view of a replicated space acting as {@code agent} ({@code ReplicatedSpace.as}, a Groovy keyword otherwise). */
    static Space acting(Space self, AgentIdentity agent) {
        if (!(self instanceof ReplicatedSpace)) {
            throw new IllegalArgumentException("only a ReplicatedSpace can act as an agent; ${self.name()} is a ${self.getClass().simpleName}")
        }
        ((ReplicatedSpace) self).as(agent)
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static Lease takeLease(Map<String, ?> options) {
        options.containsKey('lease') ? Coercions.lease(options.lease) : Lease.of(Duration.ofMinutes(10))
    }

    private static Lease resultLease(Map<String, ?> options) {
        options.containsKey('resultLease') ? Coercions.lease(options.resultLease) : Lease.of(Duration.ofHours(1))
    }

    private static Duration timeout(Map<String, ?> options) {
        options.containsKey('timeout') ? Coercions.duration(options.timeout) : DEFAULT_TIMEOUT
    }

    private static int limit(Map<String, ?> options) {
        options.containsKey('limit') ? ((Number) options.limit).intValue() : DEFAULT_LIMIT
    }

    private static boolean fresh(Map<String, ?> options) {
        Where.truth(options.fresh)
    }

    private static Map<String, ?> withoutTimeout(Map<String, ?> options) {
        Map<String, Object> copy = new LinkedHashMap<>()
        options.each { String k, Object v -> if (k != 'timeout') { copy.put(k, v) } }
        copy
    }

    static Map<String, String> stringTags(Object tags) {
        if (tags == null) {
            return [:]
        }
        if (!(tags instanceof Map)) {
            throw new IllegalArgumentException("tags: must be a map; got ${tags.getClass().simpleName}")
        }
        Map<String, String> out = new LinkedHashMap<>()
        ((Map<?, ?>) tags).each { Object k, Object v -> out.put(String.valueOf(k), String.valueOf(v)) }
        out
    }

    private static Set<SpaceEvent.Kind> kinds(Object on) {
        if (on == null) {
            return null
        }
        Collection<?> list = on instanceof Collection ? (Collection<?>) on : [on]
        list.collect { Object k -> k instanceof SpaceEvent.Kind ? (SpaceEvent.Kind) k : SpaceEvent.Kind.valueOf(k.toString().toUpperCase(Locale.ROOT)) } as Set<SpaceEvent.Kind>
    }
}
