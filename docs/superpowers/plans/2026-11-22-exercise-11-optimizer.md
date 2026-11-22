# Exercise 11 Optimizer Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development with Terra 6.1 implementers and primary-agent review, as requested for execution. Steps use checkbox syntax for tracking. This document does not authorize release operations.

## Accepted Execution Amendments

- The user approved implementation with the proposed defaults on 2026-11-22.
- Old development catalogs need not be supported. Use required primitive `long ndv`, update fixture constructors, and omit migration, nullable statistics, and legacy fallback tests. Recreate development data after a catalog-format change.
- Pass physical build/probe table lists into the hash join's diagnostic constructor as well as the logical tables, so its one execution summary identifies both sides without inspecting child operator types.
- Reuse the current `feature/exercise-11` checkout. Leave changes for review; commits, PRs, merges, tags, and pushes are outside the implementation request.

**Goal:** Implement the Exercise 11 statistics and greedy join optimizer while preserving query results and the Exercise 10 plan under `-j syntax`.

**Architecture:** Extend existing partition statistics with required NDV, estimate cardinalities using catalog metadata, and choose a connected greedy join order. Keep the existing hash join's build-then-probe row layout, track that physical layout while planning, and restore FROM column order once at the root. Keep the syntax planner's original order and build-side policy.

**Tech Stack:** Existing Java 25+, Maven 3.9+, JUnit 6, Jackson, SLF4J/Log4j, and Java collections. No new dependencies or SQL grammar changes.

**Spec:** [Exercise 11](../../../exercise-descriptions/Exercise11.md), [optimizer design](../../optimizer-design.md), and [contributor guide](../../../AGENTS.md). Exercise 10 defines the syntax-mode baseline.

## Global Constraints

- Implement greedy smallest-intermediate-first, the selected heuristic; do not substitute dynamic programming or exhaustive enumeration.
- Collect exact partition NDV for LONG, DOUBLE, and STRING, including partitions rewritten by DELETE.
- Preserve filter pushdown, partition pruning, immutable catalogs, write-conflict handling, and statement-scoped snapshots.
- `-j optimized` is the default. `-j syntax` reproduces week 10's FROM-order fold with the intermediate building at every join.
- Preserve SELECT * column order and duplicate multiplicity. Result row order may differ; compare sorted rows without deduplication.
- Every executed join must expose estimated and actual output cardinality under its existing sessionId and statementNumber context.
- Keep the current rejection of unsupported additional connecting predicates. Reject disconnected graphs; do not introduce a cross-product operator.
- GROUP BY, aliases, histograms, HyperLogLog, a migration framework, and week 12 benchmarking are outside this implementation.
- Use java-docs when implementation changes Java APIs or non-obvious internals. Create one ai-usage-log entry after implementation and validation, following `docs/ai-usage/README.md`. A plan alone needs no entry.
- Run focused checks per task, then `mvn -B verify` before a PR. Keep generated output and runtime logs out of commits.

## Exercise 11 Requirements Check

| Requirement | Implementation task |
| --- | --- |
| Design with named sources, merge rule, compatibility, and guardrails | 1 |
| Exact partition NDV and creation logs; tested max merge | 2–3 |
| Pure base, filter, and equi-join estimators | 3 |
| Smallest initial join, then smallest connected extension | 4 |
| Smaller estimated input builds | 5 |
| Syntax baseline and FROM column order | 5–6 |
| Candidate logs, chosen order, estimates and actuals per join | 4–5 |
| Required JUnit and metamorphic tests | 2–7 |
| Reviewed CI-green PR and v0.11 release | 7–8 |

## Optimizer Design Check

The existing design selects exact partition statistics, max-NDV merging, the standard equality/join formulas, and greedy ordering. Those choices carry forward. Before coding, resolve the following gaps in that document:

- NDV is a proposed catalog field, not an already implemented one: `ColumnStatistics` currently stores only columnName, min, and max.
- Choose one range formula instead of leaving `|R| / 3 or interpolate` open.
- Define missing NDV separately from zero and specify mixed old/new partitions.
- Explain safe upgrade/downgrade behavior for immutable, versioned catalogs. Deleting fields from a live catalog is not a safe migration procedure.
- State the base-column NDV assumption for intermediate joins, filter independence, deterministic ties, and cross-product rejection.
- Replace the informal explanation of range selectivity with a named source. The max merge is a lower bound, not an accurate union count in general.

