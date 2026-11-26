# DataSystems-2026
The "How to Build Data Systems" course running at ITU autumn 2026.

### Join planning

Join order is optimized by default. To force joins in the order listed in `FROM`,
use syntax mode: `./engine.sh -j syntax -f script.sql`. The `-j optimized` or
`-j syntax` option can appear before or after the `-c` or `-f` pair.

### Exercise 12 join-order benchmark

Build BoomDB and run the benchmark from the repository root with Java 25 or
later, Maven, and `uv`. The runner uses Python 3.12 and Matplotlib for the chart.

```sh
mvn -B verify
uv run --no-project --python 3.12 benchmarks/join_order.py
```

The runner generates 1,000 customers across 100 cities and 100,000 rows per fact
table with seed 12. It loads those tables once, warms the filesystem cache with
one untimed `min` query, then interleaves five measured runs of each configuration.
Every execution uses a fresh JVM with `-Xms1g -Xmx2g`. The metric is the `Executor`
statement summary's `durationMs`, which covers binding, planning, and execution
through operator close. JVM startup, SQL parsing, and CSV printing are outside
that timer.

`min` and `max` force their corresponding `FROM` lists in syntax mode.
`optimized-min` and `optimized-max` use the same two lists in optimized mode.
All 20 executions must return the same 100,000 rows after normalizing column
and row order.

Each run prints its own directory under `target/join-order-benchmark/`.

- `runtime.png` plots each configuration's median with whiskers from its minimum
  to its maximum. `runtime-summary.csv` contains those values and `timings.csv`
  records individual runtimes and chosen join orders.
- `join-sizes.csv` gives estimated and actual output rows by configuration and
  join step, along with the physical build and probe sides.
- `analysis.sql` loads `engine-log.csv` and `runs.csv` into BoomDB and joins them
  by session ID. `labeled-joins.csv` preserves the results used for the size table.
- `conditions.txt` records the machine, JVM, heap flags, and Git HEAD. Keep the
  log snapshot with the results so the planner's `JOIN_CANDIDATE` entries and
  the load's partition statistics are available for the worked example.

For report measurements, build and run from the commit you plan to cite.
