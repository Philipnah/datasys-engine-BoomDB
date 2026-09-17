# Exercise 4 Volcano Pipeline and SQL Front Door Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Execute the Exercise 3 AST through a Volcano `Scan`/`Filter` pipeline, move partition pruning into a planner, and expose script and single-statement execution through a CSV-only SQL command line.

**Architecture:** Keep storage metadata and partition I/O in `StorageEngine`, but expose narrow package-private seams so `Planner` can choose partitions and `ScanOperator` can read only that chosen list. `Executor` owns the parse/bind/plan/execute loop and statement-number MDC lifecycle; `Engine` only selects the input mode, creates the default `data/` database, buffers successful results, and renders headerless CSV.

**Tech Stack:** Java 25, Maven 3.9+, ANTLR 4.13.2, JUnit 6.1.2, SLF4J 2.0.18 with Log4j2 2.26.1, Jackson 2.22.2.

**Spec:** `exercise-descriptions/Exercise4.md`

## Global Constraints

- Preserve the exact Week 2 `StorageEngine.select(String, String, Comparison, Object)` signature and leave the Exercise 2 integration assertions unchanged.
- Implement the exact `Operator.open()` / `next()` / `close()` protocol; `next()` returns `null` only at exhaustion.
- `ScanOperator` receives a fixed partition list, preserves partition and row order, and never receives or evaluates a predicate.
- `Planner` performs min/max pruning and emits every `decision=READ|PRUNED` line before an operator opens a data file.
- `FilterOperator.close()` logs `rowsIn` and `rowsOut` and closes its child.
- SQL execution stops at the first parse, bind, plan, or execution error.
- Standard output contains only no-argument help or successful headerless CSV rows; logs and errors remain on standard error.
- `statementNumber` is 1-based within one script and is restored to `0` after success or failure.
- Default persistent storage is `data/` below the working directory, and `data/` is ignored by Git.
- Do not implement the optional column-list grammar or `ProjectOperator`.
- Apply the repository `java-docs` skill to changed public/protected Java APIs and `ai-usage-log` once after implementation and validation.
- Do not create the `v0.4` tag until the implementation PR is reviewed, green, and merged to `main`.

---

### Task 1: Volcano operators and the narrow storage read seam

**Files:**
- Create: `src/main/java/dk/itu/boomdb/Operator.java`
- Create: `src/main/java/dk/itu/boomdb/ScanOperator.java`
- Create: `src/main/java/dk/itu/boomdb/FilterOperator.java`
- Modify: `src/main/java/dk/itu/boomdb/StorageEngine.java`
- Create: `src/test/java/dk/itu/boomdb/TestListOperator.java`
- Create: `src/test/java/dk/itu/boomdb/FilterOperatorTest.java`
- Create: `src/test/java/dk/itu/boomdb/ScanOperatorTest.java`

**Interfaces:**
- Consumes: existing `TableCatalog`, `PartitionMetadata`, `ColumnSpec`, `Predicate`, and `StorageSupport.matches(...)`.
- Produces: the exact public `Operator` protocol; package-visible constructors for `ScanOperator(StorageEngine, TableCatalog, List<PartitionMetadata>)` and `FilterOperator(Operator, Predicate, List<ColumnSpec>)`; package-private `Operator FilterOperator.child()` and `List<PartitionMetadata> ScanOperator.partitions()` inspection methods for shape tests.
- Produces from storage: package-private `TableCatalog StorageEngine.table(String)` and `List<Object[]> StorageEngine.readPartition(TableCatalog, PartitionMetadata)`.

- [ ] **Step 1: Write the failing filter tests and list-backed test operator**

Create `TestListOperator` as a minimal test double with an index, `opened`/`closed` flags, and a supplied `List<Object[]>`. In `FilterOperatorTest`, use the trips schema and this input:

