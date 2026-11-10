# agentspaces-groovy

**AgentSpaces for Apache Groovy 6**, from Bad Monkey AI. A Groovy script is a whole peer of a peer-to-peer fleet: a `fleet { }` block joins the group, an `agent { }` block takes work under a lease, and the Java binder runs every verb and transport underneath.

## Quick start: build your first agent with Groovy

An agent is a method. Its parameter is the work it takes, and its return value is the next entry in the space. In Groovy the agent is one closure per verb, and a single script is a whole peer: `fleet { }` configures it, `agent(...)` describes the worker, `bind` starts it, and `serve()` keeps it running.

**1. Install Groovy 6.** Groovy 6.0.0 on JDK 21 or newer. `@Grab` fetches `agentspaces-groovy` from Maven Central at run time, so the script needs no build file.

**2. Write the agent.** The two records live in the script under a package, so they carry the wire names `shop.Order#v1` and `shop.Shipment#v1`. The `fleet { }` block uses the same keys as the Spring starter's YAML. The default `mtls` security profile secures every link, so the block has no security entry.

```groovy
package shop

@Grab('ai.badmonkey.agentspaces:agentspaces-groovy:0.6.0')
import ai.badmonkey.agentspaces.groovy.FleetScript
@groovy.transform.BaseScript FleetScript _

record Order(String orderId, String item) { }
record Shipment(String orderId, String item, String by) { }

int port = args ? args[0] as int : 7701
String seed = args.size() > 1 ? args[1] : ''

def runtime = fleet {
    bind "127.0.0.1:$port"
    group('shop') {
        founding 'shop-v1'
        if (seed) { seeds seed }
        space('orders') { }
    }
}

bind agent('fulfiller', description: 'Ships orders') {
    take(Order, space: 'orders', lease: '30s', returns: Shipment) { Order order ->
        new Shipment(order.orderId(), order.item(), 'fulfiller')
    }
}

if (args.size() > 2) {                                   // groovy fulfiller.groovy 7702 127.0.0.1:7701 kite
    def orders = runtime.context().group().space('orders')
    orders << new Order('o-1', args[2])
    println orders.read(Shipment, timeout: '30s').get()
} else {
    serve()
}
```

**3. Run two instances.** The first founds the group on port 7701. The second takes its own port, the first instance as its seed, and an item to order.

```
groovy fulfiller.groovy
groovy fulfiller.groovy 7702 127.0.0.1:7701 kite
```

The second instance prints the shipment. Either instance can take the order, because both run the same agent.

**4. Kill one.** Add `println "shipping ${order.orderId()}"` and `Thread.sleep(10000)` at the top of the closure. Start both instances, write an order from the second, and run `kill -9` on whichever instance prints `shipping`. If you killed the first instance, the second prints the shipment. If you killed the second, the first prints `shipping o-1` again when the 30 second lease lapses.

The [documents](docs/README.md) add the rest: the one-file peer in detail, the class form with compile-time checking, the Space API in Groovy, async verbs for Groovy 6's `await`, and a shell over a running peer.

## What it is

- One-file peers with `@Grab`, and the same agents as compiled classes.
- A fleet DSL whose keys are the Spring starter's properties, and an agent DSL with one closure per verb.
- GDK-style extensions on the Space API, coercions, and the `where:` template DSL.
- `await` and channels over space subscriptions in `agentspaces-groovy-concurrent`.
- Groovy 6.0.0 on JDK 21 or newer; Spring Boot and Micronaut hosts.

## Add it to your build

A script needs no build file: `@Grab('ai.badmonkey.agentspaces:agentspaces-groovy:0.6.0')` fetches it from Maven Central. A Maven or Gradle project depends on `ai.badmonkey.agentspaces:agentspaces-groovy:0.6.0` and `org.apache.groovy:groovy:6.0.0`. [About agentspaces-groovy](docs/about.md) lists both artifacts, and [Building](docs/building.md) builds from a clone.

## Where to read next

1. **[The developer guide](https://www.badmonkey.ai/agentspaces/developer-guide.html#gw-groovy)**: its Groovy section maps this library onto the Java chapters.
2. **[The Groovy guide](https://www.badmonkey.ai/agentspaces/groovy-guide.html)**: a newcomer's walk from a first agent to the fleet DSL, await, and the hosts.
3. **[The examples](examples/)**: example 21 runs a Groovy worker beside Java peers.
4. **[The documents](docs/README.md)**: everything this README leaves out, one topic per document, each with the reader it is written for.

## License

Apache License 2.0, Copyright 2026 Bad Monkey, Inc. See [LICENSE](LICENSE) and [NOTICE](NOTICE). Contributions are welcome under the Developer Certificate of Origin; see [CONTRIBUTING.md](CONTRIBUTING.md). Report a vulnerability privately, as [SECURITY.md](SECURITY.md) describes.
