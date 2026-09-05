# Exercise 2 Storage Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Implement the complete Exercise 2 persistent storage API, binary PAX partitions, catalog-backed pruning, required tests, logging, and runnable demo.

**Architecture:** `StorageEngine` coordinates API validation, catalog persistence, copying, scanning, statistics, and logging. A package-private `StorageSupport` owns the directly unit-tested storage algorithms, while `CatalogStore` owns Jackson JSON I/O and its package-private metadata records.

**Tech Stack:** Java 25, Maven 3.9+, Jackson Databind 2.22.2, SLF4J/Log4j2, JUnit 6.1.2, Maven Surefire/Failsafe 3.5.6.

**Spec:** `docs/storage-design.md` and `exercise-descriptions/Exercise2.md`

## Global Constraints

- All persistent state lives below the `Path` supplied to `StorageEngine`.
- Data uses the project's own one-file-per-partition binary PAX format; no external data-format library is allowed.
- Catalogs use one JSON file per table under `catalogs/`; partitions live under `data/`.
- Partition files use `BDBP` magic, version 1, and little-endian framing and values.
- CSV input is headerless ASCII with no quoted fields or embedded commas.
- Public and protected Java APIs receive concise Javadoc.
- Git is read-only throughout this implementation; review uses `git diff` and no commit, tag, checkout, merge, push, or staging operation.

---

### Task 1: Public value types and build configuration

**Files:**
- Create: `src/main/java/dk/itu/boomdb/ColumnType.java`
- Create: `src/main/java/dk/itu/boomdb/ColumnSpec.java`
- Create: `src/main/java/dk/itu/boomdb/Comparison.java`
- Create: `src/main/java/dk/itu/boomdb/ScanStats.java`
- Modify: `pom.xml`

**Interfaces:**
- Produces: `ColumnType { STRING, LONG, DOUBLE }`, `ColumnSpec(String name, ColumnType type)`, `Comparison { EQUALS, LESS_THAN, GREATER_THAN }`, and `ScanStats(int partitionsTotal, int partitionsRead, int partitionsPruned)`.
- Produces: Jackson Databind available to production code and Failsafe bound to `integration-test` and `verify`.

- [x] Add the four documented public types with exactly the names and record components required by Exercise 2.
- [x] Add `com.fasterxml.jackson.core:jackson-databind:2.22.2` and `maven-failsafe-plugin:3.5.6`.
- [x] Run `mvn -q -DskipTests compile`; expect exit code 0.
- [x] Inspect `git diff --check`; expect no whitespace errors.

### Task 2: Storage algorithms through unit tests

**Files:**
- Create: `src/main/java/dk/itu/boomdb/StorageSupport.java`
- Create: `src/test/java/dk/itu/boomdb/StorageSupportTest.java`

**Interfaces:**
- Produces: package-private `record MinMax(Object min, Object max)`.
- Produces: `StorageSupport.encodeValue(ColumnType, Object)`, `decodeValue(ColumnType, ByteBuffer)`, `minMax(ColumnType, List<Object>)`, `shouldPrune(ColumnType, Comparison, Object, Object, Object)`, `matches(ColumnType, Comparison, Object, Object)`, `parseCsvLine(Path, int, String, List<ColumnSpec>)`, `writePartition(Path, List<ColumnSpec>, List<Object[]>)`, and `readPartition(Path, List<ColumnSpec>)`.

- [x] Write unit tests with literal expectations for STRING, LONG, and DOUBLE encoding round trips.
- [x] Run `mvn -q -Dtest=StorageSupportTest test`; expect failures because `StorageSupport` is absent.
- [x] Implement little-endian primitive and ASCII length-prefixed string encoding; reject values whose exact Java class does not match the column type.
- [x] Run the encoding tests; expect all to pass.
- [x] Add min/max tests for multiple values, a singleton, and negative numeric values, then run them and observe failures before implementing comparison-based `minMax`.
- [x] Add six pruning tests: one pruned and one read case for each comparison, then run them and observe failures before implementing exact boundary-aware pruning.
- [x] Add successful, malformed-value, and wrong-field-count CSV tests. Both error tests must assert that the message contains the source path and line number. Run them and observe failures before implementing `split(",", -1)` and typed parsing.
- [x] Add a binary partition round-trip test that validates schema-order rows and exact Java value types. Run it and observe failure before implementing the versioned PAX header, offset/length table, column chunks, and format validation.
- [x] Run `mvn -q -Dtest=StorageSupportTest test`; expect all unit tests to pass.

### Task 3: JSON catalog and table lifecycle

