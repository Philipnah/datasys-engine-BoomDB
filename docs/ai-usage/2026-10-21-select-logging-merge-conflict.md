# Code change: Resolve SELECT logging merge conflict

- Date: 2026-10-21
- Contributor: Philip Han
- Assistance: AI-assisted
- AI tool/model: Codex / GPT-6

## Request summary

Resolve the active merge conflict while preserving SELECT timing and failure
logging. The contributor chose main's operation error format and the course
requirement to replace double quotes in log messages with apostrophes.

## Change and assistance summary

Retained the experiment's SELECT helper so timing still includes binding,
planning, execution, and operator cleanup. Its catch logs exactly one failure
using `table=... operation=SELECT outcome=ERROR`, then rethrows the original
exception without emitting a successful timing sample. Keeping failure logging
inside the helper avoids a second overlapping catch around the statement loop.
Existing comments from both branches are retained.

SQL and direct storage API logs replace double quotes with apostrophes while
exception messages remain unchanged. This deliberately loses quote fidelity in
the log to satisfy the course's CSV ingestion rules. Integration checks cover
both entry points. Experiment tests now use the merged CLI's required `-c`
argument and recognize the combined error format.

AI assistance compared the conflicting implementations and their consumers,
identified the log-format requirement, applied the contributor's decision, and
ran verification. Git was used only for read-only inspection; the index and
merge state were left unchanged for the contributor to finalize.

## Changed files

- `src/main/java/dk/itu/boomdb/Executor.java` — combine timing and operation-level failure logging.
- `src/main/java/dk/itu/boomdb/StorageEngine.java` — sanitize quotes in direct API log records.
- `src/test/java/dk/itu/boomdb/ExecutorIT.java` — check sanitized logs and unchanged exceptions.
- `src/test/java/dk/itu/boomdb/ExperimentIT.java` — align CLI calls and failure assertions with the merge.
- `docs/ai-usage/2026-10-21-select-logging-merge-conflict.md` — record the change and validation.

## Validation

- Baseline compilation failed on conflict markers in `Executor.java`.
- Updated focused checks initially reported five expected failures for the old error format and unsanitized direct API quotes.
- `mvn -B -Dit.test=ExecutorIT,ExperimentIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false verify` — 24 integration tests passed after the fix.
- `mvn -B verify` — 113 tests passed: 52 unit tests and 61 integration tests.
- `python3 -m unittest discover -s scripts -p 'test_*.py'` — six tests passed.
- `git diff --check` — passed; conflict-marker search found no remaining markers.

## Ownership checkpoint

- [ ] I reviewed this change and can explain its design, correctness, and trade-offs.