### Accepted implementation decisions

These resolve the initial design gaps with the approved defaults and the fresh-catalog execution amendment.

1. **Known NDV:** merge all partitions in the pinned table using their maximum; an empty list gives zero. Use whole-table NDV for filter and join estimates, even when pruning selects fewer partitions. This is deliberately coarse and consistent across the query.
2. **Required NDV:** store primitive `long ndv`. Every nonempty partition must have positive NDV no greater than its row count. Old catalogs are excluded; recreate development data rather than guessing missing statistics. Empty tables have effective NDV zero. Never read partition files to fill statistics during SELECT.
3. **Base and filters:** sum row counts of surviving partitions, then apply each pushed-down filter's selectivity. Equality divides by effective NDV; every `<` or `>` divides by three. Multiply selectivities for AND, assuming independence. Pruning can prove zero before filtering. Do not add interpolation in this release.
4. **Joins:** estimate `leftRows * rightRows / max(leftNdv, rightNdv)`. Carry the contributing base column's effective NDV unchanged through filters and joins, as the exercise permits. Retain fractional `double` estimates; do not round each stage to an integer or force positive estimates to at least one row.
5. **Numeric boundaries:** empty inputs give zero; denominators are at least one. Reject negative/non-finite cardinality inputs and negative NDV. Convert row counts before multiplying and cap overflow at `Double.MAX_VALUE` so comparisons and logs stay finite.
6. **Ties:** compare estimates first, then original FROM positions, then predicate position. Orient the initial logical pair in FROM order. On equal input estimates, build the logical left/intermediate input.
7. **Graph scope:** optimized mode accepts connected trees regardless of FROM prefix connectivity. Reject cycles/parallel predicates explicitly rather than dropping an equality. Syntax mode retains its existing validation and error messages.
8. **Compatibility:** require fresh catalogs with NDV, as authorized for development. COPY and rewritten DELETE partitions use the same statistics path. Do not add automatic backfill or edit leased catalog versions.
9. **Downgrade:** recreate disposable development data when changing catalog formats. Old-binary compatibility and production migration/downgrade tooling are deferred; switching binaries alone is not a compatibility guarantee.