```java
List<Object[]> input = List.of(
        new Object[] {"Copenhagen", 12L, 23.5},
        new Object[] {"Aarhus", 187L, 301.0},
        new Object[] {"Odense", 95L, 120.75});
Predicate predicate = new Predicate("distance", Comparison.GREATER_THAN, 100L);
```

Open the filter, drain it with repeated `next()`, close it, and assert that only the Aarhus row is returned and the child was both opened and closed. Add a second test whose predicate matches no rows and assert immediate exhaustion after the input is consumed.

- [ ] **Step 2: Write failing scan tests for selected and empty partition lists**

Under `@TempDir`, create/copy the golden trips table with `maxRowsPerPartition = 2`, obtain its `TableCatalog`, and construct a scan with partitions 1 and 3. Assert that it returns exactly the four rows in those two partitions, in the handed-in order. Construct another scan with `List.of()` and assert `next()` returns `null` without reading a file.

- [ ] **Step 3: Verify the operator tests fail for missing types**

Run: `mvn -Dtest=FilterOperatorTest,ScanOperatorTest test`

Expected: test compilation fails because `Operator`, `FilterOperator`, and `ScanOperator` do not exist.

- [ ] **Step 4: Add the exact Volcano interface**

Implement:

```java
public interface Operator {
    void open();
    Object[] next();
    void close();
}
```

Document that callers must use `open` before pulling, `next` returns rows in schema order, and `close` releases child/operator state.

- [ ] **Step 5: Expose only the storage operations needed by planning and scanning**

Keep `CatalogStore`, path validation, and checked-I/O conversion inside `StorageEngine`:

```java
TableCatalog table(String tableName) {
    return requireTable(tableName);
}

List<Object[]> readPartition(TableCatalog table, PartitionMetadata partition) {
    try {
        return StorageSupport.readPartition(
                partitionPath(partition.fileName()), table.columns());
    } catch (IOException error) {
        throw new UncheckedIOException(
                "cannot read partition " + partition.fileName(), error);
    }
}
```

Do not expose `CatalogStore`, the partition directory, or raw filesystem paths to the new operators.

- [ ] **Step 6: Implement the scan as a plain ordered reader**

Copy the constructor's partition list defensively. `open()` resets the partition index and current row iterator. `next()` advances through the current partition, loading the next handed-in partition through `storage.readPartition(...)` only when needed; it returns `null` when the list is exhausted. `close()` clears the iterator. Do not add pruning, predicate, or statistics logic.

- [ ] **Step 7: Implement the filter as a pull loop**

At construction, resolve the predicate column once from the supplied schema and retain its index and `ColumnType`. `open()` resets both counters and opens the child. `next()` repeatedly pulls from the child, increments `rowsIn` for every received row, applies `StorageSupport.matches(...)`, and increments `rowsOut` only when returning a matching row. `close()` closes the child in a `finally` block and logs one line containing both counters:

```java
LOGGER.debug("rowsIn={} rowsOut={}", rowsIn, rowsOut);
```

- [ ] **Step 8: Verify both operator contracts**

Run: `mvn -Dtest=FilterOperatorTest,ScanOperatorTest test`

Expected: both test classes pass, including the empty-partition scan and child-close assertion.

- [ ] **Step 9: Commit the independently working operator layer**

```bash
git add src/main/java/dk/itu/boomdb/Operator.java \
  src/main/java/dk/itu/boomdb/ScanOperator.java \
  src/main/java/dk/itu/boomdb/FilterOperator.java \
  src/main/java/dk/itu/boomdb/StorageEngine.java \
  src/test/java/dk/itu/boomdb/TestListOperator.java \
  src/test/java/dk/itu/boomdb/FilterOperatorTest.java \
  src/test/java/dk/itu/boomdb/ScanOperatorTest.java
git commit -m "feat: add volcano scan and filter operators"
```

### Task 2: Planner pruning and Week 2 `select` compatibility

