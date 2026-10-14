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

import ai.badmonkey.agentspaces.api.space.Matchers
import org.junit.jupiter.api.Test

import java.lang.reflect.Modifier

import static org.assertj.core.api.Assertions.assertThat

/**
 * GR-Q-MNT-01, the matcher half: every public static of {@code Matchers} has
 * a {@code Where} function of the same name, except {@code in}, which is a
 * Groovy keyword and is spelled {@code anyOf}. A new matcher in the core
 * fails this test until the DSL carries it.
 */
class NameParityTest {

    @Test
    void everyMatcherHasADslFunction() {
        Set<String> matchers = Matchers.declaredMethods.findAll { Modifier.isPublic(it.modifiers) && Modifier.isStatic(it.modifiers) }*.name as Set
        Set<String> dsl = Where.declaredMethods.findAll { Modifier.isPublic(it.modifiers) && Modifier.isStatic(it.modifiers) }*.name as Set
        Set<String> expected = matchers.collect { it == 'in' ? 'anyOf' : it } as Set
        assertThat(dsl).containsAll(expected)
    }
}