**Files:**
- Create: `src/main/java/dk/itu/boomdb/CatalogStore.java`
- Create: `src/main/java/dk/itu/boomdb/StorageEngine.java`
- Create: `src/test/java/dk/itu/boomdb/StorageEngineIT.java`

**Interfaces:**
- Produces: package-private `TableCatalog`, `PartitionMetadata`, and `ColumnStatistics` records, with statistic values stored as text.
- Produces: public `StorageEngine(Path)`, `StorageEngine(Path, int)`, `createTable(String, List<ColumnSpec>)`, and `lastScanStats()`.

- [x] Write integration tests for schema persistence after reconstructing the engine, duplicate-table rejection, empty-schema rejection, duplicate-column rejection, and a non-positive partition-size rejection.
- [x] Run `mvn -q -Dit.test=StorageEngineIT failsafe:integration-test`; expect failures because `StorageEngine` is absent.
- [x] Implement `CatalogStore` with normalized path containment checks, Jackson record serialization, per-table JSON files, and replacement of a temporary catalog file on save.
- [x] Implement both constructors, directory creation, table lookup, schema validation, initial empty catalog persistence, zeroed scan stats, and one summary log line for `createTable`.
- [x] Run the focused integration tests; expect them to pass.

### Task 4: COPY, partitions, statistics, and restart

**Files:**
- Modify: `src/main/java/dk/itu/boomdb/StorageEngine.java`
- Modify: `src/test/java/dk/itu/boomdb/StorageEngineIT.java`
- Create: `src/test/resources/trips.csv`

**Interfaces:**
- Produces: `StorageEngine.copyFile(String, String)` writing `data/<table>-<partition>.bin` and publishing ordered `PartitionMetadata` entries only after the CSV parses successfully.

- [x] Add the eight golden rows to `src/test/resources/trips.csv`.
- [x] Write integration tests for a complete round trip, four partitions at size two, catalog min/max contents, copy to an unknown table, a second copy, malformed typed input, and wrong field count. Error assertions must include file and line for CSV failures.
- [x] Run the focused integration tests; expect failures because `copyFile` is absent.
- [x] Implement all-input validation before writes, fixed-size row grouping, per-column min/max generation, partition file writing, ordered catalog metadata, second-copy rejection, individual min/max log lines, and a COPY summary log line.
- [x] Run the focused integration tests; expect them to pass.

### Task 5: SELECT, pruning, and persistence

**Files:**
- Modify: `src/main/java/dk/itu/boomdb/StorageEngine.java`
- Modify: `src/test/java/dk/itu/boomdb/StorageEngineIT.java`

**Interfaces:**
- Produces: `StorageEngine.select(String, String, Comparison, Object)` and updated `lastScanStats()`.

- [x] Write parameterized or table-driven integration coverage for all nine comparison/type combinations using literal expected rows, plus empty results, unknown table, unknown column, and exact constant-type mismatch.
- [x] Write a pruning test using distance-sorted rows and partition size two; assert exact output plus at least two pruned partitions and `total = read + pruned`.
- [x] Write a data-restart test in which engine B selects rows copied by engine A.
- [x] Run the focused integration tests; expect failures because `select` is absent.
- [x] Implement catalog-stat decoding, exact type validation, min/max pruning before file access, partition reads only for non-pruned partitions, row filtering in disk order, per-partition PRUNED/READ log lines, scan-stat replacement, and SELECT summary logging.
- [x] Run the focused integration tests; expect them to pass.

### Task 6: Runnable demo and final validation

**Files:**
- Modify: `src/main/java/dk/itu/boomdb/Engine.java`
- Delete: `src/test/java/dk/itu/boomdb/EngineTest.java`
- Create: `docs/ai-usage/2026-09-03-exercise-2-storage-engine.md`

**Interfaces:**
- Produces: `Engine.main(String[])` creating a temporary engine, copying `src/test/resources/trips.csv`, and printing the three required query result groups.

- [x] Replace the Exercise 1 team-name demo with the three golden Exercise 2 queries and readable row output.
- [x] Run `mvn -q compile exec:java`; expect labels for `distance > 100`, `city = Copenhagen`, and `price < 50.0`, with respectively 4, 3, and 2 rows.
- [x] Run `mvn test`; expect unit tests only and zero failures.
- [x] Run `mvn -B verify`; expect unit and integration tests and `BUILD SUCCESS`.
- [x] Run `git diff --check` and review `git status --short` plus the complete diff.
- [x] Create exactly one sanitized AI usage log containing the actual changed files and validation outcomes; leave its ownership checkbox unchecked.
