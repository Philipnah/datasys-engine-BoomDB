# Code change: Join measurement completion and catalog compatibility

- Date: 2026-11-23
- Contributor: AntohaY
- Assistance: AI-assisted
- AI tool/model: Codex (GPT-6.2 Sol)

## Request summary

Address review findings about incomplete join counts and catalog read errors.
Support moving an existing database between Exercise 10 and the optimizer
without discarding committed data.

## Change and assistance summary

Join summaries now distinguish fully drained, successfully closed inputs
from failed or partial execution. The benchmark can filter for
`completed=true`. Catalog read errors include the affected file.

The optimizer now accepts catalogs without NDV and uses the table row count
as its estimate of distinct keys for columns with missing statistics. The
design documents rollback using a pre-upgrade backup or by removing NDV
from all catalogs in an offline copy. I reproduced the review findings,
added regression assertions, implemented the fixes, and tested the format
round trip using both engine builds.

## Changed files

- `src/main/java/dk/itu/boomdb/HashJoinOperator.java` — identify completed measurements.
- `src/main/java/dk/itu/boomdb/CatalogStore.java` — accept optional NDV and diagnose reads by file.
- `src/main/java/dk/itu/boomdb/CardinalityEstimator.java` — estimate when NDV is missing.
- `src/test/java/dk/itu/boomdb/CatalogStoreTest.java` — cover old and mixed catalogs and file diagnostics.
- `src/test/java/dk/itu/boomdb/Exercise10HashJoinTest.java` — verify successful, partial, and failed summaries.
- `src/test/java/dk/itu/boomdb/Exercise11OptimizerIT.java` — check completion in successful SQL runs.
- `src/test/java/dk/itu/boomdb/DeleteIT.java`, `src/test/java/dk/itu/boomdb/GreedyJoinOrderTest.java`, `src/test/java/dk/itu/boomdb/StorageEngineIT.java` — adapt NDV fixture literals to the optional type.
- `docs/optimizer-design.md` — explain the manual version switch and benchmark filter.

## Validation

- Focused JUnit tests reproduced the missing completion field, rejected pre-NDV catalogs, and directory-only diagnostics before the fixes.
- `mvn -B "-Dtest=Exercise10HashJoinTest,CatalogStoreTest,CardinalityEstimatorTest,PartitionNdvTest" test` — 33 passed.
- `mvn -B verify` — 149 unit tests and 124 integration tests passed with zero failures, errors, or skips.
- Packaged engine round trip from Exercise 10 to the optimizer, back to Exercise 10 on a copy with NDV removed from both catalog layouts, and forward again — passed with reads and writes across both versions.

## Ownership checkpoint

- [ ] I reviewed this change and can explain its design, correctness, and trade-offs.
