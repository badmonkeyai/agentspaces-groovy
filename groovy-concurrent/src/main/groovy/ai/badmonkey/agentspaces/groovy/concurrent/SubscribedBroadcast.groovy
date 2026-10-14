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
import groovy.concurrent.AsyncChannel
import groovy.concurrent.BroadcastChannel
import groovy.transform.CompileStatic
import org.apache.groovy.runtime.async.AsyncSupport

import java.util.concurrent.Flow

/** A {@code BroadcastChannel} fed by one space subscription (GR-C-06): several consumers of one subscription. */
@CompileStatic
final class SubscribedBroadcast<T> implements AutoCloseable {

    private final BroadcastChannel<T> inner = BroadcastChannel.<T> create()
    private volatile Subscription subscription

    void attach(Subscription subscription) {
        this.subscription = subscription
    }

    /** A per-subscriber channel; {@code buffer} default 64. */
    AsyncChannel<T> subscribe(int buffer = 64) {
        inner.subscribe(buffer)
    }

    Flow.Publisher<T> asPublisher() {
        inner.asPublisher()
    }

    int getSubscriberCount() {
        inner.subscriberCount
    }

    boolean isClosed() {
        inner.closed
    }

    void deliver(T event) {
        if (!inner.closed) {
            AsyncSupport.await(inner.send(event))
        }
    }

    @Override
    void close() {
        Subscription s = subscription
        subscription = null
        s?.close()
        inner.close()
    }
}
