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

import ai.badmonkey.agentspaces.groovy.support.Fleets
import ai.badmonkey.agentspaces.groovy.support.ItFinding
import ai.badmonkey.agentspaces.groovy.support.ItTask
import ai.badmonkey.agentspaces.runtime.FleetRuntime
import ai.badmonkey.agentspaces.runtime.PropertiesLoader
import ai.badmonkey.agentspaces.spring.AgentSpacesProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

import java.time.Duration

import static org.assertj.core.api.Assertions.assertThat
import static org.assertj.core.api.Assertions.assertThatThrownBy

/** The fleet DSL (GR-D-01 to GR-D-03): the same tree as YAML, every keyword a starter property, failures by name. */
class FleetDslTest {

    @Test
    void theDslAndTheYamlOfOneConfigurationProduceEqualTrees() {
        Map<String, Object> fromDsl = Fleet.tree {
            bind '127.0.0.1:7591'
            keystore './peer-keys'
            roles 'relay', 'rendezvous'
            tick '250ms'
            lazyPush false
            security {
                profile 'mtls'
                grants { raftVoter 'z6MkA', 'z6MkB'; spaceWrite(['z6MkC']) }
            }
            transport { tls { trustStore './ca.p12'; requireAttestation() } }
            identity { agentKeys 'subordinate' }
            multicast { enabled(); group '239.255.42.99:7787'; interval '5s' }
            group('research') {
                seeds 'tls://10.0.0.5:7590', 'tls://10.0.0.6:7590'
                founding 'research-fleet-v1'
                joinTimeout '10s'
                space('tasks') { strategy 'LEASE_RACE'; admission 'credential'; settleWindow '100ms' }
                space 'findings'
                space(name: 'votes', strategy: 'LEASE_RACE')
            }
            capabilities { orderedLog(); votesSpace 'votes'; aggregate false }
        }
        Map<String, Object> fromYaml = PropertiesLoader.agentspacesSubtree(PropertiesLoader.tree(new StringReader('''
            agentspaces:
              bind: 127.0.0.1:7591
              keystore: ./peer-keys
              roles: [relay, rendezvous]
              tick-millis: 250
              lazy-push: false
              security:
                profile: mtls
                grants:
                  raft-voter: [z6MkA, z6MkB]
                  space-write: [z6MkC]
              transport:
                tls:
                  trust-store: ./ca.p12
                  require-attestation: true
              identity:
                agent-keys: subordinate
              multicast:
                enabled: true
                group: 239.255.42.99:7787
                interval-millis: 5000
              groups:
                - name: research
                  seeds: [tls://10.0.0.5:7590, tls://10.0.0.6:7590]
                  founding: research-fleet-v1
                  join-timeout-millis: 10000
                  spaces:
                    - name: tasks
                      strategy: LEASE_RACE
                      admission: credential
                      settle-window-millis: 100
                    - name: findings
                    - name: votes
                      strategy: LEASE_RACE
              capabilities:
                ordered-log: true
                votes-space: votes
                aggregate: false
            '''.stripIndent())))
        assertThat(fromDsl).isEqualTo(fromYaml)

        AgentSpacesProperties bound = PropertiesLoader.map(fromDsl)
        assertThat(bound.bind).isEqualTo('127.0.0.1:7591')
        assertThat(bound.tickMillis).isEqualTo(250L)
        assertThat(bound.roles).containsExactly('relay', 'rendezvous')
        assertThat(bound.groups).singleElement().satisfies { g ->
            assertThat(g.seeds).hasSize(2)
            assertThat(g.spaces*.name).containsExactly('tasks', 'findings', 'votes')
            assertThat(g.spaces[0].settleWindowMillis).isEqualTo(100L)
        }
        assertThat(bound.capabilities.orderedLog).isTrue()
        assertThat(bound.capabilities.aggregate).isFalse()
        assertThat(bound.transport.tls.requireAttestation).isTrue()
    }

