# Code change: Rebase partition changes and retry conflicting DELETEs

- Date: 2026-11-01
- Contributor: anton.yakovenko
- Assistance: AI-assisted
- AI tool/model: Codex (GPT-6); independent review by GPT-6 Astra

## Request summary

Allow writers with disjoint partition changes to rebase their prepared work
instead of aborting when a table version advances. Automatically retry DELETEs
that conflict on an original partition, with three retries after the initial
attempt. Log conflicts and retries while preserving the winner's changes.

## Change and assistance summary

Catalog commit derives removed originals and new files by comparing immutable
filenames in the pinned base and prepared partition list. If every removed
original remains in the latest catalog, commit applies that delta to the latest
partition list and publishes its next version. Carried stale partitions are
never reintroduced. Rebased additions receive numeric IDs above the latest
catalog's maximum to avoid collisions between concurrent writers. Validation,
merging, and atomic publication remain inside the existing store monitor.

When an original was already removed or rewritten, commit logs a WARN
`WRITE_CONFLICT` event with both versions and rejects the attempt. A shared
storage DELETE coordinator closes the failed snapshot, acquires a new one,
logs an INFO `WRITE_RETRY` event with its snapshot version and attempt number,
binds again, and reevaluates the predicate. SQL and direct API calls both use
this coordinator. Four conflicting attempts exhaust the budget; only that
terminal failure reaches the existing statement error logger. Validation and
publication failures are not retried. Logs retain the session and statement
context and sanitize table names for the existing CSV format.

Rebase reuses prepared files without rereading rows. An overlapping DELETE
retry recomputes against the newest snapshot, so it can affect intervening
inserts or finish as a no-op. Deleted-row counts come only from the successful
attempt. No-match DELETEs still do not publish another version. Failed
attempts' files remain invisible orphans, following the existing policy;
automatic retries can create more such files. Commit coordination remains
within the existing shared CatalogStore monitor.

AI assistance covered design discussion, planning, code changes, deterministic
JUnit race tests, verification, independent review, and this entry. Existing
commented-out tests were preserved; comments on the active race test were
updated to describe rebasing instead of manual recovery.

Independent review found no correctness blockers. Its documentation finding
was addressed by replacing the obsolete COPY conflict exception documentation
with prose describing concurrent rebase behavior. Review retained the existing
scope for coordination between separate store instances, orphan collection,
directory-fsync guarantees, worker-thread logging initialization, and malformed
catalog or exhausted numeric-ID handling; none of those mechanisms was expanded
by this change.

## Changed files

- `src/main/java/dk/itu/boomdb/CatalogStore.java` — derive and merge disjoint partition deltas, keep numeric IDs distinct, log overlapping conflicts.
- `src/main/java/dk/itu/boomdb/StorageEngine.java` — shared four-attempt DELETE coordinator, fresh-snapshot retries and retry logging, API documentation.
- `src/main/java/dk/itu/boomdb/Executor.java` — route SQL DELETE through the shared snapshot/retry coordinator without duplicate terminal error logging.
- `src/main/java/dk/itu/boomdb/ConflictException.java` — document the partition conflict and retry contract.
- `src/test/java/dk/itu/boomdb/CatalogStoreTest.java` — delta merging, no resurrection, complete removal, numeric IDs, conflict rejection, restart, and retention.
- `src/test/java/dk/itu/boomdb/VersioningIT.java` — expect DELETE/COPY rebasing and both statements' effects.
- `src/test/java/dk/itu/boomdb/WriteConcurrencyIT.java` — real concurrent copies, deterministic write interleavings, retry success/exhaustion, no-op retries, latest inserts, snapshot release, non-conflict failures, and correlated logs through both entry points.
- `docs/superpowers/plans/2026-11-01-partition-rebase-and-retry.md` — implementation and validation checklist.
- `docs/ai-usage/2026-11-01-partition-rebase-and-retry.md` — this learning log.

## Validation

- `mvn -B -Dtest=CatalogStoreTest test` — initially rejected six disjoint stale writes at the old version guard; all 15 catalog tests passed after delta publication was implemented.
- `mvn -B -Dtest=WriteConcurrencyIT,VersioningIT test` — initially exposed missing automatic retry: two retry-limit assertion failures and seven unhandled partition conflicts.
- `mvn -B -Dtest=CatalogStoreTest,WriteConcurrencyIT,VersioningIT test` — passed 36 tests with no failures, errors, or skips.
- `mvn -B verify` — passed 77 unit tests and 86 integration tests with no failures, errors, or skips, including the packaged launcher.
- `git diff --check` — passed after implementation and final documentation updates.

Git was used only for inspection. No branches, commits, worktrees, merges,
or pull requests were created.

## Ownership checkpoint

- [ ] I reviewed this change and can explain its design, correctness, and trade-offs.
