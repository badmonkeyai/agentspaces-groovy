// Example 21: CSV in, entries out, a Markdown report back, on the research fleet.
//
//   groovy intake.groovy topics.csv report.md [port] [seed-host:port]
//
// The CSV and Markdown modules are incubating in Groovy 6.0; this script is where the example uses them.
@Grab('ai.badmonkey.agentspaces:agentspaces-groovy:0.4.0')
@Grab('ai.badmonkey.agentspaces:agentspaces-groovy-concurrent:0.4.0')
@Grab('ai.badmonkey.agentspaces:example-02-research-fleet:0.4.0')
@Grab('org.apache.groovy:groovy-csv:6.0.0')
@Grab('org.apache.groovy:groovy-markdown:6.0.0')
import ai.badmonkey.agentspaces.examples.fleet.ResearchFleet.ResearchTask
import ai.badmonkey.agentspaces.examples.fleet.ResearchFleet.Finding
import groovy.csv.CsvSlurper
import groovy.markdown.MarkdownSlurper
@groovy.transform.BaseScript ai.badmonkey.agentspaces.groovy.FleetScript _

File csv = new File(args ? args[0] : 'topics.csv')
File report = new File(args.size() > 1 ? args[1] : 'report.md')
int port = args.size() > 2 ? (args[2] as int) : 7592
String seed = args.size() > 3 ? args[3] : '127.0.0.1:7590'

fleet {
    bind "127.0.0.1:$port"
    tick '250ms'
    security { profile 'dev-local' }        // example 02's peers speak plain TCP; the starter's default is mtls
    group('research-fleet') { founding 'research-fleet-demo-v1'; seeds seed; space('tasks') { settleWindow '150ms' } }
}
def tasks = space('tasks')

// 1. CSV rows (header: topic,priority) become tasks. Subscribe for findings first, so none is missed.
List<Map<String, String>> rows = new CsvSlurper().setUseHeader(true).parse(csv)
Set<String> pending = rows*.topic as Set
def findings = tasks.channel(Finding, on: 'written', lease: '10m')
rows.each { Map<String, String> row -> tasks << new ResearchTask(row.topic, row.priority as int) }
println "wrote ${rows.size()} tasks from ${csv.name}; waiting for findings"

// 2. Findings arrive on the channel; stop when every topic has one (or after two minutes).
def deadline = System.nanoTime() + 120_000_000_000L
for await (e in findings) {
    pending.remove(e.entry().topic())
    println "  ${e.entry().worker()} researched '${e.entry().topic()}'"
    if (pending.isEmpty() || System.nanoTime() > deadline) {
        break
    }
}
findings.close()

// 3. The report, written as Markdown and read back through the Markdown parser's table extraction.
def done = tasks.all(Finding).toSorted { it.topic() }     // all() is unmodifiable: toSorted, never sort in place
report.text = "# Research report\n\n| topic | summary | worker |\n|---|---|---|\n" +
        done.collect { "| ${it.topic()} | ${it.summary()} | ${it.worker()} |" }.join('\n') + '\n'
def document = new MarkdownSlurper().enableTables(true).parse(report)
println "report ${report.name}: ${document.tables.size()} table(s), ${done.size()} finding(s), ${pending.size()} topic(s) still pending"
