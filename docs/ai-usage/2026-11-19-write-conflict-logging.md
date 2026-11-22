# Code change: Include partition and resolution details in write conflict logs

- Date: 2026-11-19
- Contributor: Philip Han
- Assistance: AI-assisted
- AI tool/model: Codex (GPT-6)

## Request summary

Make write conflicts easier to diagnose by recording the overlapping
partitions, the two write operations, and how the engine handles the conflict.

## Change and assistance summary

The catalog detects overlap using immutable filenames and reports partition
IDs from the pinned base version. The DELETE retry loop logs the conflict
because it knows whether it can retry from a fresh snapshot or must abort
after exhausting the retry budget. Both operations are DELETE because COPY
only appends partitions. The log separates partition IDs with pipes to keep
the record compatible with the existing CSV layout. AI assistance traced the
commit path, extended the concurrency tests, and implemented the diagnostics.

## Changed files

- `src/main/java/dk/itu/boomdb/CatalogStore.java` — attach the exact partition overlap and catalog versions to the conflict.
- `src/main/java/dk/itu/boomdb/ConflictException.java` — carry catalog conflict details to the retry loop.
- `src/main/java/dk/itu/boomdb/StorageEngine.java` — log both operation types and the resolution selected for the attempt.
- `src/test/java/dk/itu/boomdb/WriteConcurrencyIT.java` — verify partition overlap, operation labels, and retry and abort resolutions for SQL and direct API calls.

## Validation

- Focused regression cases — four failed on the missing fields before implementation and passed afterward.
- `mvn -B -Dtest=WriteConcurrencyIT,CatalogStoreTest test` — all 44 tests passed.
- `mvn -B verify` — all 130 unit tests and 115 integration tests passed.
- `git diff --check` — passed.

## Ownership checkpoint

- [ ] I reviewed this change and can explain its design, correctness, and trade-offs.
