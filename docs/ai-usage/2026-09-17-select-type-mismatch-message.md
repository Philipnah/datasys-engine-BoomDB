# Code change: Contextual SELECT type-mismatch message

- Date: 2026-09-17
- Contributor: Anton Yakovenko
- Assistance: AI-assisted
- AI tool/model: Codex / GPT-5

## Request summary

Make type-mismatch errors identify the SELECT table, predicate column, expected column type, and actual Java constant type.

## Change and assistance summary

Updated the shared constant-type validator to receive the table and column names from both the binder and storage SELECT paths. It now emits a descriptive message such as `SELECT on "trips": column "distance" is LONG but constant is String`; the existing storage failure logger records that message unchanged. Added an integration assertion for the binder-visible error.

## Changed files

- `src/main/java/dk/itu/boomdb/StorageEngine.java` — creates contextual type-mismatch errors.
- `src/main/java/dk/itu/boomdb/Binder.java` — passes SELECT context to shared validation.
- `src/test/java/dk/itu/boomdb/BinderIT.java` — asserts the contextual error contract.
- `docs/ai-usage/2026-09-17-select-type-mismatch-message.md` — records this AI-assisted change.

## Validation

- `mvn -Dit.test=BinderIT verify` — fails as expected before implementation, confirming the former generic error text.
- `mvn verify` — passes: 45 unit tests and 33 integration tests.

## Ownership checkpoint

- [X] I reviewed this change and can explain its design, correctness, and trade-offs.
