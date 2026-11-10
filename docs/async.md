# Async

Written for: Groovy developers who build with agentspaces-groovy.

Every blocking verb and client wait has a twin returning a
`CompletableFuture` on a virtual thread, which Groovy 6's `await` takes
unchanged. `await` binds looser than member access: write `(await x).size()`
or assign first.

```groovy
Optional<Task> first = await space.readAsync(Task, where: [topic: 'x'])
def (quote, risk) = await(quotes.takeAsync(Quote, lease: '1m'), risk.readAsync(RiskScore, timeout: '5s'))
def decision = await votes.decisionAsync('m-1')
def estimate = await aggregate.settledAsync('epoch', ticks: 20, tolerance: 0.01)

AsyncScope.withScope { scope ->                       // inside a bound method: the binder runs it on a virtual thread
    def q = scope.async { await quotes.takeAsync(Quote, lease: '1m', timeout: '5s') }
    def r = scope.async { await risk.readAsync(RiskScore, timeout: '5s') }
    new Order((await q).get(), (await r).get())
}
```

A cancelled twin's blocking call still runs to its own timeout (the binder
renews the take lease meanwhile), so keep fan-out timeouts short.

With `agentspaces-groovy-concurrent`:

```groovy
for await (e in space.channel(Finding, lease: '1h', buffer: 64, on: 'written')) { println e.entry().summary() }
def fan = space.broadcast(Task); def a = fan.subscribe(); def b = fan.subscribe()
def first = Selects.firstOf('2s', left.channel(Task), right.channel(Task)).join()   // first.index, first.value, first.timeout
def decided = votes.decisionVar('m-1')                 // a DataflowVariable bound when the decision lands
```

A channel is fed by one subscription; closing either side closes both, and a
channel closes itself when its lease would lapse unless `renew`ed. A full
buffer blocks the subscription's listener: a slow consumer slows this peer's
event delivery and leaves the fabric alone.
