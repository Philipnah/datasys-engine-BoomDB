# Code change: Concurrent logging, SELECT statistics, and race coverage

- Date: 2026-11-01
- Contributor: Philip Han
- Assistance: AI-assisted
- AI tool/model: Codex (GPT-6); independent review by GPT-6 Astra

## Request summary

Fix missing worker logging context and incorrect concurrent SELECT statistics,
and add deterministic threaded writer, recovery, and snapshot tests. Preserve
the existing partition rebase and automatic DELETE retry behavior.

## Change and assistance summary

The SQL executor and direct CREATE, COPY, DELETE, and SELECT entry points now
fill missing logging context before parsing, validation, or execution can log.
A worker without a session uses the storage engine's construction-time session;
a direct API call without a statement number uses zero. Existing caller context
is preserved. This keeps worker records ingestible with the course's numeric
statement-number and thread-ID columns, including failure records. Callers
remain responsible for explicitly supplied context and standalone parser or
planner calls outside these execution entry points.

Latest scan statistics use Java's `ThreadLocal`, retaining the existing public
accessor with documented calling-thread semantics. SQL and direct SELECTs
capture their immutable statistics immediately after planning, before draining
the operator tree. Concurrent queries therefore cannot replace each other's
summary counts. This adds a small statistics value per engine per active caller
thread instead of serializing reads or introducing a new planning API.

New tests pause real worker threads using the existing commit seam and timed
latches. They check DELETE/COPY rebasing, overlapping DELETE retries, exact
winner state, both writer thread IDs, retry exhaustion with one terminal ERROR,
successful explicit rerun, and restart results. A separate test opens a plan,
commits a DELETE, then verifies the exact original rows while draining the
pinned scan. Statistics tests observe real filter-close logging to force the
interleaving without adding another production seam. Existing tests and
comments were retained; the scan-statistics Javadoc now describes thread scope.

AI assistance covered regression reproduction, implementation, deterministic
tests, validation, independent review, and this entry. Independent read-only
review found no substantive or minor issues. No Git mutation commands were run;
the changes and this entry are left for contributor review.

## Changed files

- `src/main/java/dk/itu/boomdb/StorageEngine.java` — worker logging context, thread-local statistics, and direct SELECT summary capture.
- `src/main/java/dk/itu/boomdb/Executor.java` — initialize SQL logging context before parsing and retain each SELECT's statistics.
- `src/test/java/dk/itu/boomdb/WriteConcurrencyIT.java` — worker-log ingestion, threaded writer interleavings, retry exhaustion, and explicit recovery.
- `src/test/java/dk/itu/boomdb/ExecutorIT.java` — deterministic concurrent SELECT summary and calling-thread statistics checks.
- `src/test/java/dk/itu/boomdb/VersioningIT.java` — open-plan snapshot read across DELETE.
- `docs/superpowers/plans/2026-11-01-exercise-8-review-fixes.md` — scoped implementation and validation record.
- `docs/ai-usage/2026-11-01-exercise-8-review-fixes.md` — this learning log.

## Validation

- `mvn -B -Dtest=WriteConcurrencyIT#freshWorkerLogsRemainIngestible test` — seven expected assertion failures from empty worker session context before implementation.
- `mvn -B -Dtest=ExecutorIT#concurrentSelectsLogTheirOwnScanStatistics test` — two expected assertion failures from incorrect partition counts before implementation.
- `mvn -B -Dtest=WriteConcurrencyIT,ExecutorIT test` — 37 tests passed after the production fixes.
- `mvn -B -Dtest=WriteConcurrencyIT,VersioningIT,ExecutorIT test` — 49 tests passed after adding writer and snapshot coverage.
- `mvn -B verify` — 77 unit tests and 102 integration tests passed with no failures, errors, or skips, including the packaged launcher.
- `git diff --check` — passed.

## Ownership checkpoint

- [ ] I reviewed this change and can explain its design, correctness, and trade-offs.
