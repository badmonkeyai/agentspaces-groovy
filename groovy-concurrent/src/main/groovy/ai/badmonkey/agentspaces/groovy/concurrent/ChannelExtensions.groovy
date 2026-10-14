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
package ai.badmonkey.agentspaces.groovy.concurrent

import ai.badmonkey.agentspaces.api.space.Lease
import ai.badmonkey.agentspaces.api.space.Space
import ai.badmonkey.agentspaces.api.space.SpaceEvent
import ai.badmonkey.agentspaces.api.space.SpaceListener
import ai.badmonkey.agentspaces.api.space.Subscription
import ai.badmonkey.agentspaces.groovy.Coercions
import ai.badmonkey.agentspaces.groovy.Where
import groovy.transform.CompileStatic

import java.time.Duration
import java.util.function.Predicate

/**
 * Channels over space subscriptions (SRAD section 3.6):
 *
 * <pre>
 * for await (e in space('findings').channel(Finding, lease: '1h', buffer: 64)) { println e.entry().summary() }
 * def fan = space('tasks').broadcast(Task); def a = fan.subscribe(); def b = fan.subscribe()
 * </pre>
 *
 * Options: {@code lease} (default 1h), {@code buffer} (default 64; 0 for
 * unbuffered), {@code where}, {@code tags}, {@code on} (the kinds to deliver),
 * {@code filter} (a closure over the entry). Back-pressure on a full buffer
 * blocks the subscription's listener: a slow consumer slows this peer's
 * event delivery and leaves the fabric alone.
 */
@CompileStatic
final class ChannelExtensions {

    static final int DEFAULT_BUFFER = 64

    static {
        Bridge.install()
    }

    private ChannelExtensions() {
    }

    static <T> SubscribedChannel<SpaceEvent<T>> channel(Space self, Map<String, ?> options, Class<T> type) {
        int buffer = options.containsKey('buffer') ? ((Number) options.buffer).intValue() : DEFAULT_BUFFER
        SubscribedChannel<SpaceEvent<T>> channel = new SubscribedChannel<>(buffer)
        Duration lease = options.containsKey('lease') ? Coercions.duration(options.lease) : Duration.ofHours(1)
        Predicate<SpaceEvent<T>> accept = accept(options)
        Subscription subscription = self.notify(Where.template(type, options), { SpaceEvent<T> e ->
            if (accept.test(e)) {
                channel.deliver(e)
            }
        } as SpaceListener<T>, Lease.of(lease))
        channel.attach(subscription, lease)
        channel
    }

    static <T> SubscribedChannel<SpaceEvent<T>> channel(Space self, Class<T> type) {
        channel(self, [:], type)
    }

    static <T> SubscribedBroadcast<SpaceEvent<T>> broadcast(Space self, Map<String, ?> options, Class<T> type) {
        SubscribedBroadcast<SpaceEvent<T>> broadcast = new SubscribedBroadcast<>()
        Duration lease = options.containsKey('lease') ? Coercions.duration(options.lease) : Duration.ofHours(1)
        Predicate<SpaceEvent<T>> accept = accept(options)
        Subscription subscription = self.notify(Where.template(type, options), { SpaceEvent<T> e ->
            if (accept.test(e)) {
                broadcast.deliver(e)
            }
        } as SpaceListener<T>, Lease.of(lease))
        broadcast.attach(subscription)
        broadcast
    }

    static <T> SubscribedBroadcast<SpaceEvent<T>> broadcast(Space self, Class<T> type) {
        broadcast(self, [:], type)
    }

    private static <T> Predicate<SpaceEvent<T>> accept(Map<String, ?> options) {
        Set<SpaceEvent.Kind> kinds = null
        if (options.on != null) {
            Collection<?> list = options.on instanceof Collection ? (Collection<?>) options.on : [options.on]
            kinds = list.collect { Object k -> k instanceof SpaceEvent.Kind ? (SpaceEvent.Kind) k : SpaceEvent.Kind.valueOf(k.toString().toUpperCase(Locale.ROOT)) } as Set<SpaceEvent.Kind>
        }
        Predicate<Object> filter = options.filter instanceof Closure ? Where.filter((Closure<?>) options.filter) : null
        Set<SpaceEvent.Kind> finalKinds = kinds
        return { SpaceEvent<T> e -> (finalKinds == null || finalKinds.contains(e.kind())) && (filter == null || filter.test(e.entry())) } as Predicate<SpaceEvent<T>>
    }
}