**Files:**
- Create: `src/main/java/dk/itu/boomdb/Planner.java`
- Modify: `src/main/java/dk/itu/boomdb/StorageEngine.java`
- Modify: `src/main/java/dk/itu/boomdb/StorageSupport.java`
- Create: `src/test/java/dk/itu/boomdb/PlannerTest.java`
- Verify unchanged: `src/test/java/dk/itu/boomdb/StorageEngineIT.java`

**Interfaces:**
- Consumes: bound `SelectStatement`, storage metadata, `StorageSupport.shouldPrune(...)`, and Task 1 operators.
- Produces: `public Operator Planner.plan(SelectStatement)`; package-private `void StorageEngine.recordScanStats(ScanStats)`; filtered plans shaped as `FilterOperator(ScanOperator(...), ...)` and unfiltered plans shaped as bare `ScanOperator`.

- [ ] **Step 1: Write failing planner-shape and pruning tests**

Create sorted golden data in an `@TempDir` and use a `StorageEngine(directory, 2)`. After create/copy, bind and plan:

```java
SelectStatement filtered = new SelectStatement("trips", Optional.of(
        new Predicate("distance", Comparison.GREATER_THAN, 200L)));
Operator filteredPlan = planner.plan(filtered);
```

Assert `filteredPlan instanceof FilterOperator`, its `child()` is a `ScanOperator`, and `lastScanStats()` is `new ScanStats(4, 1, 3)`. For `new SelectStatement("trips", Optional.empty())`, assert the result is directly a `ScanOperator`, its partition list contains all four catalog partitions, and stats are `new ScanStats(4, 4, 0)`.

- [ ] **Step 2: Add a planning-before-I/O log assertion**

Use a unique table name, call `planner.plan(filtered)` without opening the returned operator, then read `logs/engine.log`. For the unique table's decision lines, assert there are four, each has `Planner` in the CSV `className` field, and the decisions contain three `PRUNED` values and one `READ` value. This establishes that planning logs decisions before scan I/O starts.

- [ ] **Step 3: Verify planner tests fail before implementation**

Run: `mvn -Dtest=PlannerTest test`

Expected: test compilation fails because `Planner` and the plan inspection methods are missing.

- [ ] **Step 4: Centralize catalog-statistic decoding in storage support**

Move the existing `decodeStatistic(ColumnType, String)` switch from `StorageEngine` to package-private `StorageSupport.decodeStatistic(...)`. Keep the same conversions (`String`, `Long.valueOf`, `Double.valueOf`) so planner pruning is bit-for-bit compatible with Week 2.

- [ ] **Step 5: Implement planner partition selection and statistics**

`Planner` retains one non-null `StorageEngine`. In `plan(...)`, load the table and copy its partition list. For an unfiltered select, log one `table={} partition={} decision=READ` line per partition, mark all partitions read, and return a bare scan. For a filtered select, find the bound predicate column index once, decode that column's min/max for every partition, call the existing `shouldPrune`, log the original detail fields from Week 2 under `Planner`, and collect only non-pruned partitions.

Record exactly:

```java
new ScanStats(allPartitions.size(), surviving.size(),
        allPartitions.size() - surviving.size())
```

Then return:

```java
new FilterOperator(
        new ScanOperator(storage, table, surviving),
        predicate,
        table.columns());
```

- [ ] **Step 6: Refactor `StorageEngine.select` to plan and drain**

Keep the public method signature and existing SELECT summary/error logging. Convert its arguments to a `SelectStatement` with one `Predicate`, bind it, call `new Planner(this).plan(statement)`, then drain the root operator under `open`/`close`:

```java
operator.open();
try {
    for (Object[] row; (row = operator.next()) != null; ) {
        result.add(row);
    }
} finally {
    operator.close();
}
```

Remove the old pruning/read/match loop from `StorageEngine`; only `Planner` may emit `decision=` lines and only the operators may read/filter rows.

- [ ] **Step 7: Verify planner behavior and untouched Week 2 integration behavior**

