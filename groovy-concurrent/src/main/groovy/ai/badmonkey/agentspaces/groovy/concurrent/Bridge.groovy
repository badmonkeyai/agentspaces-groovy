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
import groovy.concurrent.Awaitable
import groovy.concurrent.AwaitableAdapter
import groovy.concurrent.AwaitableAdapterRegistry
import groovy.transform.CompileStatic

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Installs the bridge's adapter: {@code await subscription} fails with a
 * message naming {@code channel} rather than hanging (GR-C-04). Installed
 * when the extension module loads; {@link #install} is idempotent.
 */
@CompileStatic
final class Bridge {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean()

    static final AwaitableAdapter SUBSCRIPTIONS = new AwaitableAdapter() {
        @Override
        boolean supportsAwaitable(Class<?> type) {
            Subscription.isAssignableFrom(type)
        }

        @Override
        <T> Awaitable<T> toAwaitable(Object value) {
            Awaitable.failed(new IllegalArgumentException('a Subscription is not awaitable: it delivers events for as long as its lease lasts. Use space.channel(Type) with for await, or space.notify(Type) { event -> }'))
        }
    }

    private Bridge() {
    }

    static void install() {
        if (INSTALLED.compareAndSet(false, true)) {
            AwaitableAdapterRegistry.register(SUBSCRIPTIONS)
        }
    }
}