**Research to cite in the design:** [Selinger et al., 1979, Table 1](https://web.eecs.umich.edu/~michjc/eecs584/Papers/selinger_1979.pdf) gives uniform equality selectivity, the maximum-domain equality-join denominator, multiplicative AND selectivity, and a fallback of one third for open-ended comparisons. Applying one third to every supported range filter is our simplification. Greedy selection follows Exercise 11 / lecture 10. [Leis et al., 2015](https://vldb.org/pvldb/vol9/p204-leis.pdf) motivates measuring estimate errors; it does not establish that greedy search is optimal. Max merging is our own approximation.

## Review Focus

1. Invalid partition NDV must be rejected; old catalogs are excluded by the accepted execution amendment. Test validation in Task 2.
2. Empty/pruned inputs, fractional estimates, and large products must produce valid estimates; test in Task 3.
3. Connected graphs with an unusable FROM prefix must optimize successfully, while disconnected or extra-predicate graphs fail clearly; test in Tasks 4–5.
4. Build-side swaps at multiple levels must preserve subsequent join keys, duplicate rows, and all FROM-ordered columns, including unreferenced name collisions; test in Task 5.
5. Concurrent COPY during planning must affect neither estimates nor rows for the pinned statement; join summaries must be uniquely attributable even if close order changes; test in Tasks 5 and 7.

## File Structure

Production files below are under `src/main/java/dk/itu/boomdb/`; tests are under `src/test/java/dk/itu/boomdb/`.

| File | Responsibility |
| --- | --- |
| Modify `CatalogStore.java` | Add required NDV to its existing ColumnStatistics record; keep persistence/versioning intact. |
| Modify `StorageEngine.java` | Collect and log NDV in the shared COPY/DELETE statistics path. |
| Create `CardinalityEstimator.java` | Small package-private pure functions for merge, effective NDV, and cardinalities. |
| Create `GreedyJoinOrder.java` | Select logical order and per-step estimates from catalog numbers; log evaluated candidates. |
| Create `JoinMode.java` | Public enum with OPTIMIZED and SYNTAX. |
| Modify `Planner.java` | Share table planning; select mode; track physical schemas, keys, and build sides. |
| Create `ColumnOrderOperator.java` | Package-private final output-column permutation. |
| Modify `HashJoinOperator.java` | Receive join diagnostics and report estimate plus actuals once. |
| Modify `ColumnResolver.java` | Clarify that offsets follow supplied schema order; reuse its existing resolution logic. |
| Modify `Executor.java`, `Engine.java` | Pass the explicit mode and parse CLI options. |
| Create estimator/order/operator/planner unit tests and `Exercise11OptimizerIT.java` | Exercise 11 tests using existing fixtures and temporary data. |
| Modify existing storage/catalog/DELETE/Exercise10/CLI tests | Assert NDV and select syntax mode where historical plan shape/counts are the contract. |
| Modify `docs/optimizer-design.md`, `README.md`; create one learning log | Explain choices, usage, and actual implementation validation. |

## Task 1: Complete the Design Contract

**Files:** Modify `docs/optimizer-design.md`.

**Interfaces:** Produces the documented formulas, missing-statistic semantics, tie rules, graph restrictions, and compatibility policy above. No runtime change.

- [x] Update the document using the proposed decisions, preserving the team's max merge and greedy approach. Clearly describe uniformity, containment, independence, and unchanged intermediate NDV as assumptions.
- [x] Cite the named sources above and describe exact HashSet counting at course scale versus optional mergeable sketches at billion-row scale.
- [x] Check the document against Exercise 11 section 1 and its definition of done. Resolve any team-requested changes to these proposals before implementation begins.

## Task 2: Persist, Collect, and Observe Partition NDV

**Files:** Modify `CatalogStore.java`, `StorageEngine.java`, `CatalogStoreTest.java`, `StorageEngineIT.java`, `DeleteIT.java`; create `PartitionNdvTest.java`.

**Interfaces:** Extend `ColumnStatistics` to `(String columnName, String min, String max, long ndv)`. Update fixture constructors; old-catalog compatibility is waived. Known NDV is nonnegative, positive for nonempty partitions, and cannot exceed its partition row count.

- [x] Add failing tests: a four-row partition with LONG `[1,1,2,2]`, DOUBLE `[1.5,1.5,2.5,2.5]`, and STRING `[a,a,b,c]` stores NDVs `[2,2,3]`; a second partition starts a fresh set. Reload the database and assert the counts survive.
- [x] Test known-NDV persistence and reject negative NDV, zero for a nonempty partition, and NDV above row count. Add known-NDV persistence coverage to the existing catalog rebase test so renumbering cannot lose it.
- [x] Run `mvn -B "-Dtest=CatalogStoreTest,PartitionNdvTest" test`; expect the new assertions/API to fail before implementation.
- [x] Count typed values with a HashSet in `StorageEngine.statisticsFor`, reusing its existing per-column traversal. Keep each set local to a column/partition. Validate persisted known NDV against row count without changing partition bytes.
- [x] Retain min/max logging and add a separate `table=... partition=... column=... rows=... ndv=...` line for every created NDV. Use the existing field sanitization and statement MDC.
- [x] Extend DELETE integration coverage: rewrite `[1,1,2]` by deleting `2`, then assert survivor NDV is one. Carried partitions retain their statistics; fully removed partitions contribute none. Update existing whole-record expected values in storage/DELETE tests.
- [x] Run the unit command above, then `mvn -B "-Dit.test=StorageEngineIT,DeleteIT" verify`; expect zero failures and one NDV creation line per created column summary.

## Task 3: Add Pure Cardinality Estimation

**Files:** Create `CardinalityEstimator.java`, `CardinalityEstimatorTest.java`.

**Interfaces:** Package-private static methods:

```java
long tableNdv(List<Long> partitionNdvs)
long baseRows(List<PartitionMetadata> survivingPartitions)
long effectiveNdv(TableCatalog table, int columnIndex)
double filterRows(double inputRows, Comparison comparison, long ndv)
double joinRows(double leftRows, double rightRows, long leftNdv, long rightNdv)
```

`tableNdv` consumes required NDVs; `effectiveNdv` merges the pinned table statistics. `baseRows` sums with long arithmetic and explicit overflow detection. Other functions follow Task 1's numeric policy.

- [x] Add failing assertions: max merge `[2,5,3] -> 5`, empty merge `-> 0`, surviving rows `[40,60] -> 100`, equality `(120, ndv=12) -> 10`, range `120 -> 40`, join `(100,40,20,10) -> 200`, and chained filters `120 / 12 / 3 -> 10/3` within tolerance.
- [x] Add cases for a fully pruned table, zero-row joins, nonzero estimates below one, negative/non-finite inputs, and a large product that would overflow long arithmetic. Assert `joinRows(Double.MAX_VALUE, 2, 1, 1)` saturates at `Double.MAX_VALUE`. Every valid result is finite. A nonempty denominator of zero is guarded rather than divided by zero.
- [x] Assert effective NDV is the partition maximum and zero for an empty table. Assert an intermediate join uses its contributing base NDV rather than deriving a new distinct count from estimated rows.
- [x] Run `mvn -B "-Dtest=CardinalityEstimatorTest" test`; expect failure, implement the functions without storage access, then rerun and expect zero failures.

## Task 4: Select and Explain the Greedy Logical Order

**Files:** Create `GreedyJoinOrder.java`, `GreedyJoinOrderTest.java`.

**Interfaces:** `static Order choose(SelectStatement statement, List<TableCatalog> tables, Map<String, Double> filteredRows)`, consuming FROM-ordered pinned catalogs and Task 3 estimates. Define nested immutable records `Order(List<String> tableNames, List<Step> steps)` and `Step(JoinPredicate predicate, double estimatedRows)`. At zero-based step i, join the first i+1 ordered tables with table i+2; one-table orders have no steps.

- [x] Add a hand-made three-table test with FROM `A,B,C`, rows `10000,100,10`, predicates `a_b=b_a` and `a_c=c_a`, and NDVs `100/100` and `100/10`. Column prefixes identify their owning table; SQL references remain unqualified. Assert order `A,C,B` and step estimates `[1000,1000]`, versus syntax's initial estimate of 10000.
- [x] Extend that fixture with D: rows 1, predicate `b_d=d_b`, NDVs `100/1`. Assert order `B,D,A,C` and estimates `[1,100,10]`. Assert C is never selected as a cross product before A connects it.
- [x] Add deterministic tie, reversed-operand, single-table, disconnected, cycle, and parallel-predicate cases. A connected tree with FROM `A,C,B` and edges `A-B,B-C` must work in optimized mode. Reject extra predicates before choosing an order so none can be ignored accidentally.
- [x] Run `mvn -B "-Dtest=GreedyJoinOrderTest" test`; expect failure. Implement selection using simple lists/sets and ColumnResolver, with no operator construction or partition reads.
- [x] Log each initial predicate candidate and every connected extension evaluated, including step, joined/added tables, predicate, input estimates, and estRows. Use semicolons in table lists so the existing CSV log's message remains one field. Assert the hand-made four-table case logs three initial candidates and its two connected extensions.
- [x] Rerun the focused test; expect zero failures. Do not introduce a general cost-model or optimizer-strategy interface.

## Task 5: Build Correct Physical Plans in Both Modes

**Files:** Create `JoinMode.java`, `ColumnOrderOperator.java`, `ColumnOrderOperatorTest.java`, `Exercise11PlannerTest.java`; modify `Planner.java`, `ColumnResolver.java`, `HashJoinOperator.java`, `Executor.java`, `Exercise10PlannerTest.java`, `Exercise10HashJoinTest.java`, `Exercise10JoinIT.java`.

**Interfaces:**

- `public enum JoinMode { OPTIMIZED, SYNTAX }`.
- Add `Planner(StorageEngine storage, JoinMode mode)` and `Executor(StorageEngine storage, JoinMode mode)`. Existing constructors delegate to OPTIMIZED. Executor constructs its planner with that mode and keeps its current snapshot lifetime.
- Private planner record `Input(Operator operator, List<TableCatalog> layout, double estimatedRows)` carries the actual row schema and size of a table or intermediate. `planTable` returns Input and estimates using the same surviving partitions it scans.
- `ColumnOrderOperator(Operator child, int[] sourceIndexes)` maps each output slot to a physical input slot. Copy the index array, emit a fresh row, and delegate lifecycle; expose `child()` for tests.
- Extend the package-private hash join constructor to `HashJoinOperator(Operator build, Operator probe, int buildKey, int probeKey, int joinNumber, List<String> tables, List<String> buildTables, List<String> probeTables, double estimatedRows)`. Update its existing test helper to use the explicit constructor.

- [x] Add failing tests for strict syntax-mode FROM fold/build policy, optimized initial and later build-side swaps, and filtered/pruned size estimates. Test ties building the logical left input. Retain the old syntax errors for invalid FROM prefixes and multiple connecting predicates.
- [x] Add row assertions for the three-/four-table fixtures after multiple build-side swaps. Join keys must remain correct at every level, and final columns must follow the original FROM list. Cover duplicate multiplication, reversed predicate operands, and two unreferenced columns sharing a name across tables.
- [x] Add permutation-operator tests for mapping `[2,0,1]`, exhaustion, fresh output arrays, and child cleanup. Build the final mapping from `(table identity, local column index)`, not column names: SELECT * can include unreferenced duplicate names.
- [x] Run `mvn -B "-Dtest=ColumnOrderOperatorTest,Exercise11PlannerTest,Exercise10PlannerTest,Exercise10HashJoinTest" test`; expect the new contracts to fail.
- [x] Refactor pruning/table planning once, returning Input with Task 3's filtered estimate. Keep aggregate ScanStats and per-scan statistics correct. Estimate from the catalog objects supplied by Executor, never a fresh `storage.table()` lookup.
- [x] Keep a syntax fold matching the old implementation exactly, adding only estimates/diagnostics. For optimized mode consume Task 4's order and choose the smaller estimated input as build at each step. Derive each key index from the actual build/probe layouts using ColumnResolver with those schemas; concatenate layouts in physical build/probe order.
- [x] Add one final ColumnOrderOperator only if physical layout differs from FROM order. Do not reorder every intermediate or change HashJoinOperator's row concatenation semantics. Update ColumnResolver's documentation to describe offsets in the supplied schema order.
- [x] Log the chosen logical order and mode once per SELECT. Assign join numbers from inner logical step to outer step, independently of close order. Include physical buildTables/probeTables when creating/logging each join so choices are reviewable even when logical orders tie.
- [x] Make close idempotent per open attempt and retain cleanup on open/child-close failures. A never-opened join emits no execution summary; an attempted join emits its summary once. Test repeated close and nested open failure without leaked children or duplicate summaries.
- [x] Each executed join summary includes `join`, `tables`, `estRows`, `rowsBuild`, `rowsProbe`, and `rowsOut` on the same line, plus physical sides. Continue using existing MDC for sessionId/statementNumber and the existing CSV envelope. Syntax mode also receives real estimates.
- [x] Switch historical shape/count assertions to explicit SYNTAX mode; leave result-only tests covering the optimized default. Rerun the focused unit command and `mvn -B "-Dit.test=Exercise10JoinIT" verify`; expect zero failures.

Example message fields, inside the existing CSV log envelope:

```text
statement=SELECT joinOrder=B;D;A;C mode=optimized
join=1 tables=B;D buildTables=D probeTables=B estRows=1.0 rowsBuild=1 rowsProbe=100 rowsOut=...
```

## Task 6: Expose CLI Plan Modes

**Files:** Modify `Engine.java`, `EngineIT.java`, `README.md`; add CLI cases in `Exercise11OptimizerIT.java`.

**Interfaces:** Accept `[-j optimized|syntax] -c <SQL>` or `[-j optimized|syntax] -f <script>`, also allowing the option after the command pair. Parse the SQL/path as the value of its flag, even if the value starts with a dash. Pass JoinMode through Executor; no global mutable configuration.

- [x] Add failing Engine.run tests for both modes, both option positions, and the optimized default. Assert missing/unknown mode, repeated -j, repeated/conflicting -c/-f, missing command value, and stray arguments fail before creating storage. Preserve no-argument team/usage output with updated usage text.
- [x] Run `mvn -B "-Dit.test=EngineIT" verify`; expect the new flag cases to fail. Implement a small explicit argument parser using the standard library, then rerun and expect zero failures.
- [x] Document default optimization, `./engine.sh -j syntax -f script.sql`, and forcing order with FROM. `engine.sh` already forwards all arguments and needs no implementation change.
- [x] Add packaged-launcher assertions for both -c and -f with -j, checking clean result CSV and mode in logs. Exercise a forced FROM order in syntax mode and confirm its original build-side choices.

## Task 7: Prove Equivalence, Snapshot Safety, and Log Completeness

**Files:** Create/complete `Exercise11OptimizerIT.java`; reuse `Exercise10TestData`, its DuckDB oracle files, and existing appender/snapshot-test patterns. Modify `Exercise10JoinIT.java` only where mode-specific assertions require it.

**Interfaces:** Existing `Engine.run`, Executor(mode), and real packaged `engine.sh`; logs are keyed by `(sessionId, statementNumber, joinNumber)` rather than line position.

- [x] Run all four existing Exercise 10 queries in both modes against the same fixture state. Assert identical sorted lists including duplicates and equality with the recorded DuckDB answers. Include three-/four-table, local-filter, empty-result, and reversed-FROM variants.
- [x] For strict reordering assertions, use Task 4's deliberately unequal three-/four-table fixtures with temporary CSVs. Compare logged join orders. On the original small testbed, allow cardinality ties to keep logical order; compare physical plan fingerprints including build/probe sides to establish plan differences.
- [x] For each executed n-table SELECT, assert exactly n-1 join summaries with unique join numbers, finite estRows, and rowsOut on the same line. Run a script with two SELECTs to verify correct statementNumber attribution. Assert single-table queries have zero join summaries and candidate logs remain parseable in the existing seven-field CSV layout.
- [x] Extend the synchronous planning observer test: COPY a row after Executor pins all catalogs but before a scan opens. Assert both estimates and results match the original snapshots; a later SELECT sees the new metadata and rows. Cover NDV preservation through existing write-rebase tests.
- [x] Run `mvn -B "-Dit.test=Exercise11OptimizerIT,Exercise10JoinIT" verify`, then final `mvn -B verify`; expect zero failures. Existing launcher tests execute a Bash script directly: if Windows cannot launch it, fix the test invocation portably or run the final suite in the supported Bash/Linux environment and record that limitation accurately.
- [x] Use the java-docs and ai-usage-log skills for the implementation. Write one sanitized entry after the final validation, record actual commands/results, and link it from the PR. Review the final diff and run `git diff --check` before opening a PR.

## Task 8: Review and Release v0.11

**Files:** No additional engine features; PR description links the design and implementation learning log.

- [ ] Complete human review/academic ownership and confirm CI `mvn -B verify` is green.
- [ ] Merge the reviewed implementation. Cut and push annotated `v0.11` from the verified main revision using Exercise 11 section 9, when release publication is authorized. Do not tag an unmerged planning or implementation branch.
- [ ] Keep week 12 measurements and benchmark claims for the later exercise; this release provides the estimates, actuals, and forced-order mode that benchmark will consume.

## Execution Order and Checkpoints

Tasks 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8. Each runtime task starts with its meaningful failing tests and finishes with its stated passing check. Keep reviewable commits aligned with these deliverables when implementation commits are requested; plan creation itself makes no commit, PR, tag, or release.

The current checkout already contains Exercise 10's SQL AST, shared ColumnResolver, pruned table pipelines, snapshot leases, duplicate-preserving hash join, and recorded oracle fixtures. Reuse these. The main correctness checkpoint is Task 5: logical join order, physical row layout, and public output column order must remain distinct throughout planning.


## Execution Results

- Implemented Tasks 1–7 using Terra 6.1 workers and primary-agent review, with the accepted fresh-catalog amendment.
- The primary review added Java-API identifier sanitization and a failing regression, corrected test logger isolation, and extended real CLI three-/four-table comparisons under both flags.
- Final independent Terra review found no material actionable defects.
- Final mvn -B verify passed: 146 unit tests and 124 integration tests; zero failures, errors or skips. Packaged CLI tests used Git Bash on Windows.
- POSIX and the Windows Java fallback remain unexecuted platform paths. Release operations in Task 8 remain separate.
- Learning log: [Exercise 11 optimizer](../../ai-usage/2026-11-22-exercise-11-optimizer.md).
