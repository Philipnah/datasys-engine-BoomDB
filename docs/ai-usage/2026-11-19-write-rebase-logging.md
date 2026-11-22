# Code change: Log successful write rebases

- Date: 2026-11-19
- Contributor: Philip Han
- Assistance: AI-assisted
- AI tool/model: Codex (GPT-6)

## Request summary

Make it possible to tell from the engine log when a concurrent write
committed by rebasing onto a newer catalog version.

## Change and assistance summary

The catalog emits an INFO-level `WRITE_REBASE` event after it publishes a
merged version. The event identifies the rebased statement, table, base
version, version it rebased onto, and newly committed version. The catalog
already knows whether the writer has removed any original files, which
distinguishes a committed DELETE from an append-only COPY. AI assistance
traced the commit path, extended the concurrency checks, and added the event.

## Changed files

- `src/main/java/dk/itu/boomdb/CatalogStore.java` — log successful rebases after publication.
- `src/test/java/dk/itu/boomdb/WriteConcurrencyIT.java` — check the event for disjoint writes and its absence on conflicts and failed publication.

## Validation

- `mvn -B -Dtest=WriteConcurrencyIT test` — five cases failed on the missing rebase event before the change.
- `mvn -B verify` — all 130 unit tests and 115 integration tests passed.
- `git diff --check` — passed.

## Ownership checkpoint

- [ ] I reviewed this change and can explain its design, correctness, and trade-offs.
