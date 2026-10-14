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

import ai.badmonkey.agentspaces.groovy.Coercions
import groovy.concurrent.AsyncChannel
import groovy.concurrent.ChannelSelect
import groovy.transform.CompileStatic

import java.util.concurrent.CompletableFuture

/**
 * Select recipes (GR-C-06): the first event from any of several channels,
 * with a timeout, as a {@code CompletableFuture} of the select result
 * ({@code result.index}, {@code result.value}, {@code result.timeout}).
 */
@CompileStatic
final class Selects {

    private Selects() {
    }

    /** The first channel to deliver, or a timeout result after {@code timeout}. */
    static CompletableFuture<ChannelSelect.Result> firstOf(Object timeout, AsyncChannel<?>... channels) {
        List<ChannelSelect.Offer> offers = channels.collect { AsyncChannel<?> c -> ChannelSelect.receive(c) }
        offers << ChannelSelect.after(Coercions.duration(timeout))
        ChannelSelect.offers(offers as ChannelSelect.Offer[]).select().toCompletableFuture()
    }

    static CompletableFuture<ChannelSelect.Result> firstOf(AsyncChannel<?>... channels) {
        ChannelSelect.from(channels).select().toCompletableFuture()
    }
}
