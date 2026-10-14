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

import groovy.transform.CompileStatic
import groovy.transform.Immutable

import java.lang.annotation.Annotation

/**
 * One clause of an agent DSL (SRAD section 6.3, GR-F-01): the verb
 * annotation, the method name, the cue types (two for reduce), the options
 * that become annotation attributes, the declared return type, whether the
 * body wants maps, and the body. Plain data kept apart from the interface
 * writer so a compile-time transform can read the same model later.
 */
@CompileStatic
@Immutable(knownImmutableClasses = [Class, Closure, Map, List])
class ClauseModel {
    Class<? extends Annotation> verb
    String name
    List<Class<?>> cues
    Map<String, Object> options
    Class<?> returns
    boolean asMap
    Closure<?> body
}
