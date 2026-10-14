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
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.customizers.SecureASTCustomizer

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Compiles generated interfaces once per shape (GR-A-05, GR-Q-PRF-02): the
 * loader is a child of the cue types' loader so a script's records resolve;
 * the compiler configuration admits no closures, no imports, and nothing
 * outside the generated package.
 */
@CompileStatic
final class InterfaceCache {

    private static final Map<ClassLoader, InterfaceCache> CACHES = Collections.synchronizedMap(new WeakHashMap<ClassLoader, InterfaceCache>())
    private static final AtomicInteger COMPILATIONS = new AtomicInteger()

    private final GroovyClassLoader loader
    private final Map<String, Class<?>> classes = new ConcurrentHashMap<>()

    private InterfaceCache(ClassLoader parent) {
        CompilerConfiguration config = new CompilerConfiguration()
        SecureASTCustomizer secure = new SecureASTCustomizer()
        secure.closuresAllowed = false
        secure.methodDefinitionAllowed = true
        secure.packageAllowed = true
        secure.importsBlacklist = []
        secure.starImportsBlacklist = []
        secure.indirectImportCheckEnabled = false
        config.addCompilationCustomizers(secure)
        this.loader = new GroovyClassLoader(parent, config)
    }

    /** The cache for a parent class loader (one per loader, held weakly). */
    static InterfaceCache forLoader(ClassLoader parent) {
        CACHES.computeIfAbsent(parent) { ClassLoader p -> new InterfaceCache(p) }
    }

    /** The cache whose parent is the first cue type's loader, else this class's. */
    static InterfaceCache forModel(AgentModel model) {
        ClassLoader parent = model.clauses.first().cues.first().classLoader ?: InterfaceCache.classLoader
        forLoader(parent)
    }

    /** How many interfaces have been compiled in this JVM; a second bind of the same shape adds none. */
    static int compilations() {
        COMPILATIONS.get()
    }

    /** The interface for a model, compiled on first use. */
    Class<?> interfaceFor(AgentModel model) {
        String name = InterfaceWriter.nameFor(model)
        classes.computeIfAbsent(name) { String n ->
            String source = InterfaceWriter.source(model, n)
            COMPILATIONS.incrementAndGet()
            Class<?> parsed = loader.parseClass(source, n + '.groovy')
            if (!parsed.isInterface() || parsed.name != InterfaceWriter.PACKAGE + '.' + n) {
                throw new IllegalStateException("generated source did not compile to the expected interface: ${parsed}")
            }
            parsed
        }
    }
}
