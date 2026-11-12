# Code change: Exercise 10 multi-table hash joins

- Date: 2026-11-12
- Contributor: Philip Han
- Assistance: AI-assisted
- AI tool/model: Codex

## Request summary

Implement the required Exercise 10 behaviour against the existing failing tests,
keeping the solution simple and omitting the optional extensions.

## Change and assistance summary

Extended SELECT parsing to table lists and AND conjunctions, separating typed
constant filters from column equality joins in the AST. The printer preserves
the round-trip property by printing filters first and joins second; order within
each list is retained. DELETE still accepts only one table and one constant
filter. The original single-table SELECT constructor and predicate accessor
remain available for existing callers.
The old record's `tableName()` accessor is replaced by `tableNames()` in the
required AST; callers using that old accessor need to adapt.

A shared ColumnResolver maps each referenced name to its owning table, local
column index, FROM-concatenated index, and type. Binding rejects unknown or
ambiguous columns, same-table joins, and differing join-key types. Exact types
matter because the hash table uses Java equality, so Long and Double keys do
not compare equal even when their numeric values coincide.

HashJoinOperator builds a Java hash map of key-to-row-lists and streams the
probe child. A saved iterator emits every duplicate-key pair on successive
next calls. Each result gets a fresh array with build columns before probe
columns. Closing the operator closes both children, clears retained state,
and logs rowsBuild, rowsProbe, and rowsOut.

Planning builds a pruned scan and constant-filter pipeline for each table,
then folds joins left to right in FROM order. Earlier joined columns use
concatenated offsets; the incoming probe table uses local offsets. Every scan
retains its own partition counts, and the statement also records aggregate
counts. The planner rejects disconnected tables with a suggestion to reorder
FROM. It also rejects a second connecting join predicate rather than silently
ignoring it. This restriction is explicitly allowed by the exercise.

The executor pins every SELECT table before binding or planning. CatalogStore
acquires the group under its existing publication monitor and releases partial
acquisitions on failure. Statement-scoped leases keep the pinned catalogs
alive until all operators finish, including when planning or execution throws.
Existing single-table storage operations reuse their pinned catalogs.

The deliberate trade-offs follow Exercise 10: the complete intermediate build
side stays in memory, FROM order is trusted, and each new table supports only
one connecting equality. Qualified names, aliases, projections, cross products,
and future join-order optimization were omitted. No dependencies were added.

AI assistance covered implementation, verification, review, and this explanation.
The existing tests and recorded DuckDB fixtures were retained unchanged.

## Changed files

- `src/main/antlr4/dk/itu/datasys/sql/Sql.g4` — table lists, AND conditions, and separate filter/join syntax.
- `src/main/java/dk/itu/boomdb/SelectStatement.java` — immutable multi-table AST and single-table compatibility.
- `src/main/java/dk/itu/boomdb/JoinPredicate.java` — column equality AST record.
- `src/main/java/dk/itu/boomdb/SqlAstBuilder.java` — classify parsed predicates into filters and joins.
- `src/main/java/dk/itu/boomdb/SqlPrinter.java` — normalized multi-table SQL and conjunctive conditions.
- `src/main/java/dk/itu/boomdb/ColumnResolver.java` — shared ownership, type, and offset resolution.
- `src/main/java/dk/itu/boomdb/Binder.java` — cross-table name and type validation against pinned schemas.
- `src/main/java/dk/itu/boomdb/HashJoinOperator.java` — duplicate-preserving inner hash join and cardinality logs.
- `src/main/java/dk/itu/boomdb/Planner.java` — local pruning/filtering and FROM-order left-deep joins.
- `src/main/java/dk/itu/boomdb/ScanOperator.java` — per-scan pruning statistics.
- `src/main/java/dk/itu/boomdb/CatalogStore.java` — grouped snapshot acquisition and cleanup.
- `src/main/java/dk/itu/boomdb/StorageEngine.java` — access to grouped catalog leases.
- `src/main/java/dk/itu/boomdb/Executor.java` — pin all tables before binding/planning and use their catalogs throughout execution.
- `docs/superpowers/plans/2026-11-12-exercise-10-implementation.md` — scoped implementation plan and verification record.
- `docs/ai-usage/2026-11-12-exercise-10-implementation.md` — this learning log.

## Validation

- Baseline `mvn -B '-Dtest=Exercise10*Test' test` — 53 cases, 47 failures identifying missing APIs or syntax, six passing restrictions; zero errors/skips.
- `mvn -B '-Dtest=Exercise10*Test,SqlParserTest,SqlPrinterTest,PlannerTest' test` — 79 cases passed, zero failures/errors/skips.
- `mvn -B verify` — 130 unit cases and 115 integration cases passed, zero failures/errors/skips. All 66 Exercise 10 cases passed, including executor and packaged-launcher comparisons to the recorded sorted DuckDB fixtures, intermediate cardinality logs, and each table's snapshot during a concurrent copy.
- Maven reported shading warnings about overlapping dependency resources/module descriptors; the packaged-launcher tests passed.
- Independent read-only review — no Critical or Important findings. One minor observation concerned the intentional replacement of the old `tableName()` accessor by the exercise's `tableNames()` AST API.
- `git diff --check` — passed. Git was used only for read-only inspection; no branches, commits, tags, or pushes were created.

## Ownership checkpoint

- [ ] I reviewed this change and can explain its design, correctness, and trade-offs.
