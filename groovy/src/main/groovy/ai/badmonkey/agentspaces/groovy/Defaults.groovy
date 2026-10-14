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

import ai.badmonkey.agentspaces.api.space.Lease
import ai.badmonkey.agentspaces.api.space.Space
import groovy.transform.CompileStatic

import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** The per-space default lease behind {@code space << entry}; keyed by the space instance, released with it. */
@CompileStatic
final class Defaults {

    static final Lease TEN_MINUTES = Lease.of(Duration.ofMinutes(10))

    private static final Map<Space, Lease> LEASES = Collections.synchronizedMap(new WeakHashMap<Space, Lease>())

    private Defaults() {
    }

    static Lease leaseOf(Space space) {
        LEASES.get(space) ?: TEN_MINUTES
    }

    static void lease(Space space, Lease lease) {
        LEASES.put(space, lease)
    }
}
