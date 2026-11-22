# Code change: Exercise 11 join optimizer

- Date: 2026-11-22
- Contributor: AntohaY
- Assistance: AI-assisted
- AI tool/model: Codex with Terra 6.1 implementation and review subagents

## Request summary

Implement the reviewed Exercise 11 optimizer plan with its proposed estimation
defaults, and validate it against the existing engine and oracle fixtures.
The contributor explicitly excluded compatibility with old development
catalogs, so this change requires catalogs containing NDV rather than adding
migration or missing-statistic fallback behavior.

## Change and assistance summary

COPY now counts distinct typed values per partition column and persists NDV
beside min/max. DELETE recomputes those statistics through the same partition
writer. The estimator uses max-partition NDV, uniform equality selectivity,
one-third range selectivity, and the maximum-domain equi-join formula. These
approximations are documented; they do not promise accurate estimates for skew
or disjoint domains.

The default planner chooses the smallest estimated initial join and connected
extensions, and builds each hash table from the smaller estimated input.
Physical column layouts are tracked explicitly and restored to FROM order at
the output. Syntax mode preserves the previous FROM fold and build policy.
Candidate, order, and per-join estimate/actual logs retain statement context
and CSV-compatible fields. Hash joins close and log once per open attempt.

Terra subagents implemented focused stages; Codex reviewed their changes,
added a regression for diagnostic sanitization through the Java API, corrected
test logger isolation, and extended real CLI comparisons. A fresh Terra review
found no material actionable defects. Tests compare sorted lists, preserving
duplicates, against existing recorded DuckDB answers and between both modes.
The shared test launcher invokes Git Bash on this Windows environment.

## Changed files

- `src/main/java/dk/itu/boomdb/CatalogStore.java` — required NDV metadata and partition validation.
- `src/main/java/dk/itu/boomdb/StorageEngine.java` — shared NDV collection and creation logs.
- `src/main/java/dk/itu/boomdb/CardinalityEstimator.java` — pure merge and row estimates.
- `src/main/java/dk/itu/boomdb/GreedyJoinOrder.java` — connected greedy selection and candidate logs.
- `src/main/java/dk/itu/boomdb/JoinMode.java` — explicit optimized/syntax policy.
- `src/main/java/dk/itu/boomdb/Planner.java` — estimation, build selection, physical keys and output mapping.
- `src/main/java/dk/itu/boomdb/ColumnOrderOperator.java` — final output-column permutation.
- `src/main/java/dk/itu/boomdb/HashJoinOperator.java` — plan diagnostics and idempotent cleanup.
- `src/main/java/dk/itu/boomdb/ColumnResolver.java` — schema-order documentation.
- `src/main/java/dk/itu/boomdb/Executor.java` — propagate join mode using pinned catalogs.
- `src/main/java/dk/itu/boomdb/Engine.java` — validate and parse the join-mode flag.
- `src/test/java/dk/itu/boomdb/PartitionNdvTest.java` — typed counts, persistence, bounds and separate creation logs.
- `src/test/java/dk/itu/boomdb/CardinalityEstimatorTest.java` — formulas, empty inputs and numeric boundaries.
- `src/test/java/dk/itu/boomdb/GreedyJoinOrderTest.java` — hand-calculated orders, ties, graph restrictions and candidates.
- `src/test/java/dk/itu/boomdb/ColumnOrderOperatorTest.java` — output mapping and lifecycle.
- `src/test/java/dk/itu/boomdb/Exercise11PlannerTest.java` — build swaps, FROM columns, duplicate names and connected prefixes.
- `src/test/java/dk/itu/boomdb/OptimizerLoggingTest.java` — CSV-safe diagnostics for Java API identifiers.
- `src/test/java/dk/itu/boomdb/Exercise11OptimizerIT.java` — metamorphic/oracle, snapshot, log-context and real CLI checks.
- `src/test/java/dk/itu/boomdb/TestLauncher.java` — portable packaged-engine invocation for tests.
- `src/test/java/dk/itu/boomdb/CatalogStoreTest.java` — NDV persistence through catalog rebasing.
- `src/test/java/dk/itu/boomdb/StorageEngineIT.java` — expected persisted statistics.
- `src/test/java/dk/itu/boomdb/DeleteIT.java` — rewritten and carried NDV statistics.
- `src/test/java/dk/itu/boomdb/EngineIT.java` — flag validation, placement and usage.
- `src/test/java/dk/itu/boomdb/Exercise10HashJoinTest.java` — diagnostics, duplicate preservation and failure cleanup.
- `src/test/java/dk/itu/boomdb/Exercise10PlannerTest.java` — explicit syntax-mode regression assertions.
- `src/test/java/dk/itu/boomdb/Exercise10JoinIT.java` — syntax cardinality baseline and portable launcher.
- `src/test/java/dk/itu/boomdb/LauncherIT.java` — shared portable launcher.
- `docs/optimizer-design.md` — chosen formulas, assumptions, graph scope and development catalog policy.
- `docs/superpowers/plans/2026-11-22-exercise-11-optimizer.md` — accepted amendments and execution results.
- `README.md` — mode usage.

## Validation

- Baseline `mvn -B test` — 130 unit tests passed.
- Focused NDV, estimator, order, planner, lifecycle and CLI checks — passed after their recorded failing cases and implementation.
- `mvn -B "-Dtest=OptimizerLoggingTest" test` — reproduced raw diagnostic fields breaking CSV, then passed after sanitization.
- `mvn -B "-Dtest=OptimizerLoggingTest,GreedyJoinOrderTest,Exercise11PlannerTest" test` — 9 passed after fixing logger isolation.
- Final `mvn -B verify` — 146 unit and 124 integration tests passed; zero failures, errors or skips. Totals checked against Surefire and Failsafe XML.
- Packaged CLI checks exercised both flags on three-/four-table queries and both command forms on Windows with Git Bash. POSIX execution and the Windows Java fallback were not run here.
- `git diff --check` — passed for implementation changes; final documentation whitespace and local links checked separately.

## Ownership checkpoint

- [ ] I reviewed this change and can explain its design, correctness, and trade-offs.
