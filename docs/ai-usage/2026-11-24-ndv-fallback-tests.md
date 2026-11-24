# Code change: Maximum-row NDV fallback coverage

- Date: 2026-11-24
- Contributor: AntohaY
- Assistance: AI-assisted
- AI tool/model: Codex

## Request summary

Update tests and documentation for the contributor's change to estimate missing
NDV using the largest partition row count, and run Maven verification.

## Change and assistance summary

The missing-NDV fallback now uses the largest partition row count across the
pinned table. Base row estimates continue to sum partition row counts. When
all nonempty partitions have NDV, the existing maximum-NDV merge still applies.

AI assistance added regression cases for entirely missing statistics, mixed
known and missing statistics where the largest partition has known NDV, and
empty partitions that must not trigger the fallback. The catalog round-trip
test now expects four distinct values as its fallback for partitions containing
four and one rows. Javadoc and the design document explain the behavior and
its trade-offs, replacing conversational source comments with concise Javadoc.

The fallback is simple but may overestimate or underestimate true NDV. A
smaller denominator can increase estimated equality matches; improved accuracy
has not been measured.

## Changed files

- `src/main/java/dk/itu/boomdb/CardinalityEstimator.java` — contributor's fallback change and updated method documentation.
- `src/test/java/dk/itu/boomdb/CardinalityEstimatorTest.java` — fallback regression coverage and unchanged summed base-row behavior.
- `src/test/java/dk/itu/boomdb/CatalogStoreTest.java` — updated mixed-catalog fallback expectation.
- `docs/optimizer-design.md` — fallback policy, example, and estimation limitations.

## Validation

- `mvn -B "-Dtest=CardinalityEstimatorTest,CatalogStoreTest" test` — 22 tests passed, with zero failures, errors, or skips.
- `mvn -B verify` — 152 unit tests and 124 integration tests passed, with zero failures, errors, or skips.
- `git diff --check` — passed.

## Ownership checkpoint

- [ ] I reviewed this change and can explain its design, correctness, and trade-offs.
