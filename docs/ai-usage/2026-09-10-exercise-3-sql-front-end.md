# Code change: Exercise 3 SQL front end

- Date: 2026-09-10
- Contributor: Philip Han
- Assistance: AI-assisted
- AI tool/model: Codex (GPT-5)

## Request summary

Implement the Exercise 3 SQL text-processing front end, including ANTLR parsing, a typed AST,
catalogue binding, normalized SQL printing, the runnable demonstration, and required tests.

## Change and assistance summary

Added the ANTLR build integration and grammar, public SQL AST and facade, fail-fast positioned parse
errors, parse outcome logging, schema-based binding, and normalized round-trip printing. Updated the
runnable entry point to parse and print the four required statements without executing them. The AI
helped derive the design and plan, implement the code with red-green test cycles, preserve existing
comments and storage tests, and run the final validation.

## Changed files

- `pom.xml` — pins ANTLR 4.13.2 and enables grammar/visitor generation
- `src/main/antlr4/dk/itu/datasys/sql/Sql.g4` — defines the required SQL subset
- `src/main/java/dk/itu/boomdb/Statement.java` — defines the sealed statement hierarchy
- `src/main/java/dk/itu/boomdb/CreateTableStatement.java` — represents parsed table creation
- `src/main/java/dk/itu/boomdb/CopyStatement.java` — represents parsed CSV copying
- `src/main/java/dk/itu/boomdb/SelectStatement.java` — represents parsed selections
- `src/main/java/dk/itu/boomdb/Predicate.java` — represents typed comparison predicates
- `src/main/java/dk/itu/boomdb/SqlParseException.java` — reports first-error source coordinates
- `src/main/java/dk/itu/boomdb/SqlAstBuilder.java` — converts ANTLR parse trees to the typed AST
- `src/main/java/dk/itu/boomdb/SqlParser.java` — exposes fail-fast script parsing and logging
- `src/main/java/dk/itu/boomdb/SqlPrinter.java` — renders normalized round-trippable SQL
- `src/main/java/dk/itu/boomdb/Binder.java` — validates statements against storage schemas
- `src/main/java/dk/itu/boomdb/StorageEngine.java` — exposes schema lookup and shared validation
- `src/main/java/dk/itu/boomdb/Engine.java` — runs the required parse-and-print demonstration
- `src/test/java/dk/itu/boomdb/SqlParserTest.java` — covers parsing, typing, casing, comments, and errors
- `src/test/java/dk/itu/boomdb/SqlPrinterTest.java` — covers normalization and AST round trips
- `src/test/java/dk/itu/boomdb/BinderIT.java` — covers valid and invalid catalogue binding
- `src/test/java/dk/itu/boomdb/EngineIT.java` — verifies the no-argument demonstration output
- `docs/superpowers/specs/2026-09-10-exercise-3-sql-front-end-design.md` — records the approved design
- `docs/superpowers/plans/2026-09-10-exercise-3-sql-front-end.md` — records the test-first execution plan
- `docs/ai-usage/2026-09-10-exercise-3-sql-front-end.md` — records this AI-assisted change

## Validation

- `mvn -Dtest=SqlParserTest test` — 13 parser tests passed after the expected missing-API red phase
- `mvn -Dtest=SqlPrinterTest test` — 7 printer tests passed after the expected missing-class red phase
- `mvn -Dit.test=BinderIT,StorageEngineIT verify` — 31 integration tests passed after the expected missing-API red phase
- `mvn -Dit.test=EngineIT verify` — 2 demo/storage integration tests passed after the expected output-mismatch red phase
- `mvn verify` — build succeeded with 42 unit and 33 integration tests passing
- `mvn compile exec:java` — build succeeded and printed the four required normalized SQL statements
- `git diff --check` — completed with no whitespace errors
- `git status --short` — confirmed generated files under `target/` are not tracked

## Ownership checkpoint

- [ ] I reviewed this change and can explain its design, correctness, and trade-offs.
