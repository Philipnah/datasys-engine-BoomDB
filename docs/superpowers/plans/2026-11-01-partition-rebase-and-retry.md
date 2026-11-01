# Partition rebase and DELETE retry implementation plan

> **For agentic workers:** Use superpowers:executing-plans for this plan. Git is read-only; use the current checkout without creating branches, worktrees, or commits.

**Goal:** Rebase disjoint partition changes and automatically retry overlapping DELETEs up to three times, logging conflicts and retries.

**Architecture:** Derive partition additions and removals from a writer's pinned base and prepared catalog. Within the existing catalog monitor, reject overlapping removals or apply the delta to the latest catalog. A shared storage DELETE coordinator releases failed snapshots and retries with a newly bound snapshot; SQL routes through it.

**Tech Stack:** Java 25+, Maven 3.9+, JUnit, SLF4J, existing immutable PAX partitions and JSON catalogs.

**Spec:** The design agreed in this chat: disjoint writes rebase without re-execution; overlapping DELETEs retry automatically with three retries (four attempts total); conflicts and retries are logged. A retry evaluates the latest snapshot, including concurrent inserts. No-match DELETEs keep the current behavior of not publishing a version.

## Global constraints

- Git is a read-only source; preserve existing comments and unrelated edits.
- Keep the existing atomic catalog publication and snapshot retention mechanisms.
- Use immutable filenames as partition identity; concurrent numeric IDs may collide.
- Failed attempts' partition files remain harmless orphans under the existing policy.
- Do not retry validation or I/O failures; terminal statement errors are logged once.

## Review focus

- A stale COPY must not resurrect a partition deleted by the winner.
- A DELETE removing a whole partition must still participate in conflict detection.
- A long-running writer must rebase across more versions than retention keeps.
- A retry can become a no-op; only a successful attempt contributes deleted-row counts.
- The fourth conflict ends execution without a fifth attempt or fourth retry log.

### Task 1: Catalog delta publication

**Files:** `src/main/java/dk/itu/boomdb/CatalogStore.java`, `src/test/java/dk/itu/boomdb/CatalogStoreTest.java`.

**Interfaces:** Retain `commit(TableCatalog base, List<PartitionMetadata> partitions)` returning the actual committed version; throw `ConflictException` only for overlapping removals. Match files by `fileName`; allocate noncolliding numeric IDs for rebased additions.

- [x] Add tests for copy/copy, copy/delete in both directions, disjoint deletes, overlapping deletes, complete removal, multiple intervening versions, and numeric ID collisions.
- [x] Run `mvn -B -Dtest=CatalogStoreTest test`; observe new rebase tests failing on the version mismatch guard.
- [x] Implement the delta check and merge under the existing monitor, preserving the unchanged-base path and atomic save. Log `WRITE_CONFLICT` with table and competing versions before rejection.
- [x] Run the catalog tests and confirm they pass.

### Task 2: Automatic DELETE retries and race integration

**Files:** `src/main/java/dk/itu/boomdb/StorageEngine.java`, `Executor.java`, `ConflictException.java`, `src/test/java/dk/itu/boomdb/VersioningIT.java`, `WriteConcurrencyIT.java`.

**Interfaces:** Add package-private `long delete(DeleteStatement statement)` to own snapshot acquisition, binding, and up to four attempts. Keep `delete(DeleteStatement, TableCatalog)` as one prepared attempt. Route both SQL and the public DELETE API through the coordinator.

- [x] Update the active DELETE/COPY assertion to expect automatic rebase and both effects. Preserve existing comments and commented-out scenarios.
- [x] Add real deterministic race tests for concurrent copies, disjoint deletes, overlapping deletes through SQL/direct API, same-row no-op retry, success on the third retry, retry exhaustion, non-conflict failures, snapshot release, restart results, and log correlation.
- [x] Run `mvn -B -Dtest=WriteConcurrencyIT,VersioningIT test`; observe the retry tests fail on the first conflict.
- [x] Implement fresh-snapshot retries outside the catalog monitor. Log `WRITE_RETRY` with table, acquired snapshot version, and one-based attempt; throw after four conflicting attempts.
- [x] Update API documentation for the changed conflict contract.
- [x] Run `mvn -B -Dtest=CatalogStoreTest,WriteConcurrencyIT,VersioningIT test` and confirm success.

### Task 3: Review and final validation

- [x] Review the full diff for lost updates, row resurrection, snapshot leaks, duplicate failure logs, and unintended comment removal. Independent review found no correctness blockers; replaced obsolete COPY exception documentation with explicit rebase semantics.
- [x] Run `mvn -B verify` and `git diff --check`; inspect actual results.
- [x] Add one sanitized entry under `docs/ai-usage/` with changes, trade-offs, and measured validation. Leave ownership unchecked.
- [x] Report the behavior and validation without changing Git state.
