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
import ai.badmonkey.agentspaces.api.space.Subscription
import ai.badmonkey.agentspaces.api.space.TakenEntry
import groovy.transform.CompileStatic

/** {@code handle.renew('10m')}, {@code taken.renew('30s')}, {@code subscription.renew('1h')}. */
@CompileStatic
final class HandleExtensions {

    private HandleExtensions() {
    }

    static void renew(EntryHandle self, Object extension) {
        self.renew(Coercions.duration(extension))
    }

    static void renew(TakenEntry<?> self, Object extension) {
        self.renew(Coercions.duration(extension))
    }

    static void renew(Subscription self, Object extension) {
        self.renew(Coercions.duration(extension))
    }
}
