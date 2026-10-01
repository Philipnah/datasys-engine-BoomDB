# Code change: Exercise 4 Volcano pipeline and SQL front door

- Date: 2026-09-17
- Contributor: Anton Yakovenko
- Assistance: AI-assisted
- AI tool/model: Codex (GPT-5)

## Request summary

Implement the Exercise 4 execution layer from the course specification: Volcano scan and filter
operators, planner-owned partition pruning, statement execution, the SQL command-line front door,
statement-number logging context, and the required automated tests.

## Change and assistance summary

Added pull-based scan and filter operators, moved min/max pruning and partition decisions from
`StorageEngine` into a planner, and preserved the Week 2 `select` API by making it drain the new
pipeline. Added a script executor that parses once, binds/plans/executes statements in order, stops
at the first error, and restores the MDC statement number. Replaced the parsing demonstration with
single-statement and `-f` script execution that uses `data/` by default, buffers successful SELECT
results, and prints headerless CSV without mixing logs or errors into stdout. The AI helped inspect
the existing implementation, write and execute the implementation plan, implement each phase with
red-green tests, review the resulting diff, and run validation.

## Changed files

- `.gitignore` — ignores the default `data/` runtime database
- `src/main/java/dk/itu/boomdb/Operator.java` — defines the Volcano operator lifecycle
- `src/main/java/dk/itu/boomdb/ScanOperator.java` — reads a planner-selected partition list in order
- `src/main/java/dk/itu/boomdb/FilterOperator.java` — filters child rows and logs input/output counts
- `src/main/java/dk/itu/boomdb/Planner.java` — prunes partitions, records scan stats, and builds plans
- `src/main/java/dk/itu/boomdb/Executor.java` — runs parse/bind/plan/execute with MDC numbering
- `src/main/java/dk/itu/boomdb/StorageEngine.java` — exposes narrow package seams and drains plans
- `src/main/java/dk/itu/boomdb/StorageSupport.java` — shares catalog-statistic decoding with planning
- `src/main/java/dk/itu/boomdb/ScanStats.java` — documents planner-owned partition decisions
- `src/main/java/dk/itu/boomdb/Engine.java` — implements argument modes, CSV output, and stream isolation
- `src/test/java/dk/itu/boomdb/TestListOperator.java` — supplies deterministic rows to operator tests
- `src/test/java/dk/itu/boomdb/FilterOperatorTest.java` — covers filtering and child lifecycle
- `src/test/java/dk/itu/boomdb/ScanOperatorTest.java` — covers selected and empty partition lists
- `src/test/java/dk/itu/boomdb/PlannerTest.java` — covers pruning, stats, plan shapes, and decision logs
- `src/test/java/dk/itu/boomdb/ExecutorIT.java` — covers execution order, failures, and MDC numbering
- `src/test/java/dk/itu/boomdb/EngineIT.java` — covers both CLI modes, CSV output, and clean error streams
- `docs/superpowers/plans/2026-09-17-exercise-4-volcano-sql-front-door.md` — records the implementation plan
- `docs/ai-usage/2026-09-17-exercise-4-volcano-sql-front-door.md` — records this AI-assisted change

## Validation

- `mvn -Dtest=FilterOperatorTest,ScanOperatorTest test` — expected missing-operator red phase, then 4 tests passed
- `mvn -Dtest=PlannerTest test` — expected missing-planner red phase, then 3 tests passed
- `mvn -Dit.test=StorageEngineIT verify` — all 24 unchanged storage integration tests passed
- `mvn -Dit.test=ExecutorIT verify` — expected missing-executor red phase, then 4 integration tests passed
- `mvn -Dit.test=EngineIT verify` — expected missing-front-door red phase, then 6 integration tests passed
- `mvn -Dit.test=EngineIT,StorageEngineIT verify` — all 30 selected integration tests passed
- `mvn verify` — build succeeded with 49 unit and 41 integration tests passing
- `mvn -q compile exec:java` — exited successfully and printed the team name and usage text
- `git diff --check v0.3..HEAD` — completed with no whitespace errors before final documentation edits

## Ownership checkpoint

- [ ] I reviewed this change and can explain its design, correctness, and trade-offs.
