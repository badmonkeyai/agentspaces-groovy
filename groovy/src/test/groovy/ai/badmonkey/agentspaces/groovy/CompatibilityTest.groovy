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

import ai.badmonkey.agentspaces.agent.AgentBinder
import ai.badmonkey.agentspaces.agent.annotation.AgentSpec
import ai.badmonkey.agentspaces.agent.annotation.SpaceTake
import ai.badmonkey.agentspaces.api.space.Lease
import ai.badmonkey.agentspaces.api.space.Space
import ai.badmonkey.agentspaces.api.space.Template
import ai.badmonkey.agentspaces.groovy.support.Fleets
import ai.badmonkey.agentspaces.groovy.support.ItFinding
import ai.badmonkey.agentspaces.groovy.support.ItTask
import ai.badmonkey.agentspaces.runtime.FleetRuntime
import groovy.transform.Immutable
import groovy.transform.Memoized
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout

import java.time.Duration

import static org.assertj.core.api.Assertions.assertThat

/**
 * The compatibility table (SRAD GR-B-04): Groovy constructs that meet the
 * binder, one test per row, each row a sentence the guide repeats. One
 * runtime serves every row; each row uses its own task topic.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompatibilityTest {

    private static final Lease TEN_MINUTES = Lease.of(Duration.ofMinutes(10))

    private FleetRuntime runtime

    @BeforeAll
    void start() {
        runtime = Fleets.start(Fleets.freePort(), 0)
    }

    @AfterAll
    void stop() {
        runtime.close()
    }

    private Space tasks() {
        runtime.spaces().group('it-fleet').space('tasks')
    }

    private ItFinding findingFor(String topic) {
        Space tasks = tasks()
        tasks.write(new ItTask(topic, 1), TEN_MINUTES)
        def template = Template.of(ItFinding).where('topic', ai.badmonkey.agentspaces.api.space.Matchers.eq(topic))
        assertThat(Fleets.await(Duration.ofSeconds(20)) { tasks.read(template).isPresent() })
                .as("a finding for $topic").isTrue()
        tasks.read(template).orElseThrow()
    }

    // Row: the annotated interface a map of closures is coerced to (a java.lang.reflect.Proxy) binds,
    // because the binder reads annotations through interfaces. This is what the agent DSL stands on.
    @AgentSpec(name = 'coerced', description = 'An interface a map is coerced to')
    interface Coerced {
        @SpaceTake(space = 'tasks', pollTimeout = 'PT0.2S', where = ['topic=coerced'])
        ItFinding summarize(ItTask task)
    }

    @Test
    @Timeout(60L)
    void aMapCoercedToAnAnnotatedInterfaceBinds() {
        Coerced agent = [summarize: { ItTask t -> new ItFinding(t.topic, 'from a coerced map') }] as Coerced
        AgentBinder.Bound bound = runtime.bind(agent).first()
        assertThat(bound.card().agent().localName()).isEqualTo('coerced')
        assertThat(findingFor('coerced').summary()).isEqualTo('from a coerced map')
    }

    // Row: a closure property is a field holding a Closure; the binder sees no annotated method and
    // binds nothing from it. An agent that is only closures fails to bind, naming the class.
    static class ClosuresOnly {
        def summarize = { ItTask t -> new ItFinding(t.topic, 'never called') }
    }

    @Test
    void aClosurePropertyIsNotAVerb() {
        assertThat(AgentBinder.hasBindings(ClosuresOnly)).isFalse()
        assertThat(runtime.bind(new ClosuresOnly())).isEmpty()
    }

    // Row: a trait method carrying a verb annotation binds through the implementing class,
    // because Groovy copies the trait method and its annotations into the class.
    trait Summarizing {
        @SpaceTake(space = 'tasks', pollTimeout = 'PT0.2S', where = ['topic=trait'])
        ItFinding summarize(ItTask task) {
            new ItFinding(task.topic, 'from a trait')
        }
    }

    @AgentSpec(name = 'trait-worker', description = 'Implements a trait that carries the verb')
    static class TraitWorker implements Summarizing {
    }

    @Test
    @Timeout(60L)
    void aTraitMethodWithAVerbAnnotationBindsThroughTheImplementingClass() {
        assertThat(runtime.bind(new TraitWorker())).hasSize(1)
        assertThat(findingFor('trait').summary()).isEqualTo('from a trait')
    }

    // Row: @Delegate to a field whose type is an annotated interface makes the class implement that
    // interface, so the binder finds the verb on the interface and calls the delegating method.
    @AgentSpec(name = 'delegated', description = 'Delegates to an annotated interface')
    interface Delegated {
        @SpaceTake(space = 'tasks', pollTimeout = 'PT0.2S', where = ['topic=delegate'])
        ItFinding summarize(ItTask task)
    }

    static class Target implements Delegated {
        ItFinding summarize(ItTask task) {
            new ItFinding(task.topic, 'from a delegate')
        }
    }

    static class Delegating {
        @Delegate Delegated target = new Target()
    }

    @Test
    @Timeout(60L)
    void aDelegateToAnAnnotatedInterfaceExposesTheVerb() {
        assertThat(Delegated.isAssignableFrom(Delegating)).isTrue()
        assertThat(runtime.bind(new Delegating())).hasSize(1)
        assertThat(findingFor('delegate').summary()).isEqualTo('from a delegate')
    }

    // Row: @Memoized keeps the annotation on the verb method, so it binds; the result is cached per
    // equal cue, which is why the checker refuses it on a verb (phase 3). Here: it binds and runs once.
    @AgentSpec(name = 'memoized', description = 'A memoized verb')
    static class MemoizedWorker {
        int calls = 0

        @Memoized
        @SpaceTake(space = 'tasks', pollTimeout = 'PT0.2S', where = ['topic=memo'])
        ItFinding summarize(ItTask task) {
            calls++
            new ItFinding(task.topic, "memoized call $calls")
        }
    }

    @Test
    @Timeout(60L)
    void aMemoizedVerbBindsAndCachesPerCue() {
        def worker = new MemoizedWorker()
        assertThat(runtime.bind(worker)).hasSize(1)
        assertThat(findingFor('memo').summary()).isEqualTo('memoized call 1')
        // A second equal cue is answered from the cache: the method body does not run again.
        Space tasks = tasks()
        tasks.write(new ItTask('memo', 1), TEN_MINUTES)
        assertThat(Fleets.await(Duration.ofSeconds(20)) { tasks.readAll(Template.of(ItFinding), 10).count { it.topic() == 'memo' } == 2 }).isTrue()
        assertThat(worker.calls).isEqualTo(1)
    }

    // Row: a Groovy @Immutable class round-trips through the codec (Jackson builds it through the
    // generated no-argument constructor and sets the final fields), so it is an entry type; records
    // stay the recommendation because a Java peer names and builds them the same way.
    @Immutable
    static class Point {
        int x
        int y
    }

    @Test
    void anImmutableClassRoundTripsThroughTheCodec() {
        def codec = ai.badmonkey.agentspaces.common.codec.CborCodec.defaultCodec()
        byte[] bytes = codec.toBytes(new Point(1, 2))
        assertThat(codec.fromBytes(bytes, Point)).isEqualTo(new Point(1, 2))
    }

    // Row: in dynamic Groovy a map renders as [k:v] in every string context, its toString() included,
    // because Groovy's own toString(Map) is preferred; String.valueOf(map) gives the Java form.
    @Test
    void aMapInAStringContextRendersInGroovysForm() {
        Map<String, Integer> tally = new LinkedHashMap<>([approve: 1, deny: 0])
        assertThat("tally ${tally}".toString()).isEqualTo('tally [approve:1, deny:0]')
        assertThat('tally ' + tally).isEqualTo('tally [approve:1, deny:0]')
        assertThat(tally.toString()).isEqualTo('[approve:1, deny:0]')
        assertThat(String.valueOf(tally)).isEqualTo('{approve=1, deny=0}')
    }
}
