# Exercise 8 review fixes implementation plan

> **For agentic workers:** Execute the tasks in this checkout with test-driven development. Git is read-only: do not create worktrees, stage, commit, merge, tag, or push.

**Goal:** Make concurrent execution logs ingestible, keep SELECT statistics accurate, and cover deterministic writer races and snapshot reads.

**Architecture:** Fill missing thread logging context at the SQL and direct API boundaries, using the engine's session as the fallback. Keep the existing scan-statistics API with thread-local storage and capture each statement's statistics before draining its plan. Exercise existing partition rebasing and bounded DELETE retries through real worker threads and timed latches.

**Tech Stack:** Java 25+, Maven, JUnit 6, SLF4J/Log4j2, Java concurrency utilities.

**Spec:** The user's accepted review findings 2–4. Automatic retries are explicitly retained; Exercise 8's abort-on-first-overlap behavior is outside this change.

## Global constraints

- Preserve existing comments and unrelated changes.
- Keep partition rebasing and the three-retry DELETE budget unchanged.
- Use no sleeps or unbounded latch/future waits in concurrency tests.
- Preserve caller-provided logging context and public execution APIs.
- Add one learning-log entry under `docs/ai-usage/` after validation.

## Review focus

- Fresh worker threads, including validation and SQL parsing failures, produce ingestible records.
- Existing caller session and statement context remains intact.
- Both SQL and direct API SELECT summaries use their own partition counts.
- A failing assertion releases parked workers; latches and futures have timeouts.
- An exhausted DELETE preserves only committed winners and succeeds when explicitly rerun.

### Task 1: Worker logging context

**Files:** `StorageEngine.java`, `Executor.java`, `WriteConcurrencyIT.java`.

**Interfaces:** Keep existing SQL and storage entry points. Make `initializeLoggingContext()` available to Executor; missing session IDs fall back to the engine's session and missing direct-API statement numbers become zero.

- [x] Add fresh-worker coverage for direct CREATE/COPY/DELETE/SELECT and SQL execution/parsing failures; ingest captured records using the course schema.
- [x] Run the new `freshWorkerLogsRemainIngestible` cases; all seven failed on missing session context before implementation.
- [x] Initialize missing context before each execution entry point can log.
- [x] Rerun the focused tests; all 37 logging/statistics and existing writer cases passed.

### Task 2: SELECT statistics isolation

**Files:** `StorageEngine.java`, `Executor.java`, `ExecutorIT.java`.

**Interfaces:** Preserve `lastScanStats()` and `recordScanStats(ScanStats)` with calling-thread semantics. Capture a statement's immutable `ScanStats` immediately after planning.

- [x] Add a timed-latch test pausing a selective SELECT at filter close while another SELECT plans a full scan, through both SQL and direct APIs.
- [x] Run the new `concurrentSelectsLogTheirOwnScanStatistics` cases; both failed because the selective summary reported the full scan's counts.
- [x] Store scan statistics in `ThreadLocal<ScanStats>` and capture them before draining each plan; document calling-thread semantics.
- [x] Rerun the focused tests; both entry points report correct rows and partition counts.

### Task 3: Deterministic concurrency and recovery coverage

**Files:** `WriteConcurrencyIT.java`, `VersioningIT.java`.

**Interfaces:** Use the existing volatile `beforeCommit` seam and public SQL/direct APIs; leave conflict handling unchanged.

- [x] Add timed-latch DELETE/COPY and overlapping DELETE/DELETE races, checking rows, versions, worker IDs, and retry logs.
- [x] Add a threaded retry-exhaustion test checking the terminal ERROR, exact winner state, explicit rerun, and restart.
- [x] Add open-plan → DELETE → drain coverage asserting the pinned rows exactly.
- [x] Run `mvn -B -Dtest=WriteConcurrencyIT,VersioningIT,ExecutorIT test`; all 49 tests passed.
- [x] Run `mvn -B verify` and `git diff --check`; 77 unit and 102 integration tests passed with no failures, errors, or skips; whitespace check passed.
- [x] Request an independent review of the uncommitted changes, address substantive findings, and create the learning log with actual validation results.

## Review outcome

Independent read-only review found no critical, important, or minor issues. Existing SQL statement-number reset behavior, standalone parser/planner logging, malformed caller-supplied MDC values, and shared rolling-log test infrastructure remain outside these execution-boundary fixes. Automatic retry and rebase policy remains explicitly unchanged. Git was used only for inspection.
