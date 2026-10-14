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

import org.codehaus.groovy.control.MultipleCompilationErrorsException
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

import java.util.stream.Stream

import static org.assertj.core.api.Assertions.assertThat
import static org.assertj.core.api.Assertions.assertThatThrownBy

/**
 * The checker (GR-T-01, GR-Q-MNT-03, GR-Q-TST-02): snippets compiled under
 * {@code @CompileStatic(extensions = FleetChecker)}, each row the refusal the
 * guide's table shows; and the positive rows that must compile.
 */
class FleetCheckerTest {

    private static final String PRELUDE = '''
        import groovy.transform.CompileStatic
        import ai.badmonkey.agentspaces.agent.annotation.*
        import ai.badmonkey.agentspaces.agent.join.Joined
        import ai.badmonkey.agentspaces.capabilities.vote.VoteCapability
        import ai.badmonkey.agentspaces.groovy.support.*
        import ai.badmonkey.agentspaces.groovy.Fleet
        import static ai.badmonkey.agentspaces.groovy.Agents.agent
    '''
    private static final String CHECKED = "@CompileStatic(extensions = 'ai.badmonkey.agentspaces.groovy.FleetChecker')"

    private static Object compile(String source) {
        new GroovyShell(FleetCheckerTest.classLoader).parse(PRELUDE + source)
    }

    static Stream<Arguments> refusals() {
        Stream.of(
                Arguments.of('a where field that is not a component',
                        """$CHECKED class A { @SpaceTake(space = 'tasks', where = ['priorty=1']) ItFinding f(ItTask t) { null } }""".toString(),
                        ["ItTask has no component 'priorty'", 'components: [priority, topic]']),
                Arguments.of('a lease that does not parse',
                        """$CHECKED class A { @SpaceTake(space = 'tasks', lease = '10 minutes') ItFinding f(ItTask t) { null } }""".toString(),
                        ['@SpaceTake.lease on f', 'not a duration']),
                Arguments.of('a ballot that returns the wrong type',
                        """$CHECKED class A { @Ballot(space = 'votes') int f(VoteCapability.Proposal p) { 1 } }""".toString(),
                        ['@Ballot method returns the chosen option as a String', 'f returns int']),
                Arguments.of('@Memoized on a verb',
                        """$CHECKED class A { @groovy.transform.Memoized @SpaceTake(space = 'tasks') ItFinding f(ItTask t) { null } }""".toString(),
                        ['@Memoized cannot sit on a verb method', 'repeated cue from its cache']),
                Arguments.of('@ActiveMethod on a verb',
                        """$CHECKED class A { @groovy.transform.ActiveMethod @SpaceTake(space = 'tasks') ItFinding f(ItTask t) { null } }""".toString(),
                        ['@ActiveMethod cannot sit on a verb method', 'returns before the work runs']),
                Arguments.of('a DSL where key that is not a component',
                        """$CHECKED class A { def a() { agent('x') { take(ItTask, where: ['topc=1']) { ItTask t -> null } } } }""".toString(),
                        ["take clause where:: ItTask has no component 'topc'"]),
                Arguments.of('a DSL duration that does not parse',
                        """$CHECKED class A { def a() { agent('x') { notify(ItTask, lease: 'soon') { ItTask t -> null } } } }""".toString(),
                        ['notify clause lease:', 'not a duration']),
                Arguments.of('a DSL mode that is not a constant',
                        """$CHECKED class A { def a() { agent('x') { join(key: 'k', mode: 'sideways', parts: [ItTask]) { Joined j -> null } } } }""".toString(),
                        ['mode: wants one of [local, leased, ordered]']),
                Arguments.of('mode on a verb without one',
                        """$CHECKED class A { def a() { agent('x') { take(ItTask, mode: 'leased') { ItTask t -> null } } } }""".toString(),
                        ['mode: applies to join and reduce clauses only']),
                Arguments.of('a part without a class',
                        """$CHECKED class A { def a() { agent('x') { join(key: 'k', parts: [[keyTag: 'topic']]) { Joined j -> null } } } }""".toString(),
                        ['each map needs value:']),
                Arguments.of('a body whose parameter disagrees with the cue',
                        """$CHECKED class A { def a() { agent('x') { take(ItTask, space: 'tasks') { ItFinding f -> null } } } }""".toString(),
                        ["the body's first parameter is ItFinding but the cue is ItTask"]),
                Arguments.of('a reduce body whose accumulator disagrees',
                        """$CHECKED class A { def a() { agent('x') { reduce(ItTally, ItTask, space: 'tasks', key: 'topic') { ItTask a, ItTask t -> null } } } }""".toString(),
                        ["the body's first parameter is ItTask but the cue is ItTally"]),
        )
    }

    @ParameterizedTest(name = '{0}')
    @MethodSource('refusals')
    void theCheckerRefuses(String row, String source, List<String> fragments) {
        assertThatThrownBy { compile(source) }.isInstanceOf(MultipleCompilationErrorsException).satisfies { Throwable t ->
            fragments.each { String f -> assertThat(t.message).as(row).contains(f) }
        }
    }

    @Test
    void validClassesAndClausesCompileUnderTheChecker() {
        compile("""$CHECKED class A {
            @SpaceTake(space = 'tasks', lease = '10m', pollTimeout = 'PT0.2S', where = ['priority=1', 'topic!=x']) ItFinding f(ItTask t) { null }
            @Ballot(space = 'votes') String g(VoteCapability.Proposal p) { 'yes' }
            @SpaceReduce(space = 'tasks', key = 'topic', where = ['priority=2']) ItTally h(ItTally acc, ItTask t) { acc }
            def a() {
                agent('x', description: 'd') {
                    take(ItTask, space: 'tasks', lease: '10m', where: ['priority=1'], returns: ItFinding) { ItTask t -> new ItFinding(t.topic(), 's') }
                    take(ItTask, space: 'tasks', asMap: true, returns: ItFinding) { Map m -> [topic: m.topic, summary: 's'] }
                    join(space: 'tasks', key: 'topic', mode: 'leased', parts: [ItTask, [value: ItFinding, keyTag: 'topic']]) { Joined j -> null }
                    reduce(ItTally, ItTask, space: 'tasks', key: 'topic') { ItTally acc, ItTask t -> acc }
                    ballot(space: 'votes') { p -> 'approve' }
                }
            }
        }""")
    }

    @Test
    void theFleetDslCompilesStaticallyBecauseKeywordsResolveAtRunTime() {
        def script = compile("""$CHECKED class A {
            Map tree() {
                Fleet.tree {
                    bind '127.0.0.1:7591'
                    tick '250ms'
                    group('research') { seeds 'a:1', 'b:2'; space('tasks') { strategy 'LEASE_RACE' } }
                    capabilities { orderedLog() }
                }
            }
        }
        new A().tree()""")
        Map tree = (Map) script.run()
        assertThat(tree).containsEntry('tick-millis', 250)
        assertThat((Map) tree.capabilities).containsEntry('ordered-log', true)
    }
}
