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

/**
 * The coercions as Groovy methods: {@code '10m' as Duration}, {@code 600_000 as Lease},
 * {@code record.toMap()}, and the static {@code Lease.of('10m')}.
 */
@CompileStatic
final class CoercionExtensions {

    private CoercionExtensions() {
    }

    /** {@code '10m' as Duration}, {@code '10m' as Lease}; anything else as Groovy would. */
    static <T> T asType(String self, Class<T> type) {
        if (type == Duration) {
            return type.cast(Coercions.duration(self))
        }
        if (type == Lease) {
            return type.cast(Coercions.lease(self))
        }
        org.codehaus.groovy.runtime.StringGroovyMethods.asType((CharSequence) self, type)
    }

    /** {@code 600_000 as Duration}, {@code 600_000 as Lease}; anything else as Groovy would. */
    static <T> T asType(Number self, Class<T> type) {
        if (type == Duration) {
            return type.cast(Coercions.duration(self))
        }
        if (type == Lease) {
            return type.cast(Coercions.lease(self))
        }
        org.codehaus.groovy.runtime.DefaultGroovyMethods.asType(self, type)
    }

    /** {@code Duration.ofMinutes(10) as Lease}. */
    static <T> T asType(Duration self, Class<T> type) {
        if (type == Lease) {
            return type.cast(Lease.of(self))
        }
        org.codehaus.groovy.runtime.DefaultGroovyMethods.asType(self, type)
    }

    /** A record's components as an ordered map. */
    static Map<String, Object> toMap(Record self) {
        Coercions.map(self)
    }
}
