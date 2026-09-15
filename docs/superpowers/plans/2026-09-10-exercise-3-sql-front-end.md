# Exercise 3 SQL Front End Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Parse the Exercise 3 SQL subset into a typed AST, bind it against BoomDB schemas, print it back to SQL, and demonstrate the four required statements without executing them.

**Architecture:** ANTLR generates an internal lexer/parser in `dk.itu.datasys.sql`; a public facade and immutable AST in `dk.itu.boomdb` isolate consumers from generated types. Binding remains a separate read-only pass over `StorageEngine.schema`, while printing is a total dispatch over the sealed statement hierarchy.

**Tech Stack:** Java 25, Maven 3.9+, ANTLR 4.13.2, JUnit 6.1.2, SLF4J/Log4j2.

**Spec:** `docs/superpowers/specs/2026-09-10-exercise-3-sql-front-end-design.md`

## Global Constraints

- Java 25 or later and Maven 3.9 or later.
- ANTLR 4.13.2; no SQL-processing library.
- Generated ANTLR Java files stay under `target/` and are not tracked.
- SQL statements are parsed, bound, and printed but never executed.
- Existing source comments are preserved.
- Git is read-only: do not add, commit, branch, tag, reset, or otherwise mutate Git state.
- Use `apply_patch` for source and documentation edits.

---

### Task 1: ANTLR parser and typed AST

**Files:**
- Modify: `pom.xml`
- Create: `src/main/antlr4/dk/itu/datasys/sql/Sql.g4`
- Create: `src/main/java/dk/itu/boomdb/Statement.java`
- Create: `src/main/java/dk/itu/boomdb/CreateTableStatement.java`
- Create: `src/main/java/dk/itu/boomdb/CopyStatement.java`
- Create: `src/main/java/dk/itu/boomdb/SelectStatement.java`
- Create: `src/main/java/dk/itu/boomdb/Predicate.java`
- Create: `src/main/java/dk/itu/boomdb/SqlParseException.java`
- Create: `src/main/java/dk/itu/boomdb/SqlAstBuilder.java`
- Create: `src/main/java/dk/itu/boomdb/SqlParser.java`
- Create: `src/test/java/dk/itu/boomdb/SqlParserTest.java`

**Interfaces:**
- Consumes: existing `ColumnSpec`, `ColumnType`, and `Comparison`.
- Produces: `List<Statement> SqlParser.parse(String sqlText)` and the exact AST records required by Exercise 3.

- [ ] **Step 1: Add parser tests before production code**

Create tests that assert exact AST equality for all four statement forms; exact Java literal classes for `12`, `12.0`, `'12'`, `-1`, and `-1.5`; lowercase keywords with preserved identifier casing; skipped comments/whitespace; and exact line/column values for missing semicolon, unbalanced parentheses, `TEXT`, unterminated string, and missing `FROM`.

Representative assertion:

```java
assertEquals(List.of(new SelectStatement("Trips", Optional.of(
        new Predicate("distance", Comparison.GREATER_THAN, -1L)))),
        parser.parse("select * from Trips where distance > -1;"));
```

- [ ] **Step 2: Verify the parser tests fail for the missing API**

Run: `mvn -Dtest=SqlParserTest test`

Expected: compilation failure naming missing `SqlParser` and AST types.

- [ ] **Step 3: Wire ANTLR into Maven and add the grammar**

Add the `antlr.version` property, `antlr4-runtime` dependency, and `antlr4-maven-plugin` execution with `<visitor>true</visitor>`. Implement the exact grammar from Exercise 3. Keep keyword tokens before `IDENTIFIER`; ANTLR's longest-token rule ensures decimal literals are recognized as `DOUBLE_LITERAL` rather than a shorter `LONG_LITERAL` prefix.

- [ ] **Step 4: Add the immutable AST and parse exception**

Implement:

```java
public sealed interface Statement
        permits CreateTableStatement, CopyStatement, SelectStatement { }

public record SelectStatement(String tableName, Optional<Predicate> where)
        implements Statement { }

public final class SqlParseException extends RuntimeException {
    private final int line;
    private final int column;
    public int line() { return line; }
    public int column() { return column; }
}
```

Use corresponding public records for create, copy, and predicate. Give all public/protected types and members useful Javadoc without documenting trivial record accessors.

- [ ] **Step 5: Implement one AST visitor and the parser facade**

`SqlAstBuilder` returns typed values from generated contexts. `SqlParser` removes default error listeners from lexer and parser, installs a throwing `BaseErrorListener`, calls `script()`, returns `List.copyOf(...)`, and logs exactly one outcome per call:

```java
LOGGER.debug("statements={} durationMs={}", statements.size(), elapsedMillis(started));
LOGGER.error("failed line={} col={} durationMs={}", error.line(), error.column(),
        elapsedMillis(started));
```

- [ ] **Step 6: Verify parser behavior is green**

Run: `mvn -Dtest=SqlParserTest test`

Expected: all parser tests pass with no stderr syntax diagnostics.

- [ ] **Step 7: Inspect the focused diff without mutating Git**

Run: `git diff -- pom.xml src/main/antlr4 src/main/java/dk/itu/boomdb src/test/java/dk/itu/boomdb/SqlParserTest.java`

Expected: only the parser/build/AST changes described above.

### Task 2: SQL pretty-printer

**Files:**
- Create: `src/main/java/dk/itu/boomdb/SqlPrinter.java`
- Create: `src/test/java/dk/itu/boomdb/SqlPrinterTest.java`

**Interfaces:**
- Consumes: all `Statement` implementations from Task 1.
- Produces: `String SqlPrinter.print(Statement statement)` with a terminating semicolon.

