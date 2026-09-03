# Code change: Exercise 2 storage engine

- Date: 2026-09-03
- Contributor: Philip Han
- Assistance: AI-assisted
- AI tool/model: OpenAI Codex (GPT-5)

## Request summary

Complete Exercise 2 from the approved storage design, including persistent catalogs, a custom partitioned binary format, typed CSV loading, min/max pruning, logging, tests, and the runnable golden-data demonstration.

## Change and assistance summary

The AI translated the contributor-approved design into a test-first Java implementation. The result persists one Jackson JSON catalog per table and one little-endian PAX binary file per partition, restores schemas and data after restart, validates typed headerless CSV input, records per-column min/max values, prunes partitions before opening their data files, exposes scan statistics, and emits seven-field CSV logs. Unit and integration tests cover the required behavior, and the entry point prints the three golden query results.

## Changed files

- `docs/storage-design.md` — resolves the partition-file layout and documents exact framing and API choices.
- `docs/superpowers/plans/2026-09-03-exercise-2-storage-engine.md` — records the test-first implementation plan.
- `pom.xml` — adds Jackson Databind and configures Maven Failsafe.
- `src/main/java/dk/itu/boomdb/ColumnType.java` — defines supported value types.
- `src/main/java/dk/itu/boomdb/ColumnSpec.java` — defines ordered schema columns.
- `src/main/java/dk/itu/boomdb/Comparison.java` — defines supported predicates.
- `src/main/java/dk/itu/boomdb/ScanStats.java` — exposes pruning statistics.
- `src/main/java/dk/itu/boomdb/CatalogStore.java` — persists per-table JSON catalogs and partition metadata.
- `src/main/java/dk/itu/boomdb/StorageSupport.java` — implements typed values, CSV parsing, statistics, predicates, and PAX partition I/O.
- `src/main/java/dk/itu/boomdb/StorageEngine.java` — implements the persistent CREATE, COPY, and SELECT API.
- `src/main/java/dk/itu/boomdb/Engine.java` — runs and prints the golden-data demonstration.
- `src/test/java/dk/itu/boomdb/StorageSupportTest.java` — covers storage algorithms as unit tests.
- `src/test/java/dk/itu/boomdb/StorageEngineIT.java` — covers the public storage API end to end.
- `src/test/java/dk/itu/boomdb/EngineIT.java` — verifies the runnable demonstration output.
- `src/test/java/dk/itu/boomdb/EngineTest.java` — removes the superseded Exercise 1 team-name test.
- `src/test/resources/trips.csv` — supplies the required eight-row golden data.

## Validation

- `mvn test` — BUILD SUCCESS; 22 unit tests passed with no failures or errors.
- `mvn -B verify` — BUILD SUCCESS; 22 unit tests and 25 integration tests passed with no failures or errors.
- `mvn -q compile exec:java` — exited successfully and printed the required result groups with 4, 3, and 2 rows.
- `mvn -q javadoc:javadoc` — exited successfully with no Javadoc errors.
- `git diff --check` — exited successfully with no whitespace errors.

## Ownership checkpoint

- [ ] I reviewed this change and can explain its design, correctness, and trade-offs.
