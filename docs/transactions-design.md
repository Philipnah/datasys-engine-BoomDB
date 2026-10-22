1. Versioning: One catalog file per version.
2. Commit: We keep an integer of catalog version in memory of the newest version of the catalog. On db start we check which catalog file has the highest integer appended to it and use that for the in-memory integer.
3. Snapshot: We pin the version of the catalog to the newest catalog version when the statement starts.
4. Retention: We want to keep old version files. We keep them based on reference counting, a time limit and maximum amount of versions (5). Reference counting to make sure that we don't remove a currently used version. Time limit is to allow for time travel feature. 
5. The rewrite set: The planner's pruning helper. And what happens to a partition that may contain a match but turns out to contain none: carry over the original.
6. New version: DELETE that matches nothing creates no new version of the partition.
7. Partition placement: Write new partition into a new file, as we store partitions in separate files.

## Implementation clarifications (Exercise 7)

- **Versions and publication:** The counter belongs to each table. `CREATE TABLE`
  publishes version 0. A successful `COPY`, including an empty input, increments
  it. New catalogs are named `catalogs/versions/<table>-v<version>.json`; startup selects the
  highest numeric finalized version for that table and ignores temporary files.
  Existing flat `<table>.json` and `<table>-v<version>.json` catalogs remain
  readable in place. Discovery checks each document's table identity and version
  before admitting it, because a flat `t-v1.json` may be a legacy table named
  `t-v1`. Valid foreign catalogs are ignored; malformed identities and duplicate
  table/version documents are errors. Saves reject an existing logical version
  across both layouts. Retention deletes the actual discovered file, so no copied
  compatibility file can resurrect an expired version.
- **Atomic commit:** Write and flush new partition files first, then write and
  flush a complete temporary catalog. Atomically rename the catalog to its final
  versioned name before publishing the immutable latest catalog in memory.
  Publication and snapshot acquisition are synchronized in one JVM. An atomic
  move failure aborts publication; there is no non-atomic fallback. This is
  process-crash atomicity, rather than a claim about every filesystem's behavior
  after sudden power loss.
- **Snapshot representation:** A statement acquires a reference-counted lease
  on one immutable table catalog before binding. Binding, planning, scanning,
  rewriting, and summary logging use that exact catalog. The lease closes after
  execution, including on failure. Partition lists and statistics are copied
  defensively when metadata is constructed.
- **Retention:** Keep the current version and at most five versions total for
  one hour. An unpinned older version may be removed when it falls outside the
  newest five or is older than one hour. Active leases override both limits, so
  temporary overflow is allowed. Cleanup runs on commit and lease release.
  Requesting an expired or unknown version throws `IllegalArgumentException`.
  This policy does not add time-travel SQL syntax.
- **Pruning and rewriting:** Both the planner and DELETE use
  `StorageSupport.shouldPrune`. A pruned partition is carried unchanged. A
  candidate with no actual matches is also carried unchanged; an entirely
  deleted partition is omitted without writing an empty replacement. Surviving
  rows use the existing PAX encoding and freshly computed statistics.
- **No-match DELETE:** Explicit contributor decision: if no rows match, do not
  commit and keep the catalog version unchanged. Log that unchanged version and
  zero rows deleted. This resolves item 6 above and the choice explicitly posed
  by Exercise 7 section 1; every DELETE that actually commits increments its
  table version.
- **Partition identity and orphans:** New files have fresh identities and are
  created without overwriting an existing path. Carried partitions retain the
  same file references and internal column offsets. Retention removes catalog
  versions only, leaving partition bytes safe. A future orphan collector can
  find files unreferenced by every retained catalog, while also excluding active
  writers' files; automatic partition collection is outside this exercise.
