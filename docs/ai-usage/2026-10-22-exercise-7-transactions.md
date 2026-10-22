# Code change: Exercise 7 DELETE and versioned catalogs

- Date: 2026-10-22
- Contributor: anton.yakovenko
- Assistance: AI-assisted
- AI tool/model: Codex (GPT-6)

## Request summary

Implement Exercise 7 using the contributor's transaction design: immutable
copy-on-write deletion, repeated CSV ingestion, versioned catalogs, statement
snapshots, retention, and the required SQL and persistence checks. The contributor
specified that a no-match DELETE keeps the current catalog version and selected
a one-hour retention window with at most five versions, except active readers.

## Change and assistance summary

DELETE now passes through parsing, printing, binding, and execution. It uses the
same min/max pruning and row comparisons as SELECT. Only partitions with actual
deleted rows need replacement files; unaffected partitions retain their original
references. Empty survivor sets do not produce empty files. This reuses existing
PAX encoding and keeps old snapshots readable.

Each committed table version has a separate JSON catalog. A temporary catalog is
flushed and atomically renamed before the immutable latest reference is published
in memory. New partition paths use fresh identities and CREATE_NEW, so neither
repeated COPY nor DELETE can truncate a prior partition. Failure before catalog
publication leaves the previous committed data visible, including after restart.

Statements lease one immutable catalog before binding and retain it through
execution and version logging. Shared JVM reference counts protect readers across
engine instances. Retention removes expired/excess catalog versions on commits and
lease release, while protecting the current version and active readers. Legacy
catalogs remain readable. The experiment script discovers numeric finalized
catalog versions, and an executable `engine` wrapper supports the exercise's CLI.

AI assistance covered implementation, regression tests, design clarifications,
and an independent review. Trade-offs: catalog work is serialized per directory;
retention leaves partition bytes in place; orphan reclamation, time-travel SQL,
and Exercise 8 writer-conflict handling are deferred. Atomic rename is not a
claim of universal power-loss durability. Git was used only for reading; no
release tag, commit, branch, or push was created.

## Changed files

- `src/main/antlr4/dk/itu/datasys/sql/Sql.g4` — DELETE grammar and keyword.
- `src/main/java/dk/itu/boomdb/DeleteStatement.java` — DELETE AST record.
- `src/main/java/dk/itu/boomdb/Statement.java` — sealed AST membership.
- `src/main/java/dk/itu/boomdb/SqlAstBuilder.java` — DELETE AST construction.
- `src/main/java/dk/itu/boomdb/SqlPrinter.java` — exhaustive DELETE printing.
- `src/main/java/dk/itu/boomdb/Binder.java` — shared predicate validation on pinned metadata.
- `src/main/java/dk/itu/boomdb/CatalogStore.java` — version publication, snapshots, migration, and retention.
- `src/main/java/dk/itu/boomdb/StorageEngine.java` — repeated COPY, copy-on-write DELETE, and version summaries.
- `src/main/java/dk/itu/boomdb/StorageSupport.java` — exclusive creation and flushing of immutable partitions.
- `src/main/java/dk/itu/boomdb/Planner.java` — caller-pinned metadata and leased standalone plans.
- `src/main/java/dk/itu/boomdb/ScanOperator.java` — standalone scan lease release.
- `src/main/java/dk/itu/boomdb/Executor.java` — statement-start leases and DELETE execution.
- `src/test/java/dk/itu/boomdb/SqlParserTest.java` — DELETE AST coverage.
- `src/test/java/dk/itu/boomdb/SqlPrinterTest.java` — DELETE normalization and round trips.
- `src/test/java/dk/itu/boomdb/BinderIT.java` — DELETE validation and pinned schema checks.
- `src/test/java/dk/itu/boomdb/CatalogStoreTest.java` — discovery, immutable metadata, retention, and failed publication.
- `src/test/java/dk/itu/boomdb/TransactionIT.java` — versions, rewrites, byte preservation, no-match behavior, restart, leases, summaries, and orphan invisibility.
- `src/test/java/dk/itu/boomdb/StorageEngineIT.java` — repeated COPY and fresh partition paths.
- `src/test/java/dk/itu/boomdb/EngineIT.java` — packaged launcher mutation script.
- `src/test/java/dk/itu/boomdb/ExperimentIT.java` — resolve fault-injection partition through catalog metadata.
- `engine` — executable exercise-compatible launcher.
- `scripts/partition_experiment.py` — latest finalized catalog discovery.
- `scripts/test_partition_experiment.py` — numeric discovery and compatibility checks.
- `docs/transactions-design.md` — precise commit, snapshot, retention, and no-match decisions.
- `docs/superpowers/plans/2026-10-22-exercise-7.md` — implementation and verification checklist.

## Validation

- Initial focused tests exposed missing DELETE parsing, missing version discovery,
  and the repeated-COPY restriction before those features were implemented.
- `mvn -B verify` — passed: 69 unit tests and 84 integration tests, zero failures,
  errors, or skips. Includes subprocess `./engine -f`, failed publication after
  partition writes, and restart visibility. Maven emitted dependency shading
  overlap warnings; the build and all tests succeeded.
- `python3 -m unittest discover -s scripts -p 'test_*.py'` — passed: eight tests.
- `bash -n engine engine.sh` — passed.
- `git diff --check` — passed.
- Independent code review — no blocking correctness findings. Unsupported atomic
  move failure was reviewed in code; no filesystem fault injection was added.

## Ownership checkpoint

- [ ] I reviewed this change and can explain its design, correctness, and trade-offs.