- [ ] **Step 1: Add failing round-trip tests**

For `CREATE TABLE`, `COPY`, `SELECT` without `WHERE`, and `SELECT` with each literal type, assert:

```java
assertEquals(List.of(statement), parser.parse(printer.print(statement)));
```

Also assert one normalized result such as `SELECT * FROM trips WHERE distance > 100;`.

- [ ] **Step 2: Verify the printer test fails because `SqlPrinter` is absent**

Run: `mvn -Dtest=SqlPrinterTest test`

Expected: compilation failure naming `SqlPrinter`.

- [ ] **Step 3: Implement minimal exhaustive statement rendering**

Use `instanceof` dispatch over the sealed hierarchy, `String.join` for column definitions, a switch for comparison symbols, and `String.valueOf` for numeric constants. Quote string constants with single quotes.

- [ ] **Step 4: Verify printer tests pass**

Run: `mvn -Dtest=SqlPrinterTest test`

Expected: all printer round-trip tests pass.

### Task 3: Schema accessor and binder

**Files:**
- Modify: `src/main/java/dk/itu/boomdb/StorageEngine.java`
- Create: `src/main/java/dk/itu/boomdb/Binder.java`
- Create: `src/test/java/dk/itu/boomdb/BinderIT.java`

**Interfaces:**
- Consumes: Task 1 AST, existing persisted catalog, existing storage type vocabulary.
- Produces: `List<ColumnSpec> StorageEngine.schema(String tableName)` and `void Binder.bind(Statement statement)`.

- [ ] **Step 1: Add failing binder integration tests**

Using `@TempDir`, create a `trips` schema and assert valid `CREATE`, `COPY`, and both `SELECT` forms bind. Assert `IllegalArgumentException` for unknown tables, unknown predicate columns, `distance = 'x'`, empty create columns, and duplicate create column names.

- [ ] **Step 2: Verify binder tests fail for missing APIs**

Run: `mvn -Dit.test=BinderIT verify`

Expected: compilation failure naming `Binder` and `StorageEngine.schema`.

- [ ] **Step 3: Add the read-only schema accessor**

Implement:

```java
public List<ColumnSpec> schema(String tableName) {
    return requireTable(tableName).columns();
}
```

Document the public method and its unknown-table behavior.

- [ ] **Step 4: Implement statement validation in `Binder`**

Require a non-null engine and statement. Reuse `engine.schema` for table existence. For selects, find the named `ColumnSpec` and compare the predicate constant's exact class to `String.class`, `Long.class`, or `Double.class`. For creates, reject empty columns and duplicate names with a `HashSet`.

- [ ] **Step 5: Verify binder and existing storage tests pass**

Run: `mvn -Dit.test=BinderIT,StorageEngineIT verify`

Expected: all selected tests pass.

### Task 4: Required runnable demonstration

**Files:**
- Modify: `src/main/java/dk/itu/boomdb/Engine.java`
- Modify: `src/test/java/dk/itu/boomdb/EngineIT.java`

**Interfaces:**
- Consumes: `SqlParser.parse` and `SqlPrinter.print`.
- Produces: `Engine.main` prints exactly the four normalized required statements, one per line, apart from configured logging destinations.

- [ ] **Step 1: Replace the old integration assertion with a failing output assertion**

Capture `System.out`, invoke `Engine.main(new String[0])`, normalize line endings, and assert:

```text
CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
COPY trips FROM 'trips.csv';
SELECT * FROM trips WHERE distance > 100;
SELECT * FROM trips;
```

- [ ] **Step 2: Verify the output test fails against the Exercise 2 demo**

Run: `mvn -Dit.test=EngineIT verify`

Expected: assertion failure showing the existing query-result tables instead of SQL statements.

- [ ] **Step 3: Replace demo execution with parse-and-print**

Retain existing comments, define the four-statement text block, parse it once, and print `new SqlPrinter().print(statement)` for each statement. Remove only obsolete executable storage-demo code and imports.

- [ ] **Step 4: Verify the demo integration test passes**

Run: `mvn -Dit.test=EngineIT verify`

Expected: the output assertion passes.

### Task 5: Full validation and learning log

**Files:**
- Create: `docs/ai-usage/2026-09-10-exercise-3-sql-front-end.md`

**Interfaces:**
- Consumes: completed Tasks 1–4 and `docs/ai-usage/README.md`.
- Produces: verified Exercise 3 implementation and one sanitized learning-log entry.

- [ ] **Step 1: Run all unit and integration checks**

Run: `mvn verify`

Expected: Maven reports `BUILD SUCCESS` with all unit and integration tests passing.

- [ ] **Step 2: Run the required no-argument demo**

Run: `mvn compile exec:java`

Expected: the four required normalized SQL statements appear in order, one per line.

- [ ] **Step 3: Confirm generated sources and build output are untracked**

Run: `git status --short`

Expected: no paths under `target/`; only intentional source, test, design, plan, and learning-log files are listed.

- [ ] **Step 4: Create the sanitized AI learning log**

Record the request summary, actual changes, every changed file, and actual validation results. Keep the ownership checkbox unchecked:

```markdown
## Ownership checkpoint

- [ ] I reviewed this change and can explain its design, correctness, and trade-offs.
```

- [ ] **Step 5: Re-run final verification after documentation**

Run: `mvn verify && mvn compile exec:java`

Expected: both commands finish successfully and the demo retains the required output.

- [ ] **Step 6: Review final repository state without changing Git**

Run: `git diff --check && git status --short`

Expected: no whitespace errors and only intentional uncommitted changes. Do not perform the release-tag instructions because Git mutations are prohibited.
