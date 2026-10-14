# Example 21: the Groovy fleet

Three Groovy peers on example 02's research fleet, beside its Java peers:

| File | What it is |
|---|---|
| `scripts/worker.groovy` | A one-file peer: `@Grab`, `fleet { }`, one `agent { }` clause, `serve()`. `groovy scripts/worker.groovy 7591 127.0.0.1:7590` |
| `scripts/intake.groovy` | CSV rows in (`groovy-csv`), one task per row, findings collected over a channel (`for await`), a Markdown report written and read back through `groovy-markdown`'s table extraction. Both modules are incubating in Groovy 6.0. |
| `Planner.groovy` | The class form: `@CompileStatic(extensions = FleetChecker)` with the Java annotations; a misspelt `where` component fails compilation. `PlannerApp` runs it from `agentspaces.yaml`. |
| `GroovyFleetTest` | The fleet test: example 02's Java peer founds the group, the script worker and the planner join, a Java `RemoteActions` caller invokes the script's card, and the intake script writes the report. |

The scripts run against an installed Groovy 6 with `@Grab`; the test runs the same sources through a `GroovyShell` with the `@Grab` lines removed, because the test classpath already carries the artifacts.

```bash
# in one terminal: a Java seed peer of example 02 (or the example's all-in-one demo)
mvn -q -f ../../../agentspaces/pom.xml -pl examples/example-02-research-fleet exec:java -Dexec.args="coordinator 7590"
# in another: the Groovy worker, then the intake
groovy scripts/worker.groovy 7591 127.0.0.1:7590
groovy scripts/intake.groovy topics.csv report.md 7592 127.0.0.1:7590
```
