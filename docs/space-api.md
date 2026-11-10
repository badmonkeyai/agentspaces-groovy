# The Space API, in Groovy

Written for: Groovy developers who build with agentspaces-groovy.

```groovy
import static ai.badmonkey.agentspaces.groovy.Where.*

space << entry                                        // write under the space's default lease (10m; space.defaultLease = '2h')
space.write(entry, '10m')                             // or lease: '10m', tags: [region: 'eu']
space.read(Task, where: [priority: gte(2), topic: anyOf('a', 'b')], tags: [region: 'eu'])
space.read(Task) { priority > 2 }                     // a filter over the decoded entry, judged on this replica
space.all(Task, limit: 50)                            // an unmodifiable List: GINQ-ready; toSorted, never sort in place
space.entries(Task, tags: [shard: present()])         // with id, issuer, tags, lease, stamp
space.take(Task, lease: '10m', timeout: '2s')         // Optional<TakenEntry<Task>>
space.take(Task, lease: '10m') { Task t -> new Quote(...) }   // take, run, complete with the result; null completes without one; a throw lets the lease lapse
space.use(Task, lease: '10m') { TakenEntry<Task> taken -> ... }  // take, run, complete without a result
space.notify(Task, lease: '1h', on: 'written') { SpaceEvent e -> ... }   // Subscription
votes.propose(id: 'm-1', question: 'ship?', options: ['yes', 'no'], quorum: 2); votes.cast('m-1', 'yes')
aggregate.settled('epoch', ticks: 20, tolerance: 0.01)
content.publish(file, name: 'weights.bin', pieceSize: '1m')          // group.content: the content swarm (content { enabled } in the fleet DSL)
content.fetch(root, timeout: '10m') { Fetch f -> f.bytes }             // from every holder at once; f.text, f.progress(), f.trace()
space << bigEntry                                                     // above content.escalateAbove (4 MiB) the entry travels as an artifact
def facts = pdt().ref('facts', PMap)                                 // pdt { enabled } beside content { enabled }: a persistent map ref
facts.swap { it + [total: 3, owner: 'ana'] }                          // a new version every member merges; facts.value.total, facts.value['owner']
pdt().pin(facts.value.root(), copies: 2, lease: '30d')               // the group's content stores hold the root; pdt().status(root).copies
```

`group.content` is the group's content swarm (SPEC v0.1.23 §8,
`srad/large-networks-bulk-data.md`) once `content { enabled }` is in the
fleet block (with `pieceSize`, `escalateAbove`, `store { backends 'file';
path '...' }` and the other `agentspaces.content.*` keywords): `publish`
takes bytes, a `File`, a `Path` or a path string with `name:`, `mediaType:`,
`pieceSize:`, `lease:`, `attrs:`, `inPlace:` (a size is `'1m'`, `'256k'`, or a
number of bytes here; the Clojure binding spells the same size `"1MiB"`);
`fetch` with a closure waits
up to `timeout:` and hands the closure the completed `Fetch`, whose `bytes`
and `text` read the artifact; a `Fetch` also takes `await('2m')`. In a
script, `content()` is the one joined group's swarm.

`pdt()` is the group's persistent trees (`srad/agentspaces-pdt.md` PD-C-07)
once `pdt { enabled }` is in the fleet DSL beside `content { enabled }`: the
`Pdt` facade of the starter, whose `ref('facts', PMap)` is a handle every
member merges. A `PMap` reads property-style (`facts.value.total`,
`facts.value['total']`, `'total' in facts.value`) and grows with `+` and
shrinks with `-` as a new value; a `PVector` indexes with `[]`; a `Ref` derefs
as `ref.value`, swaps with a closure (`ref.swap { it.assoc('total', 1) }`),
and lists its earlier versions as `ref.history`. A group without the trees
names `agentspaces.pdt.enabled`. The starter's map-valued keys bind by name:
`pdt { refs { facts { mode 'linear' } } }` or `pdt(refs: [facts: [mode: 'linear']])`
for `agentspaces.pdt.refs.facts.mode`, and `pdt { spaces { tasks { escalateAbove 65536 } } }`
for `agentspaces.pdt.spaces.tasks.escalate-above`.

`pdt.pin(root, copies: 2, lease: '30d')` asks the group's content stores to
hold a root (`srad/agentspaces-pdt2.md` P2-C-04): the stores' cards choose
where, this peer signs the pin, and pinning the root again renews it.
`tier:`, `maxPrice:` and `avoid:` narrow the choice, and `pdt.pin(root)`
asks for one copy for 30 days. `pdt.status(root)` answers `[root:, copies:,
holders: [[store:, bytes:, until:, blind:]], unplaced:]`. A peer becomes a
store with `store { enabled; name 'site-a'; capacity '2TiB' }` in the fleet
DSL, the starter's `agentspaces.store.*`.

`where:` takes a plain value (`eq`), a matcher function (`eq`, `ne`, `gt`,
`gte`, `lt`, `lte`, `anyOf` for the Java `in`, `contains`, `isNull`, `notNull`,
`present` on tags), or a closure over the field. The filter closure of
`read`, `all`, `entries`, and `notify` runs after the template matched, on
this replica only; a take claim is judged by the fabric's template, so `take`
takes `where:` and `tags:` and no filter closure.

Numbers in filters compare by value whatever their boxed class: `gte(2)`
matches a `long` component and `eq(2)` a `double` component holding 2.0,
because the core's matchers compare numerically since 0.5.0
(`ExtensionsTest.numericBoundsMatchByValueWhateverTheFieldsBoxedClass`).

Every record class gains `from(map)`: `Task.from([topic: 'x', priority: 3])`
builds the record through its canonical constructor by component name, with
numeric widening and string-to-enum coercion, and refuses an unknown key or a
missing primitive component by name; `record.toMap()` is the way back. The
method is an extension on `Class<T>`, the way Groovy's own `Foo.newInstance()`
is, so it works dynamically and under `@CompileStatic`, and a class that has
its own static `from` keeps it.
