# Groovy constructs that meet the binder

Written for: Groovy developers who build with agentspaces-groovy.

| Construct | What happens |
|---|---|
| `@CompileStatic` or dynamic class with the Java annotations | binds alike |
| Groovy `record` | a Java record: the entry type a Java peer names the same way (recommended) |
| `@Immutable` class | round-trips through the codec via the generated no-argument constructor; records stay the recommendation |
| a map coerced to an annotated interface (`[price: { }] as Pricer`) | binds: the binder reads annotations through interfaces; the agent DSL stands on this |
| a closure property | not a verb; a class that is only closures binds nothing |
| a trait with an annotated method | binds through the implementing class |
| `@Delegate` to an annotated interface | the class implements the interface, so the verb binds |
| `@Memoized` on a verb | binds, and answers a repeated equal cue from its cache; the checker refuses it |
| `@ActiveMethod` on a verb | returns before the work runs, breaking "the return is the next entry"; the checker refuses it |
| a Java map in a GString or string concatenation | renders Groovy-style (`[k:v]`); `String.valueOf(map)` gives Java's form |
| `'10m' as Duration`, `'10m' as Lease` | the extension module overrides Groovy's own `asType(String, Class)` for these two targets |
