# Join optimizer design

BoomDB uses a greedy join optimizer for Exercise 11. It estimates the output
of each candidate equi-join from catalog statistics, chooses small intermediate
results first, and builds each hash table from the smaller estimated input.
`-j syntax` preserves Exercise 10's FROM-order plan for comparisons and forced
join orders. Optimization changes the plan, while SELECT * columns remain in
FROM-list order and duplicate rows retain their multiplicity.

## Statistics and collection

The catalog stores row count per partition and min, max, and number of distinct
values (NDV) per partition column. NDV is exact within each partition: a
`HashSet` counts typed LONG, DOUBLE, and STRING values when the shared
partition-writing path computes statistics. COPY creates these summaries, and
DELETE recomputes them for rewritten partitions. Unchanged partitions carry
their existing summaries into the new catalog version.

Every created NDV has a separate log line containing table, partition, column,
rows, and ndv, alongside the existing min/max logs. Collection is bounded by
partition size, but exact sets are unsuitable for very large partitions. At
billion-row scale we would consider mergeable HyperLogLog sketches, accepting
their estimation error instead of retaining every distinct value.

## Table NDV and merging

We approximate a column's table NDV with the maximum of its partition NDVs.
An empty table has NDV zero. The maximum is a lower bound on the true union's
NDV; the sum is an upper bound. Maximum merging is simple and works well when
partitions repeat similar domains, but can severely undercount disjoint domains
and consequently overestimate equality matches. This is our approximation,
not a claim of an exact merge.

The planner uses NDV across all partitions in the statement's pinned table
catalog, even when filters prune some partitions. It uses the same base-column
NDV throughout filtering and later joins. We do not estimate how filters or
previous joins change the distinct count. This deliberately coarse assumption
is one source of estimate error for the later report.

## Development catalog policy

This implementation requires development catalogs created with NDV. As agreed
for the current engine, we do not migrate old catalogs or support an old binary
reading the new format. Recreate development data with CREATE and COPY when
switching formats; do not edit live immutable catalog versions. Missing NDV in
a nonempty partition is invalid metadata, rather than evidence that the column
has zero distinct values. Empty tables still estimate zero rows and zero NDV.

A production rollout would need optional statistics, a documented fallback,
and a versioned backfill/downgrade procedure. Those are deferred while stored
development data is disposable. NDV is added to catalog JSON; the binary
partition layout does not change.

## Cardinality estimates

The estimator consists of small pure functions over catalog numbers. It reads
no data files and never refreshes catalogs during a statement.

- **Base table:** sum row counts of the partitions surviving min/max pruning.
- **Equality filter:** `filteredRows = inputRows / ndv(column)`.
- **Range filter:** for both `<` and `>`, `filteredRows = inputRows / 3`.
- **Equi-join:** `joinedRows = leftRows * rightRows / max(leftNdv, rightNdv)`.

Apply each local filter below the joins; multiply selectivities for AND.
Equality assumes uniform frequencies. The join formula additionally assumes
containment: values from the smaller distinct domain occur in the larger
domain. Multiplying filter selectivities assumes independence. Skew, correlated
columns, disjoint join domains, and coarse max merging can violate these
assumptions. Estimates influence plan selection, never which rows are returned.

Retain fractional double estimates through the whole chain instead of rounding
each stage or forcing an estimate to at least one row. Empty inputs estimate
zero; denominators are guarded to at least one. Reject negative or non-finite
estimate inputs. Sum base row counts as long values with overflow detection;
join calculations avoid overflowing a long product and saturate double
overflow at `Double.MAX_VALUE` so comparison and logging remain finite.

[Selinger et al., 1979, Table 1](https://web.eecs.umich.edu/~michjc/eecs584/Papers/selinger_1979.pdf)
describes uniform equality selectivity, the maximum-domain equality-join
denominator, multiplicative AND selectivity, and a one-third fallback for
open-ended comparisons. Applying one third to every supported range filter
is our simplification; we do not interpolate from min/max in this release.
The join formula and unchanged base-column NDV assumption also follow
Exercise 11 / lecture 10.

## Greedy ordering and graph restrictions

1. Build each table's pruned, filtered row estimate.
2. Evaluate every join predicate and select the pair with the smallest
   estimated output.
3. Among remaining tables connected to the joined set, evaluate each candidate
   extension and select the smallest estimated output. Repeat until complete.
4. At each physical join, build from the smaller estimated input. On equal
   estimates, build from the logical left/intermediate input.

Break ordering ties by original FROM positions, then predicate position.
Orient the initial logical pair in FROM order. Log every evaluated candidate
and the chosen order so the decision can be explained from the statistics.

Greedy smallest-intermediate-first is the course baseline and our chosen
alternative to dynamic programming or exhaustive enumeration. It is simpler,
but does not promise the globally cheapest plan.
[Leis et al., 2015](https://vldb.org/pvldb/vol9/p204-leis.pdf) motivates measuring
cardinality errors: inaccurate estimates can dominate plan quality even when
the search and cost model are more sophisticated.

Optimized mode accepts connected tree-shaped join graphs regardless of whether
the FROM prefix itself is connected. Disconnected graphs require a cross
product, which the engine does not implement, so they fail clearly. We retain
Exercise 10's restriction to one connecting predicate per added table: cycles
and parallel predicates are rejected explicitly, never silently discarded.
Syntax mode retains its original FROM-prefix validation and errors.

## Physical layouts, snapshots, and observability

HashJoinOperator emits build columns followed by probe columns. The planner
tracks that actual schema at each join and resolves the next key against it.
One final column permutation restores the original FROM schema when needed.
The permutation uses table identity and local column position, allowing
unreferenced duplicate column names without confusing their output positions.

Executor pins all table catalogs before binding or planning. Both estimates
and scans use those same objects, so concurrent COPY or DELETE affects a later
statement, not the current statement's rows or statistics.

The default is `-j optimized`. Under `-j syntax`, tables fold left to right
in FROM order and the intermediate always builds, exactly as in Exercise 10.
Both modes log chosen order, physical build/probe sides, and join estimates.
Each executed join logs estRows and actual rowsBuild, rowsProbe, and rowsOut
together when it closes. Join numbers identify logical steps independently of
close order; the existing sessionId and statementNumber identify the query.
Table lists use semicolons inside messages to preserve the existing seven-field
CSV log layout. GROUP BY and week 12 benchmarks remain outside this change.
