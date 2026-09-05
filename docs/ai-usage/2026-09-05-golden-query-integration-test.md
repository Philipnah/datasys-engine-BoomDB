# Code change: Self-contained golden-query integration test

- Date: 2026-09-05
- Contributor: Philip Han
- Assistance: AI-assisted
- AI tool/model: Codex / GPT-6

## Request summary

Run the three golden queries directly inside EngineIT while retaining the Engine.main demonstration.

## Change and assistance summary

AI replaced the console-capture test with table creation, CSV loading, and the three queries inside the test method. JUnit provides a temporary storage directory and cleans it up. Assertions check each query's row count and every returned row, making validation independent of demo output formatting. Engine.main was left untouched.

## Changed files

- `src/test/java/dk/itu/boomdb/EngineIT.java` — self-contained golden example and exact result assertions.
- `docs/ai-usage/2026-09-05-golden-query-integration-test.md` — learning log.

## Validation

- `mvn -Dtest=EngineIT test` — passed, one test with no failures or errors.
- `mvn verify` — passed, including all 25 integration tests with no failures or errors.
- `git diff --check` — passed.

## Ownership checkpoint

- [ ] I reviewed this change and can explain its design, correctness, and trade-offs.
