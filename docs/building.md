# Building

Written for: contributors who build this repository.

```bash
mvn -B -ntp verify                         # the library and the bridge: 72 and 7 tests (60 @Test methods plus FleetCheckerTest's 12 parameterized refusal rows; 7 @Test methods)
mvn -B -ntp verify -Pexamples              # plus example 21 (examples/groovy-fleet), on example 02's fleet
mvn -B -ntp verify -Phosts                 # plus the Spring Boot and Micronaut passthroughs
ci/grab-check.sh                           # the @Grab script against an installed Groovy 6 and the local repository
```

Maven with `gmavenplus-plugin` 5.1.0 compiles the Groovy sources; JUnit
Jupiter runs the tests (no Spock build exists for Groovy 6). The enforcer keeps
Spring, Micronaut, LangChain4j, and `groovy-concurrent-java` off the library's
classpath. The incubating Groovy features this project touches: the
`groovy.concurrent` toolkit (the bridge artifact), `groovy-csv` and
`groovy-markdown` (example 21 only), and Grape's dual engine (the `@Grab`
recipe); a Groovy minor that changes them touches those places and nothing else.
