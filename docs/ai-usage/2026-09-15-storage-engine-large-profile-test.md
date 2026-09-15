# Code change: Selective PAX scan optimization and profiling workload

- Date: 2026-09-15
- Contributor: AntohaY
- Assistance: AI-assisted
- AI tool/model: Codex (GPT-5)

## Request summary

Avoid decoding and materializing non-matching PAX rows during `SELECT`, while keeping a local large-data workload for profiling the result.

## Change and assistance summary

Added a package-private selective partition reader. It validates the existing header and
directory, scans the predicate chunk first, returns immediately when it finds no matches,
and reads non-predicate chunks only after matches are known while decoding values only for
matching rows. The complete partition reader remains available for round-trip tests and shares
the same layout validation. ASCII string decoding now constructs strings directly from the
heap-backed partition buffer instead of allocating an intermediate byte array. The disabled
profiling workload now includes the low-selectivity
`distance = 42L` scan as well as full and broad scans. Codex helped design and validate the
change, including comments beside the selective-scan decisions that describe their purpose and
allocation/I/O benefit. A follow-up review removed redundant byte-order setup and a duplicate
channel-size lookup from the selective reader; the normal verification path remains free of the
large workload.

## Problem and fix details

Before this change, `StorageEngine.select` called `readPartition` for every catalog-selected
partition. `readPartition` loaded the full partition file, decoded every value in every PAX
column chunk, and allocated an `Object[]` for every on-disk row before `select` evaluated the
predicate. Consequently, a query that returned a small fraction of a candidate partition still
performed the work required to materialize all of its rows. String values added an extra
temporary `byte[]` allocation during decoding.

For example, consider a 10,000-row PAX partition with `city`, `distance`, and `price` chunks
and the predicate `distance = 42L`. The old path decoded all 10,000 cities, distances, and
prices, allocated 10,000 result-shaped arrays, and then discarded every array except the rows
whose distance was 42. This happened even though the PAX directory already records the offset
and length of the `distance` chunk.

The new reader validates the header and directory, reads the `distance` chunk first, and records
only matching row indexes and their already-decoded predicate values. If there are no matches,
it returns without reading `city` or `price`. If, for example, rows 12 and 8,431 match, it reads
the other two chunks only after that decision, advances over their unmatched encoded values, and
decodes `city` and `price` solely for rows 12 and 8,431. The returned rows remain in on-disk row
order and schema column order. This preserves `SELECT *` semantics while avoiding unnecessary
object creation and decoding for rejected rows.

The full `readPartition` method remains for complete partition round-trip coverage. Both readers
now use the same header and directory validator, so the optimization does not weaken binary
format checks. Direct ASCII decoding constructs a `String` from a heap-backed partition buffer
and advances its cursor; this removes the previous intermediate `byte[]` for normal partition
reads. The non-array-buffer fallback remains because it is needed when `decodeValue` is used
with another kind of `ByteBuffer`.

## Changed files

- `src/test/java/dk/itu/boomdb/StorageEngineLargeIT.java` — opt-in large-data import and scan workload.
- `src/main/java/dk/itu/boomdb/StorageEngine.java` — uses the selective reader from `select`.
- `src/main/java/dk/itu/boomdb/StorageSupport.java` — selective reader, shared layout validation, and allocation-free ASCII buffer decoding.
- `src/test/java/dk/itu/boomdb/StorageSupportTest.java` — selective read order, types, and no-match short-circuit coverage.
- `docs/ai-usage/2026-09-15-storage-engine-large-profile-test.md` — AI-assisted change record.

## Validation

- `mvn '-Dtest=StorageSupportTest' test` — passed: 26 focused unit tests, including re-runs after explanatory comments and redundant-operation cleanup.
- `mvn '-Dit.test=StorageEngineIT' verify` — passed: 46 unit tests and 24 storage-engine integration tests.
- `mvn '-Dit.test=StorageEngineLargeIT' '-Djunit.jupiter.conditions.deactivate=org.junit.*DisabledCondition' '-DargLine=-XX:StartFlightRecording=filename=target/storage-engine-large-%p.jfr,settings=profile,dumponexit=true' verify` — passed and wrote a JFR recording for the workload.
- `mvn verify` — passed: 46 unit tests and 34 integration tests; the large-data test was skipped as intended.

## Ownership checkpoint

- [X] I reviewed this change and can explain its design, correctness, and trade-offs.
