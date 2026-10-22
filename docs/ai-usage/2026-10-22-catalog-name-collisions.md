# Code change: Separate catalog filename namespaces

- Date: 2026-10-22
- Contributor: anton.yakovenko
- Assistance: AI-assisted
- AI tool/model: Codex

## Request summary

Fix the reviewed catalog naming regression while preserving existing databases.
Public storage API table names such as `t-v1` must remain independent of table
`t` as its versions advance. Preserve atomic publication, immutable versions,
reader leases, and retention.

## Change and assistance summary

New catalog versions are written under `catalogs/versions/`. Existing flat
versioned and legacy catalogs remain readable in place. Discovery validates
document identity before including a catalog or excluding it as another table's
file. Explicit versions must be nonnegative integral longs; malformed metadata
cannot acquire a valid identity through Jackson coercion.

Lookup and save reject duplicate logical table/version identities across both
layouts. Retention deletes the actual discovered path, protects active leases,
and leaves foreign catalogs intact. Keeping old documents in place avoids a bulk
migration and avoids copied versions reappearing after retention. The experiment
script follows the same discovery rules.

AI assistance covered the implementation, regression tests, documentation, and
independent review. The review exposed a version-coercion edge case; a failing
test was added before tightening validation. The trade-off is that discovery
continues to inspect both layouts until old flat versions naturally expire.
Partition files and their encoding are unchanged. No Git mutations were made.

## Changed files

- `src/main/java/dk/itu/boomdb/CatalogStore.java` — separate publication directory, validated compatibility discovery, and duplicate protection.
- `src/test/java/dk/itu/boomdb/CatalogStoreTest.java` — mixed layouts, identity validation, duplicate rejection, and retention regressions.
- `src/test/java/dk/itu/boomdb/CatalogNamesIT.java` — public API collision cases in both creation orders, write isolation, legacy compatibility, and restart.
- `src/test/java/dk/itu/boomdb/TransactionIT.java` — publication-failure and lease expectations for the new directory.
- `scripts/partition_experiment.py` — validated discovery across both layouts.
- `scripts/test_partition_experiment.py` — compatibility, corruption, and duplicate regressions.
- `docs/transactions-design.md` — updated physical layout and compatibility rules.

## Validation

- Regression tests failed on the original collision, missing namespace discovery,
  and legacy duplicate handling before implementation; malformed version cases
  also failed before the validation correction.
- `mvn -B -Dtest=CatalogStoreTest,CatalogNamesIT,TransactionIT test` — passed: 41 tests.
- `mvn -B verify` — passed: 89 unit tests and 87 integration tests; zero failures,
  errors, or skips. Existing dependency shading overlap warnings were emitted.
- `python3 -m unittest discover -s scripts -p 'test_*.py'` — passed: 16 tests.
- Original standalone naming reproduction against the rebuilt packaged engine
  — creation of `t-v0` and access to `t-v1` after COPY into `t` both succeeded.
- Independent review — version-coercion finding fixed and rechecked; no remaining
  actionable findings.
- `git diff --check` — passed.

## Ownership checkpoint

- [ ] I reviewed this change and can explain its design, correctness, and trade-offs.
