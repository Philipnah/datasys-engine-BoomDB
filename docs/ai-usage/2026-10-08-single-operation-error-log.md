# Code change: One error record per failed storage operation

- Date: 2026-10-08
- Contributor: anton.yakovenko
- Assistance: AI-assisted
- AI tool/model: Codex / GPT-6

## Request summary

Prevent duplicate error records when a missing table causes a COPY or SELECT
storage API call to fail. Preserve the centralized operation-level failure log.

## Change and assistance summary

Removed error logging from `requireTable`, leaving the exception type and message
unchanged. The enclosing COPY or SELECT catch block writes the failure once via
`logFailure`, retaining the table, operation, outcome, error message, and duration.
Added a comment explaining logging ownership. Schema and catalog lookups now only
propagate the exception; callers outside those storage operations own any failure
logging they require.

AI assistance traced the call paths, strengthened the existing integration test
to count all matching ERROR records rather than merely find an operation record,
implemented the focused fix, and ran validation. No dependencies or logging flags
were added.

## Changed files

- `src/main/java/dk/itu/boomdb/StorageEngine.java` — delegate failure logging to the enclosing operation.
- `src/test/java/dk/itu/boomdb/StorageEngineIT.java` — require exactly one seven-field CSV error record for each failed CREATE, COPY, and SELECT call.
- `docs/ai-usage/2026-10-08-single-operation-error-log.md` — record assistance and validation.

## Validation

- `mvn -B -Dtest=StorageEngineIT#failedApiCallsWriteExactlyOneSevenFieldCsvErrorLogLine test` before the fix — failed as expected: COPY produced two records instead of one; console output also showed duplicate SELECT errors.
- The same command after the fix — passed, one test with no failures or errors.
- `mvn -B verify` — passed, 52 unit tests and 45 integration tests with no failures, errors, or skips. Maven Shade reported overlapping-resource and module-encapsulation warnings.
- `git diff --check` — passed.

## Ownership checkpoint

- [ ] I reviewed this change and can explain its design, correctness, and trade-offs.