Run: `mvn -Dtest=PlannerTest test && mvn -Dit.test=StorageEngineIT verify`

Expected: planner tests pass; every existing `StorageEngineIT` assertion passes unchanged, including all type/comparison cases, fully pruned results, restart behavior, and `ScanStats(4, 1, 3)`.

- [ ] **Step 8: Commit the planner refactor**

```bash
git add src/main/java/dk/itu/boomdb/Planner.java \
  src/main/java/dk/itu/boomdb/StorageEngine.java \
  src/main/java/dk/itu/boomdb/StorageSupport.java \
  src/test/java/dk/itu/boomdb/PlannerTest.java
git commit -m "feat: move partition pruning into planner"
```

### Task 3: Statement executor and MDC statement numbering

**Files:**
- Create: `src/main/java/dk/itu/boomdb/Executor.java`
- Create: `src/test/java/dk/itu/boomdb/ExecutorIT.java`

**Interfaces:**
- Consumes: `SqlParser`, `Binder`, `Planner`, `StorageEngine`, and operator trees from Tasks 1–2.
- Produces: `public Executor(StorageEngine)` and `public List<Object[]> execute(String sqlText)`; rows from all successful `SELECT` statements appear in script order, while `CREATE TABLE` and `COPY` produce no rows.

- [ ] **Step 1: Write a failing full-script executor test**

Create a temporary two-row CSV and a script containing `CREATE TABLE`, `COPY`, a filtered `SELECT`, and an unfiltered `SELECT`. Execute it once and assert the returned rows contain the filtered result followed by both unfiltered rows, preserving statement and storage order.

- [ ] **Step 2: Write failure-stop and MDC-reset tests**

Execute a three-statement script whose second statement references an unknown table. Assert the exception is propagated, the third statement did not create its table, and `MDC.get("statementNumber")` equals `"0"` afterwards. Add a malformed-script case and assert no earlier valid-looking statement ran because whole-script parsing failed at statement number 0.

- [ ] **Step 3: Write a log-based statement-number test**

Run a successful script using a unique table name. Read its matching log lines and assert CREATE activity has statement number `1`, COPY activity has `2`, planner/filter/SELECT activity has `3`, the parser's script summary has `0`, and the MDC is back at `0` after execution.

- [ ] **Step 4: Verify executor tests fail for the missing class**

Run: `mvn -Dit.test=ExecutorIT verify`

Expected: test compilation fails because `Executor` does not exist.

- [ ] **Step 5: Implement parse/bind/plan/execute in one ordered loop**

Parse the whole script while the MDC remains `0`. Create one binder and planner for the storage instance. For each parsed statement, increment a local counter before binding and call:

```java
MDC.put("statementNumber", String.valueOf(++statementNumber));
```

Dispatch exhaustively over the sealed statement hierarchy: create calls `storage.createTable`, copy calls `storage.copyFile`, and select plans then drains an operator into the result list. Do not catch per-statement failures, so the first error exits the loop naturally.

- [ ] **Step 6: Guarantee operator closing and MDC restoration**

Drain every select under `try/finally` so the root operator closes on normal exhaustion and runtime errors. Wrap the complete statement loop in an outer `try/finally` whose only cleanup is:

```java
MDC.put("statementNumber", "0");
```

Return an immutable copy of the accumulated row list only after all statements succeed. Skip the optional per-statement summary log; existing storage/planner/operator logs already provide required coverage.

- [ ] **Step 7: Verify executor order, stop-on-error, and MDC behavior**

Run: `mvn -Dit.test=ExecutorIT verify`

Expected: all executor integration tests pass and no test leaves a nonzero statement number in the calling thread.

- [ ] **Step 8: Commit the execution layer**

```bash
git add src/main/java/dk/itu/boomdb/Executor.java \
  src/test/java/dk/itu/boomdb/ExecutorIT.java
git commit -m "feat: execute sql scripts with statement context"
```

