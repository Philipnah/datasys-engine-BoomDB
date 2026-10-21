# Run the partition-size experiment

## Summary

Run the experiment in `docs/experiment-design.md`: one reproducible dataset of 1,000,000 rows, partition sizes `2¹` through `2¹⁸`, and five identical SELECTs per size—90 measurements total.

Retrieve ID **500000** throughout. Measure binding, planning, execution, and operator cleanup using the engine’s own `durationMs` log entries. Plot median latency against partition size, with minimum–maximum spread.

## Prepare the engine and automation

- Add the JVM property `boomdb.maxRowsPerPartition` to the CLI’s storage-engine construction. Preserve the existing default when absent; reject malformed or nonpositive values.
- Add SELECT summaries in `Executor`. Start timing immediately before binding and stop after operator cleanup. Log `durationMs` with fractional milliseconds, table, rows returned, and partition statistics. Exclude script parsing, JVM startup, ingestion, CSV output, and writing the summary itself.
- Retain normal DEBUG logging, including partition decisions. Failed SELECTs must produce ERROR records and no successful timing sample.
- Add a Python experiment script with dataset generation, smoke-run, full-run, and chart-generation commands. Use the standard library for generation, orchestration, log extraction, and statistics; use Matplotlib for plotting.
- Update the design’s query to valid SQL and document the agreed cache interpretation:
  ```sql
  SELECT * FROM experiment WHERE id = 500000;
  ```

## Generate data and run the sweep

1. **Validate and package.** Run focused tests, then `mvn verify` and `mvn package`. Record the source commit, any uncommitted patch, and the packaged JAR’s checksum. Perform no Git state-changing operations.

2. **Record conditions.** Capture machine model, CPU, RAM, OS, Python version, build JVM, launcher JVM, and logging configuration. Fix `ENGINE_JAVA_OPTS` to `-Xms2g -Xmx2g` for every engine invocation, adding the partition-size property as needed. Check resource availability before starting; stop and report if this configuration is infeasible.

3. **Generate one headerless CSV.** Use `random.Random(67)`. For each ID from 1 through 1,000,000, draw a name from `Slava`, `Vasya`, `Anton`, and `Taras`, then draw `random_double` using `rng.random()`. Write IDs in increasing order using columns `id LONG, name STRING, random_double DOUBLE`. Record the CSV checksum and the expected row for ID 500000.

4. **Smoke-test separately.** Use 1,024 rows, target ID 512, partition sizes 2, 128, and 1,024, and five SELECTs per size. Validate the launcher, configuration, partition counts, returned rows, timing extraction, and chart generation. Keep these results separate from the experiment.

5. **Run sizes in ascending order.** Use a new experiment directory under `/private/tmp`, outside OneDrive. Each partition size gets its own working directory, database, and logs. Invoke the repository’s absolute `engine.sh` path from that directory:
   - Execute CREATE and COPY in a setup invocation, using the shared CSV’s absolute path.
   - Verify ingestion produced 1,000,000 rows and `ceil(1,000,000 / partitionSize)` partitions.
   - Start one fresh JVM with a script containing five identical SELECTs. Do not add unmeasured warm-up queries.
   - Capture stdout and stderr, retain all rotated logs, and validate results before advancing.

6. **Accept samples strictly.** Require five expected result rows and exactly one successful SELECT timing summary for each statement number 1–5 in the measurement session. Each query must return one row, read one partition, and prune all remaining partitions. On missing records, incorrect output, or failure, stop and preserve diagnostic artifacts; do not silently replace samples.

## Analyze and deliver

- Produce a raw-results CSV containing partition size, repetition, session ID, statement number, `durationMs`, row count, and partition statistics.
- Produce a summary CSV with five samples per size, median, minimum, and maximum.
- Export PNG and PDF charts: base-2 logarithmic x-axis labeled **Maximum rows per partition (rows)**; linear y-axis labeled **SELECT duration (ms)**. Show the median curve and minimum–maximum error bars.
- Caption the chart with dataset size, seed, target ID, five-query protocol, timing boundary, and retained DEBUG logging.
- Preserve the generator, runner, SQL scripts, conditions manifest, checksums, raw logs, results, and charts. Add the required AI usage log after implementation and validation. Leave the report’s interpretation to the contributors.

## Tests and interpretation limits

- Test default/custom/invalid partition-size configuration, successful and failing timing records, log rotation handling, missing or duplicate samples, incorrect query output, and deterministic generation.
- Five queries share one JVM per partition size. Label repetition 1 **first query** and repetitions 2–5 **subsequent queries**; filesystem-cache state is uncontrolled, and steady-state JVM warmth is not guaranteed.
- Keep the first measurement in the five-sample median, as specified.
- Timing includes catalog loading, pruning, and diagnostic logging. At size 2, this includes processing 500,000 partition decisions per query.
- Evaluate the valley hypothesis from the measurements without forcing a curve shape or assuming it will be confirmed.
