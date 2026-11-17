# Exercise 10 implementation plan

> **For agentic workers:** Use superpowers:executing-plans for inline implementation and a final independent review.

**Goal:** Make the existing Exercise 10 tests pass by implementing the required multi-table hash joins.

**Architecture:** Extend the existing SQL pipeline, resolve columns once in a shared helper, push filters into each table pipeline, and fold hash joins in FROM order. Pin all table catalogs before binding/planning and release them after execution.

**Tech stack:** Existing Java 25+, Maven, ANTLR, JUnit 6, and Java collections; no new dependencies.

**Spec:** `exercise-descriptions/Exercise10.md` and the existing Exercise 10 tests.

## Global constraints

- Omit qualified names, aliases, projections, and future join optimization.
- Reject multiple connecting predicates and disconnected FROM entries explicitly.
- Preserve existing tests, comments, single-table APIs, snapshot leases, and pruning.
- Git stays read-only: no branches, worktrees, commits, tags, or pushes.
- Use the existing `docs/ai-usage/README.md` template and destination for one learning log.

## Review focus

- Duplicate keys must emit every pair without reusing output arrays.
- Joined offsets follow FROM order even with reversed join operands.
- Multiple filters combine both pruning and row filtering.
- Every table is pinned before any planning diagnostic can trigger a concurrent write.
- Snapshot acquisition and operator failures must release already acquired resources.

### Task 1: SQL front end and binding

**Files:** SQL grammar; `SelectStatement`, new `JoinPredicate`, `SqlAstBuilder`, `SqlPrinter`, new `ColumnResolver`, and `Binder`.

**Interfaces:** Required `SelectStatement(List<String>, List<Predicate>, List<JoinPredicate>)`; retain the single-table constructor and predicate accessor used by existing tests. `ColumnResolver(List<TableCatalog>).resolve(String)` returns table name, local column index, joined index, and type. Binder consumes FROM-ordered pinned catalogs.

- [x] Run existing Exercise 10 unit tests: 53 cases, 47 failures, six passing restrictions.
- [x] Implement grammar/AST/printer and shared column resolution/type validation.
- [x] Run front-end, binder, resolver, and existing parser/printer unit tests; expect zero failures.

### Task 2: Hash join and planning

**Files:** New `HashJoinOperator`, `Planner`, `ScanOperator`.

**Interfaces:** `HashJoinOperator(Operator, Operator, int, int)` with build/probe inspection; `ScanOperator.scanStats()`; planner accepts FROM-ordered pinned catalogs.

- [x] Implement duplicate-preserving build/probe cursor and lifecycle/cardinality logging.
- [x] Build filtered, pruned scans per table; fold one connecting predicate per table in FROM order.
- [x] Run all Exercise 10 unit tests and the existing unit suite; expect zero failures.

### Task 3: Statement snapshots, integration, and review

**Files:** `CatalogStore`, `StorageEngine`, `Executor`, learning log.

**Interfaces:** CatalogStore acquires/releases a group of existing table snapshot leases; executor binds/plans against that group.

- [x] Pin every FROM table before binding/planning, with cleanup on acquisition or execution failure.
- [x] Run `mvn -B verify`; expect all unit and integration tests, including packaged-launcher oracle comparisons, to pass.
- [x] Independently review the diff against the exercise, resolve material findings, and run required verification after changes.
- [x] Write one sanitized learning log and check the final diff for whitespace errors.

## Execution record

- The user explicitly requested implementation of the existing exercise and red tests; proceed within that scope without another approval gate.
- Maven cache access required tool approval because the sandbox blocked its lock file.
- Ruling: use the repository's learning-log path/template rather than the skill's conflicting destination, as the supplied AGENTS.md requires.
- Ruling: keep this checkout and do not run git-mutating skill scripts, following the user's read-only-git instruction.
- Task 1: complete — focused SQL/parser/printer/planner run: 79 cases, zero failures/errors/skips.
- Task 2: complete — full unit suite: 130 cases, zero failures/errors/skips.
- Task 3 validation: `mvn -B verify` passed with 130 unit cases and 115 integration cases. All 66 Exercise 10 cases pass, including all four recorded DuckDB queries through the real packaged launcher and concurrent-write snapshot checks.
- Final independent review found no Critical or Important issues. Minor observation: the old record's `tableName()` accessor is replaced by `tableNames()`; the compatibility constructor and `where()` accessor needed by existing callers are retained. Ruling: accept this AST API change required by the exercise rather than adding an unused compatibility method; downstream callers of `tableName()` must adapt.
- Final diff and new-file whitespace checks passed. Test report totals confirmed 245 tests with zero failures/errors/skips. No production changes followed the passing full verification run.
- Finish: leave all changes in the current checkout, with Git read-only as requested. No integration menu, commits, release tags, or pushes apply.