### Task 4: SQL command line and clean CSV/error streams

**Files:**
- Modify: `.gitignore`
- Modify: `src/main/java/dk/itu/boomdb/Engine.java`
- Modify: `src/test/java/dk/itu/boomdb/EngineIT.java`
- Preserve unchanged golden-query test: `EngineIT.selectsTheThreeGoldenQueryResults`

**Interfaces:**
- Consumes: `Executor.execute(String)` and the restored Week 1 team name `Team BoomDB`.
- Produces: no arguments print team/help; one argument executes one SQL string; `-f <path>` executes a UTF-8 script; package-private `int Engine.run(String[], Path, PrintStream, PrintStream)` permits isolated end-to-end tests while `main` supplies `Path.of("data")`, `System.out`, and `System.err`.

- [ ] **Step 1: Replace only the obsolete Exercise 3 demo assertion**

Keep the existing golden query test untouched. Add a no-argument test that calls `Engine.run` and expects exactly:

```text
Team BoomDB
Usage: boomdb '<SQL statement>' | boomdb -f <script.sql>
```

Assert return code `0`, empty stderr, and no database directory creation in this mode.

- [ ] **Step 2: Add successful one-argument and script-mode end-to-end tests**

Use `@TempDir` for both the injected database directory and CSV/script files. The one-argument case should execute a valid `CREATE TABLE` and produce empty stdout/stderr. The `-f` script should create/copy/select the golden rows and assert byte-for-byte UTF-8 output with `\n` line endings:

```text
Aarhus,187,301.0
Copenhagen,140,210.0
Aalborg,210,340.5
Esbjerg,299,450.25
```

- [ ] **Step 3: Add the failing-script stream-isolation test**

Use a script that successfully creates/copies/selects and then fails on an unknown table. Assert return code `1`, stdout is empty because results are printed only after the complete script succeeds, and stderr contains the unknown-table message. Also assert invalid argument shapes report usage on stderr without creating storage.

- [ ] **Step 4: Verify front-door tests fail against the Exercise 3 demo**

Run: `mvn -Dit.test=EngineIT verify`

Expected: the new tests fail because `Engine.run` and the argument modes do not exist.

- [ ] **Step 5: Implement argument selection without adding a CLI dependency**

Restore `String teamName()` returning `"Team BoomDB"`. In `run`, handle the modes before constructing storage:

```java
if (args.length == 0) {
    out.println(teamName());
    out.println(USAGE);
    return 0;
}
String sql = switch (args.length) {
    case 1 -> args[0];
    case 2 -> {
        if (!args[0].equals("-f")) {
            throw new IllegalArgumentException(USAGE);
        }
        yield Files.readString(Path.of(args[1]), StandardCharsets.UTF_8);
    }
    default -> throw new IllegalArgumentException(USAGE);
};
```

Catch input/execution failures once at the `run` boundary, print only the message to the supplied error stream, and return `1`. Do not call `System.exit`, which keeps direct JUnit invocation safe.

- [ ] **Step 6: Buffer success and render minimal headerless CSV**

Call `new Executor(new StorageEngine(dataDirectory)).execute(sql)` and print rows only after it returns successfully. Join each row's values with commas using `String.valueOf`; BoomDB's current COPY format cannot produce embedded comma/newline fields or nulls, so no new CSV library or escaping layer is needed for Exercise 4.

- [ ] **Step 7: Keep session and engine lifecycle at statement number zero**

In `main`, set a fresh random `sessionId` and `statementNumber=0`, log engine start, delegate to `run(args, Path.of("data"), System.out, System.err)`, restore `statementNumber=0` defensively in `finally`, and then log engine stop. Remove the obsolete SQL pretty-print demo and formatted table printer.

- [ ] **Step 8: Ignore the default runtime database**

Append exactly `data/` to `.gitignore`. Do not ignore arbitrary `.sql` or `.csv` files.

