# Code change: Log SELECT validation failures at the operation boundary

- Date: 2026-10-08
- Contributor: anton.yakovenko
- Assistance: AI-assisted
- AI tool/model: Codex / GPT-6

## Request summary

Implement the reviewed proposal to log SELECT failures caused by unavailable
tables or literals with an incorrect type, covering SQL and direct storage API
entry points without duplicate error records.

## Change and assistance summary

The Executor now catches SELECT runtime failures around binding, planning, and
execution, writes one contextual ERROR record, and rethrows the original
exception. Sanitizing table names and error messages preserves the seven-field
CSV log format. Logging occurs before the existing statement-number reset.

Removed helper-level logging from `StorageEngine.requireTable` and
`StorageSupport.requireValueType`, retaining their validation and exceptions.
Direct storage API calls continue using `StorageEngine.logFailure`. SQL SELECT
executes through Planner and operators rather than `StorageEngine.select`, so
these two operation-level logs do not overlap. Comments and Executor Javadoc
explain logging ownership.

AI assistance traced the validation paths, implemented the approved proposal,
and added a four-case integration test for missing tables and wrong literal
types through both entry points. The test checks error count, operation context,
CSV field count, exception messages, statement numbers, and that later SQL
statements do not execute. The existing storage logging test now also rejects
duplicate CREATE, COPY, and SELECT error records.

The scope remains SELECT failures. SQL COPY binding errors and standalone schema
lookups do not receive a new operation-level log in this change; their callers
would need separate logging ownership. No logging flags or dependencies were
added.

## Changed files

- `src/main/java/dk/itu/boomdb/Executor.java` — log SQL SELECT failures across binding, planning, and execution.
- `src/main/java/dk/itu/boomdb/StorageEngine.java` — delegate missing-table logging to the operation boundary.
- `src/main/java/dk/itu/boomdb/StorageSupport.java` — remove helper-level type-error logging and unused logger declarations.
- `src/test/java/dk/itu/boomdb/ExecutorIT.java` — cover both validation failures through SQL and direct storage calls.
- `src/test/java/dk/itu/boomdb/StorageEngineIT.java` — require one error record per failed storage operation.
- `docs/ai-usage/2026-10-08-select-validation-error-logging.md` — record assistance, design, and validation.

## Validation

- `mvn -B '-Dtest=ExecutorIT#selectValidationFailuresWriteExactlyOneContextualError,StorageEngineIT#failedApiCallsWriteExactlyOneSevenFieldCsvErrorLogLine' test` before implementation — five cases ran; four failed as expected, exposing the absent SQL type-error record, helper-level SQL missing-table record, and duplicate direct SELECT/COPY errors.
- The same focused command after implementation — all five cases passed.
- `mvn -B verify` — passed: 52 unit tests and 49 integration tests, with no failures, errors, or skips. Maven Shade emitted module-encapsulation and overlapping-resource warnings.
- `git diff --check` — passed.

## Ownership checkpoint

- [ ] I reviewed this change and can explain its design, correctness, and trade-offs.
