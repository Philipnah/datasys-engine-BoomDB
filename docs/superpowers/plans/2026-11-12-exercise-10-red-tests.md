# Exercise 10 red-phase tests implementation plan

> **For agentic workers:** Use superpowers:executing-plans for this tests-only task. Stop at RED; production implementation is explicitly deferred by the contributor.

**Goal:** Add runnable JUnit 6 tests for Exercise 10 and demonstrate the missing behaviour with failing assertions.

**Architecture:** Exercise the existing parser, binder, planner, executor, and packaged launcher. A test-only reflection helper bridges the absent AST, column resolver, and hash join APIs so one missing class does not prevent every other test from running. Real storage and the existing TestListOperator provide inputs; recorded DuckDB results provide independent expected output.

**Tech Stack:** Java 25+, Maven 3.9+, JUnit 6, existing DuckDB CLI for recording oracle output.

**Spec:** `exercise-descriptions/Exercise10.md`, sections 1–7 and the definition of done.

## Global constraints

- Change tests, fixtures, and documentation only; leave production code and build configuration unchanged.
- Reject a second connecting join predicate; omit qualified names, aliases, and projection extensions.
- Keep Git read-only, retain existing comments, and do not create branches, worktrees, commits, or tags.
- Use the repository's existing `engine.sh -f` launcher.
- Keep the new tests enabled and intentionally failing until later implementation.

## Review focus

- Avoid parser failures falsely satisfying binder/planner validation tests: construct the proposed AST independently there.
- Check duplicate output multiplicity and row values, not merely counts.
- Check joined-column offsets after unequal-width schemas and reordered FROM lists.
- Reject tables that connect only to later FROM entries, and reject silently ignored extra predicates.
- Preserve each table's pinned snapshot when a write occurs before scans execute.

### Task 1: Front end and column resolution

**Files:** `Exercise10TestApi.java`, `Exercise10TestData.java`, `Exercise10FrontEndTest.java`, `Exercise10BinderTest.java`, and `Exercise10ColumnResolverTest.java` under `src/test/java/dk/itu/boomdb/`.

**Interfaces:** The specified `JoinPredicate(String, String)` and `SelectStatement(List, List, List)`; proposed `ColumnResolver(List<TableCatalog>).resolve(String)` returning accessors `tableName()`, `columnIndex()` (table-local), `joinedIndex()`, and `type()`.

- [x] Add focused parser shape/AND/round-trip tests, binder acceptance and rejection tests, and resolver ownership/index/error cases.
- [x] Run the new tests; expect failures for unsupported syntax or absent APIs, with no compilation errors.

### Task 2: Hash join and planning

**Files:** `Exercise10HashJoinTest.java` and `Exercise10PlannerTest.java` under the same test package.

**Interfaces:** Specified `HashJoinOperator(Operator, Operator, int, int)`, with package-visible `build()`/`probe()` accessors for required plan-shape inspection; proposed package-visible `ScanOperator.scanStats()` returns the existing ScanStats record for each table's scan. Existing Operator and FilterOperator APIs are reused.

- [x] Test duplicate multiplication, unmatched keys, both empty sides, nonzero key indices, lifecycle, and close summary.
- [x] Test the four-table left-deep tree, filters and pruning per table, conjunctive ScanStats, reversed predicate operands, FROM reordering, disconnected tables, and extra predicates.
- [x] Run focused tests and inspect each failure's cause.

### Task 3: Oracle and integration

**Files:** `Exercise10JoinIT.java`, CSV fixtures and four golden outputs under `src/test/resources/exercise10/`, and one learning log under `docs/ai-usage/`.

**Interfaces:** Existing Executor.execute and `engine.sh -f`; literal queries and schemas from the exercise.

- [x] Record and hand-check DuckDB's sorted outputs: 4, 4, 4, and 3 rows.
- [x] Compare executor and packaged-launcher output with each golden file; test join summaries and statement-start snapshots.
- [x] Run all new tests explicitly, then the complete Maven verification lifecycle with intentional test failures allowed to continue so integration tests also run.
- [x] Review actual report totals and failure reasons, verify no production changes, and write the learning log with the observed RED result.

## Execution notes

- The user has already authorized implementation of the tests; no additional design approval is needed.
- Baseline `mvn -B verify`: 77 unit tests and 102 integration tests passed.
- Maven needs its normal `~/.m2` cache outside the workspace sandbox; execution approval was requested through the tool.
- The repository README's learning-log template and `docs/ai-usage/` destination take precedence over the differing skill template/destination.
- Focused RED run: 66 cases, 60 expected failures, six passing syntax restrictions, zero errors/skips.
- Full `mvn -B clean verify -Dmaven.test.failure.ignore=true`: 130 unit cases (47 expected failures) and 115 integration cases (13 expected failures). All 179 original tests still pass. The command-line failure-ignore flag is solely to execute both test phases and does not make the tests green.
- Independent review identified two test gaps, both addressed: inspect ScanStats on every table's scan, and count only log records with the mandated summary fields while allowing other diagnostics and field ordering.
