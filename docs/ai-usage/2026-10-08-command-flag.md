# Code change: Require the command flag for inline SQL

- Date: 2026-10-08
- Contributor: Philip Han
- Assistance: AI-assisted
- AI tool/model: Codex / GPT-6.1

## Request summary

Require `-c` when passing SQL directly to the database command line, as specified in Exercise 5.

## Change and assistance summary

The entry point now accepts inline SQL as `-c <SQL statement>` and rejects bare SQL with usage text before creating storage. File execution with `-f` and the no-argument team-name output remain available. The existing argument check handles this without another parser or dependency. This intentionally changes compatibility for callers that previously passed bare SQL: they must add `-c`.

AI inspected the exercise requirements and command-line flow, updated the integration tests first, observed their expected failures, and then changed argument handling, Javadoc, and the launcher example. Existing comments were retained and the launcher comment was updated.

## Changed files

- `src/main/java/dk/itu/boomdb/Engine.java` — require `-c` and document the invocation syntax.
- `src/test/java/dk/itu/boomdb/EngineIT.java` — cover command execution, updated usage, bare SQL, missing flag values, unknown flags, and extra arguments.
- `engine.sh` — update the command example.
- `docs/ai-usage/2026-10-08-command-flag.md` — record this AI-assisted change.

## Validation

- `mvn -Dtest=EngineIT test` — initially failed in three tests against the old behavior; passed all six tests after implementation.
- `mvn verify` — build succeeded; 52 unit tests and 45 integration tests passed with no failures or errors.
- `git diff --check` — passed without whitespace errors.

## Ownership checkpoint

- [ ] I reviewed this change and can explain its design, correctness, and trade-offs.
