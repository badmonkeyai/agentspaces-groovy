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

import ai.badmonkey.agentspaces.api.space.Subscription
import ai.badmonkey.agentspaces.groovy.Coercions
import groovy.concurrent.AsyncChannel
import groovy.concurrent.Awaitable
import groovy.transform.CompileStatic
import org.apache.groovy.runtime.async.AsyncSupport

import java.time.Duration
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * An {@code AsyncChannel} fed by one space subscription (SRAD section 3.6,
 * GR-C-05, GR-Q-REL-03): closing either side closes both, and the channel
 * closes itself when the subscription's lease would lapse unless
 * {@link #renew} extends it. Groovy marks {@code groovy.concurrent}
 * incubating; this artifact is where that dependency lives.
 */
@CompileStatic
final class SubscribedChannel<T> implements AsyncChannel<T> {

    private final AsyncChannel<T> inner
    private volatile Subscription subscription
    private volatile ScheduledFuture<?> expiry

    SubscribedChannel(int buffer) {
        this.inner = buffer > 0 ? AsyncChannel.<T> create(buffer) : AsyncChannel.<T> create()
    }

    /** Attaches the subscription that feeds this channel and schedules the close at its lease. */
    void attach(Subscription subscription, Duration lease) {
        this.subscription = subscription
        schedule(lease)
    }

    private void schedule(Duration lease) {
        ScheduledFuture<?> previous = expiry
        if (previous != null) {
            previous.cancel(false)
        }
        expiry = AsyncSupport.scheduler.schedule({ -> close() } as Runnable, lease.toMillis(), TimeUnit.MILLISECONDS)
    }

    /** Extends the subscription's lease and the channel's life with it. */
    void renew(Object extension) {
        Duration more = Coercions.duration(extension)
        subscription?.renew(more)
        schedule(more)
    }

    /** Delivers one event from the subscription's listener; blocks the listener when the buffer is full. */
    void deliver(T event) {
        if (inner.isClosed()) {
            return
        }
        try {
            AsyncSupport.await(inner.send(event))
        } catch (groovy.concurrent.ChannelClosedException ignored) {
            // closed between the check and the send
        }
    }

    @Override
    boolean close() {
        Subscription s = subscription
        subscription = null
        if (s != null) {
            s.close()
        }
        ScheduledFuture<?> e = expiry
        if (e != null) {
            e.cancel(false)
        }
        inner.close()
    }

    @Override
    int getCapacity() { inner.capacity }

    @Override
    int getBufferedSize() { inner.bufferedSize }

    @Override
    boolean isClosed() { inner.closed }

    @Override
    Awaitable<Void> send(T value) { inner.send(value) }

    @Override
    Awaitable<T> receive() { inner.receive() }

    @Override
    Iterator<T> iterator() { inner.iterator() }
}