- [ ] **Step 9: Verify front-door behavior and Week 2 compatibility**

Run: `mvn -Dit.test=EngineIT,StorageEngineIT verify`

Expected: CLI output/error assertions pass, the golden query test remains green, and all unchanged storage integration tests still pass.

- [ ] **Step 10: Commit the SQL front door**

```bash
git add .gitignore src/main/java/dk/itu/boomdb/Engine.java \
  src/test/java/dk/itu/boomdb/EngineIT.java
git commit -m "feat: add sql script command line"
```

### Task 5: Full validation, learning log, review, and release handoff

**Files:**
- Create after validation: `docs/ai-usage/2026-09-17-exercise-4-volcano-sql-front-door.md`
- Inspect: all files changed in Tasks 1–4

**Interfaces:**
- Consumes: the complete Exercise 4 implementation and `docs/ai-usage/README.md`.
- Produces: one verified change set, one sanitized learning-log entry, and a reviewed/merged commit eligible for tag `v0.4`.

- [ ] **Step 1: Run the focused operator/planner unit suite**

Run: `mvn -Dtest=FilterOperatorTest,ScanOperatorTest,PlannerTest test`

Expected: all operator lifecycle, empty-scan, plan-shape, pruning-stat, and pre-I/O logging tests pass.

- [ ] **Step 2: Run every unit and integration check**

Run: `mvn verify`

Expected: Maven reports `BUILD SUCCESS`; the pre-change baseline was 75 passing tests (42 unit and 33 integration), and the final count is higher by the new Exercise 4 cases with zero failures/errors.

- [ ] **Step 3: Smoke-test the documented no-argument command**

Run: `mvn -q compile exec:java`

Expected stdout:

```text
Team BoomDB
Usage: boomdb '<SQL statement>' | boomdb -f <script.sql>
```

Expected stderr: console log lines only, including engine start/stop at statement number 0 in the CSV log.

- [ ] **Step 4: Inspect logging invariants**

Inspect `logs/engine.log` from the automated tests. Confirm filtered query decisions name `Planner` in `className`, `FilterOperator` emits `rowsIn`/`rowsOut`, statement-scoped lines use positive numbers, and parser/start/stop lines outside statements use `0`.

- [ ] **Step 5: Create the single sanitized AI learning log**

Invoke the repository `ai-usage-log` skill and record the request summary, final design choices, exact changed files, and only validation commands/results that actually ran. Keep secrets, raw prompts, chain-of-thought, and raw tool output out. Leave the ownership checkpoint unchecked for the contributor:

```markdown
## Ownership checkpoint

- [ ] I reviewed this change and can explain its design, correctness, and trade-offs.
```

- [ ] **Step 6: Re-run final repository checks after documentation**

Run: `mvn verify`

Run: `git diff --check`

Run: `git status --short`

Expected: verification succeeds, no whitespace errors appear, `target/`, `logs/`, and `data/` are absent from Git status, and only intentional source/test/documentation changes remain.

- [ ] **Step 7: Commit the learning log and final adjustments**

```bash
git add docs/ai-usage/2026-09-17-exercise-4-volcano-sql-front-door.md
git commit -m "docs: record exercise 4 ai usage"
```

- [ ] **Step 8: Open the PR and complete human review**

Link the learning-log entry in the PR description. Require green CI and at least one teammate review. The contributor checks the ownership box only after reviewing every generated line and being able to explain operator lifecycle, pruning correctness, error-stream isolation, and MDC restoration.

- [ ] **Step 9: Cut release `v0.4` only after the PR is merged**

Run from the normal local checkout, not an unmerged feature worktree:

```bash
git checkout main
git pull
git tag -a v0.4 -m "Exercise 4: Volcano pipeline and SQL front door"
git push origin v0.4
```

Expected: the pushed annotated tag points at the reviewed, CI-green merge commit on `main`.