    @Test
    void anUnknownKeywordFailsNamingTheLevelTheNearestAndTheKnown() {
        assertThatThrownBy { Fleet.tree { bnid '127.0.0.1:7591' } }
                .hasMessageContaining("'bnid' is not a property of agentspaces")
                .hasMessageContaining('Did you mean bind')
                .hasMessageContaining('Known: ')
        assertThatThrownBy { Fleet.tree { group('g') { sapce 'tasks' } } }
                .hasMessageContaining("'sapce' is not a property of agentspaces.groups[0] (Group)")
                .hasMessageContaining('Did you mean').hasMessageContaining('spaces')
        assertThatThrownBy { Fleet.tree { security { grants { raftVoters 'x' } } } }
                .hasMessageContaining('agentspaces.security.grants (Grants)')
                .hasMessageContaining('raftVoter')
        assertThatThrownBy { Fleet.tree { tickMillis 'soon' } }.hasMessageContaining('not a duration')
        assertThatThrownBy { Fleet.tree { lazyPush 'yes' } }.hasMessageContaining('takes true, false, or nothing')
        assertThatThrownBy { Fleet.tree { roles { } } }.hasMessageContaining('takes values, not a block')
        assertThatThrownBy { Fleet.tree { security 'mtls' } }.hasMessageContaining('takes a block or a map')
        assertThatThrownBy { Fleet.tree { bind 1, 2 } }.hasMessageContaining('takes one value')
    }

    private String seedOf(int port) {
        "127.0.0.1:$port"
    }

    @Test
    void variablesAndMethodsOfTheEnclosingScopeResolveInsideABlock() {
        int seedPort = 7590
        String keys = './keys'      // a local named like a keyword would shadow it: Groovy resolves locals first
        def tree = Fleet.tree {
            keystore keys
            group('g') { seeds seedOf(seedPort), "127.0.0.1:${seedPort + 1}"; space 'tasks' }
        }
        assertThat(tree.keystore).isEqualTo('./keys')
        assertThat(((List<Map>) tree.groups)[0].seeds).isEqualTo(['127.0.0.1:7590', '127.0.0.1:7591'])
        assertThatThrownBy { Fleet.tree { nothingLikeThis 1 } }.hasMessageContaining("'nothingLikeThis' is not a property of agentspaces")
        assertThatThrownBy { Fleet.tree { group('g') { seeds noSuchVariable } } }.hasMessageContaining("'noSuchVariable' is not a property of agentspaces.groups[0]")
    }

    @Test
    void propertySyntaxAndMapsWorkBesideTheKeywordSyntax() {
        def tree = Fleet.tree {
            bind = '127.0.0.1:7591'
            security(profile: 'dev-local')
            group(name: 'g', founding: 'f') { space 'tasks' }
            capabilities { semanticDiscovery }
        }
        assertThat(tree).containsEntry('bind', '127.0.0.1:7591')
        assertThat((Map) tree.security).containsEntry('profile', 'dev-local')
        assertThat((Map) tree.capabilities).containsEntry('semantic-discovery', true)
        assertThat(((List<Map>) tree.groups)[0]).containsEntry('founding', 'f')
    }

    @Test
    @Timeout(90L)
    void aPeerStartsFromTheDslAndFromYamlTextAndTheyServeEachOther() {
        int seedPort = Fleets.freePort()
        FleetRuntime worker = Fleet.start {
            bind "127.0.0.1:$seedPort"
            tick '200ms'
            group('it-fleet') {
                founding 'groovy-it-fleet-v1'
                space('tasks') { settleWindow '100ms' }
            }
        }
        FleetRuntime client = Fleet.start("""
            agentspaces:
              bind: 127.0.0.1:${Fleets.freePort()}
              tick-millis: 200
              groups:
                - name: it-fleet
                  founding: groovy-it-fleet-v1
                  seeds: [127.0.0.1:$seedPort]
                  spaces:
                    - name: tasks
                      settle-window-millis: 100
            """.stripIndent())
        try {
            worker.bind(new FloorTest.StaticSummarizer())
            def tasks = client.context().group().space('tasks')
            tasks << new ItTask('dsl', 2)
            assertThat(Fleets.await(Duration.ofSeconds(30)) { tasks.count(ItFinding) == 1 }).isTrue()
            assertThat(tasks.read(ItFinding).get().summary()).isEqualTo('static summary of dsl')
        } finally {
            client.close()
            worker.close()
        }
    }
}
