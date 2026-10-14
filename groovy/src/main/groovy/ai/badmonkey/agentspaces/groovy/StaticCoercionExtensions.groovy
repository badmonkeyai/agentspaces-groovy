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
import groovy.transform.CompileStatic

import java.time.Duration

/** Static extensions: {@code Lease.of('10m')}, {@code Lease.of(600_000)}, {@code Duration.of('10m')}. */
@CompileStatic
final class StaticCoercionExtensions {

    private StaticCoercionExtensions() {
    }

    static Lease of(Lease self, CharSequence value) {
        Coercions.lease(value)
    }

    static Lease of(Lease self, Number millis) {
        Coercions.lease(millis)
    }

    static Duration of(Duration self, CharSequence value) {
        Coercions.duration(value)
    }
}
