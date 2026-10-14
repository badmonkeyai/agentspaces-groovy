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
import ai.badmonkey.agentspaces.groovy.support.ItTask
import org.junit.jupiter.api.Test

import java.time.Duration

import static org.assertj.core.api.Assertions.assertThat
import static org.assertj.core.api.Assertions.assertThatThrownBy

/** GR-V-03 and GR-V-04: durations, leases, and records from maps and back. */
class CoercionsTest {

    record Nested(String name, Inner inner, Level level, long big, double ratio) {}

    record Inner(int depth) {}

    enum Level { LOW, HIGH }

    @Test
    void durationsAndLeasesCoerceFromStringsNumbersAndEachOther() {
        assertThat('10m' as Duration).isEqualTo(Duration.ofMinutes(10))
        assertThat('PT10M' as Duration).isEqualTo(Duration.ofMinutes(10))
        assertThat('500ms' as Duration).isEqualTo(Duration.ofMillis(500))
        assertThat(600_000 as Duration).isEqualTo(Duration.ofMinutes(10))
        assertThat('10m' as Lease).isEqualTo(Lease.of(Duration.ofMinutes(10)))
        assertThat(600_000 as Lease).isEqualTo(Lease.of(Duration.ofMinutes(10)))
        assertThat(Duration.ofMinutes(10) as Lease).isEqualTo(Lease.of(Duration.ofMinutes(10)))
        assertThat(Lease.of('10m')).isEqualTo(Lease.of(Duration.ofMinutes(10)))
        assertThat(Lease.of(600_000)).isEqualTo(Lease.of(Duration.ofMinutes(10)))
        assertThat(Duration.of('2h')).isEqualTo(Duration.ofHours(2))
        assertThat(Coercions.lease(Lease.of(Duration.ofSeconds(1)))).isEqualTo(Lease.of(Duration.ofSeconds(1)))
        assertThat('42' as Integer).isEqualTo(42)
        assertThatThrownBy { '10 minutes' as Duration }.hasMessageContaining('not a duration').hasMessageContaining('10m')
        assertThatThrownBy { Coercions.duration(new Object()) }.hasMessageContaining('not a duration')
    }

    @Test
    void aRecordComesFromAMapByComponentNameWithWideningAndGoesBackToAMap() {
        ItTask task = Coercions.record(ItTask, [topic: 'x', priority: 3])
        assertThat(task).isEqualTo(new ItTask('x', 3))
        assertThat(task.toMap()).containsExactly(Map.entry('topic', 'x'), Map.entry('priority', 3))

        Nested nested = Coercions.record(Nested, [name: 'n', inner: [depth: 2], level: 'HIGH', big: 7, ratio: 1])
        assertThat(nested.inner()).isEqualTo(new Inner(2))
        assertThat(nested.level()).isEqualTo(Level.HIGH)
        assertThat(nested.big()).isEqualTo(7L)
        assertThat(nested.ratio()).isEqualTo(1.0d)
        assertThat(Coercions.record(Nested, [name: 'n', inner: null, level: null, big: 1.5, ratio: 2.5G]).ratio()).isEqualTo(2.5d)

        assertThatThrownBy { Coercions.record(ItTask, [topic: 'x', prio: 3]) }
                .hasMessageContaining('no component [prio]').hasMessageContaining('[priority, topic]')
        assertThatThrownBy { Coercions.record(ItTask, [topic: 'x']) }.hasMessageContaining('priority').hasMessageContaining('required')
        assertThatThrownBy { Coercions.record(ItTask, [topic: 'x', priority: 'three']) }.hasMessageContaining('priority')
        assertThatThrownBy { Coercions.record(String, [:]) }.hasMessageContaining('not a record')
    }
}
