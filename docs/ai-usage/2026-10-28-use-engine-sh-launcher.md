# Code change: Use the existing engine.sh launcher

- Date: 2026-10-28
- Contributor: Philip Han
- Assistance: AI-assisted
- AI tool/model: Codex

## Request summary

Use the team's existing `engine.sh` launcher for Exercise 7 integration instead
of keeping an additional script named `engine`.

## Change and assistance summary

Removed the forwarding wrapper and changed the existing integration test to
invoke `engine.sh` directly. The test still checks script execution from a
different working directory, the remaining rows after repeated COPY and DELETE,
and the persisted version after restart. Reusing the established launcher avoids
maintaining a second command name. Exercise examples using `./engine` correspond
to `./engine.sh` in this repository. AI assistance made the edits and ran validation.

## Changed files

- `engine` — removed the redundant launcher wrapper.
- `src/test/java/dk/itu/boomdb/LauncherIT.java` — invoke `engine.sh` directly and
  explain the launcher coverage in a comment.
- `docs/ai-usage/2026-10-28-use-engine-sh-launcher.md` — record the change and validation.

## Validation

- `mvn -B -Dtest=LauncherIT test` — passed, one test with no failures or errors.
- `mvn -B verify` — passed, including the packaged launcher integration test.
- `git diff --check` — passed.

## Ownership checkpoint

- [ ] I reviewed this change and can explain its design, correctness, and trade-offs.
