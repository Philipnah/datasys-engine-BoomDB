# Code change: Exercise 10 tests before implementation

- Date: 2026-11-12
- Contributor: Philip Han
- Assistance: AI-assisted
- AI tool/model: Codex

## Request summary

Add the tests required by Exercise 10 before implementing multi-table joins.
Keep the feature tests deliberately failing for the TDD red phase. Reject
additional connecting join predicates and omit the optional qualified-name,
alias, and projection extensions.

## Change and assistance summary

Added 66 JUnit cases covering parser/AST/printer behaviour, multi-table binding,
column resolution, hash join cursor and lifecycle behaviour, left-deep planning,
filters and partition pruning, and executor/packaged-launcher integration.
Additional cases check reversed join operands, FROM reordering, a second
connecting predicate, per-join cardinality logs, and a snapshot pinned for each
table before planning starts.

Independent review identified two gaps that were addressed: each inspected scan
now exposes its table's ScanStats through a proposed package-visible
`scanStats()` accessor, and log checks count only records with the three summary
counters while allowing unrelated diagnostics and different field ordering.

A small test-only reflection helper bridges APIs that do not exist yet. This
keeps the tests compilable and reports missing APIs as individual assertion
failures. It unwraps production exceptions so rejection tests cannot pass merely
because reflection failed. Binder and planner tests construct ASTs independently
of the parser, keeping syntax errors outside their rejection assertions.
The trade-off is weaker compile-time checking until those APIs are implemented;
the proposed signatures are recorded in the plan.

The four expected query outputs were captured from the installed DuckDB CLI
using explicit schemas and headerless COPY input. Their 4/4/4/3 row counts were
checked against the exercise and the recorded files were compared byte for byte
with the captured results. Integration tests sort actual rows before comparing
them, preserving duplicates while allowing unspecified join output order.
Recorded oracle files avoid adding a DuckDB installation requirement to CI.

AI assistance covered test design, fixtures, oracle capture, test execution,
review, and documentation. Production sources, the grammar, build configuration,
the launcher, and existing tests were left unchanged. No Git mutation commands
were run. Implementing the feature and making these tests green is deferred.

## Changed files

- `src/test/java/dk/itu/boomdb/Exercise10TestApi.java` — test-only missing-API bridge and bounded operator drain.
- `src/test/java/dk/itu/boomdb/Exercise10TestData.java` — exercise schemas, queries, setup, and result helpers.
- `src/test/java/dk/itu/boomdb/Exercise10FrontEndTest.java` — table lists, AND, AST separation, round trips, and syntax restrictions.
- `src/test/java/dk/itu/boomdb/Exercise10BinderTest.java` — name resolution and join/filter type validation.
- `src/test/java/dk/itu/boomdb/Exercise10ColumnResolverTest.java` — table ownership, local/joined indices, reordering, and error cases.
- `src/test/java/dk/itu/boomdb/Exercise10HashJoinTest.java` — duplicates, unmatched/empty inputs, key indices, lifecycle, and summary logging.
- `src/test/java/dk/itu/boomdb/Exercise10PlannerTest.java` — tree shape, filters, pruning, ScanStats, output order, and unsupported joins.
- `src/test/java/dk/itu/boomdb/Exercise10JoinIT.java` — all four queries through the executor and packaged launcher, cardinality logs, and snapshots.
- `src/test/resources/exercise10/` — four input CSVs, four DuckDB golden outputs, and oracle provenance/reproduction notes.
- `docs/superpowers/plans/2026-11-12-exercise-10-red-tests.md` — tests-only scope, proposed interfaces, and verification record.
- `docs/ai-usage/2026-11-12-exercise-10-red-tests.md` — this learning log.

## Validation

- Baseline `mvn -B verify` — 77 unit tests and 102 integration tests passed before adding the tests.
- `mvn -B '-Dtest=Exercise10FrontEndTest,Exercise10BinderTest,Exercise10ColumnResolverTest' test` — 36 cases, 30 intended failures, zero errors/skips. Six existing syntax restrictions passed.
- `mvn -B '-Dtest=Exercise10*Test,Exercise10JoinIT' test` — 66 cases, 60 intended failures, zero errors/skips. Failures identify absent APIs, unsupported syntax, or the launcher's missing join output.
- `mvn -B clean verify -Dmaven.test.failure.ignore=true` — ran both phases despite the intended red unit tests: 130 unit cases with 47 failures and 115 integration cases with 13 failures; zero errors/skips. All 179 original tests passed. The temporary failure-ignore flag allows Maven to finish the lifecycle; its successful process exit does not mean the tests are green.
- DuckDB v2.0.0 (Cyanoptera), commit `d54cbbe3df` — actual sorted results exactly matched all four golden files.
- `git diff -- src/main pom.xml engine.sh .github` — no production, grammar, build, launcher, or CI changes.
- `git diff --check` — passed; whitespace in new untracked files was also checked separately.

## Ownership checkpoint

- [ ] I reviewed this change and can explain its design, correctness, and trade-offs.
