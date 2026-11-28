# DataSystems-2026
The "How to Build Data Systems" course running at ITU autumn 2026.

### Join planning

Join order is optimized by default. To force joins in the order listed in `FROM`,
use syntax mode: `./engine.sh -j syntax -f script.sql`. The `-j optimized` or
`-j syntax` option can appear before or after the `-c` or `-f` pair.

### Exercise 12 join benchmark

Build BoomDB and run the benchmark from the repository root with Java 25 or
later, Maven, and `uv`. The runner uses Python 3.12 and Matplotlib for the chart.

```sh
mvn -B verify
uv run --no-project --python 3.12 benchmarks/join_order.py
```

By default the runner generates 1,000 customers across 100 cities and 100,000 rows per fact
table with seed 12. It loads those tables once, warms the filesystem cache with
one untimed `min` query, then interleaves five measured runs of each configuration.
Every execution uses a fresh JVM with `-Xms1g -Xmx2g`. The metric is the `Executor`
statement summary's `durationMs`, which covers binding, planning, and execution
through operator close. JVM startup, SQL parsing, and CSV printing are outside
that timer.

`min` and `max` force their corresponding `FROM` lists in syntax mode.
`optimized-min` and `optimized-max` use the same two lists in optimized mode.
At the default scale all 20 executions must return the same 100,000 rows after
normalizing column and row order. The runner derives the expected row counts
from the selected workload for other scales.

To reproduce the Week 6 procedure of five SELECTs in one fresh JVM per
configuration, including the first query in the median, run

```sh
uv run --no-project --python 3.12 benchmarks/join_order.py --schedule shared-jvm --warmups 0 --repetitions 5
```

| Options | Effect |
| --- | --- |
| `--customers`, `--cities`, `--city` | Set the dimension size, number of round-robin cities, and zero-based city to select. By default the query uses city 42, or the last city for smaller city counts. |
| `--orders-per-customer`, `--shipments-per-customer`, `--seed` | Set each fact table's multiplicity independently and the seed for the two foreign-key shuffles. |
| `--configurations` | Supply one or more space-separated configuration names in the desired starting order. |
| `--repetitions`, `--warmups` | Set measured executions per configuration and untimed `min` queries before the experiment. Each warm-up uses a separate JVM. With zero warm-ups, filesystem cache state depends on the OS and prior activity. |
| `--schedule` | `rotating` changes the starting configuration each round. `fixed` repeats the same round order. `grouped` completes all repetitions of each configuration in fresh JVMs. `shared-jvm` groups them in a single SQL script per configuration. |
| `--initial-heap-mb`, `--max-heap-mb` | Set the JVM's initial and maximum heap in MiB. |

Use `--help` for defaults. For the Exercise 12 report, include all four
configurations and at least five measurements per configuration. In
`shared-jvm` mode BoomDB retains the output of all SELECTs in the script
until it prints them, so its heap requirement can grow with repetitions.

Each run prints its own directory under `target/join-order-benchmark/`.

- `runtime.png` shows three colored bars per configuration for the minimum,
  median, and maximum runtime. `runtime-summary.csv` contains those values and
  `timings.csv` records individual runtimes and chosen join orders.
- `join-sizes.csv` gives estimated and actual output rows by configuration and
  join step, along with the physical build and probe sides.
- `analysis.sql` loads `engine-log.csv` and `runs.csv` into BoomDB and joins them
  by session ID. `labeled-joins.csv` preserves the results used for the size table.
- `conditions.txt` records the effective parameters, replay command, machine, JVM, heap flags, and Git HEAD. Keep the
  log snapshot with the results so the planner's `JOIN_CANDIDATE` entries and
  the load's partition statistics are available for the worked example.

For report measurements, build and run from the commit you plan to cite.
