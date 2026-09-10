# Exercise 3 SQL Front End Design

## Scope

Implement the SQL text-processing front end defined by `exercise-descriptions/Exercise3.md`.
The feature parses the prescribed SQL subset into a typed immutable AST, validates statements
against the storage catalogue, and renders AST values back to parseable SQL. It does not execute
SQL statements.

## Package and build structure

The ANTLR grammar lives at the required path
`src/main/antlr4/dk/itu/datasys/sql/Sql.g4`, so generated lexer, parser, listener, and visitor
classes belong to `dk.itu.datasys.sql`. Generated Java sources remain build output under
`target/generated-sources/antlr4` and are not tracked.

The public front-end API belongs to `dk.itu.boomdb`, next to the existing storage API types it
reuses. Maven pins ANTLR 4.13.2 for both code generation and runtime use and enables visitor
generation.

## Parsing and AST

`Statement` is a sealed interface permitting public record implementations for `CREATE TABLE`,
`COPY`, and `SELECT`. Supporting `Predicate` values reuse the existing `Comparison` enum;
`CreateTableStatement` columns reuse `ColumnSpec` and `ColumnType`.

`SqlAstBuilder` is the single parse-tree visitor. It maps syntax to AST values and converts
literal token text into exactly `String`, `Long`, or `Double`. It removes the surrounding quotes
from string literals and maps `=`, `<`, and `>` to the existing comparison constants.

`SqlParser.parse(String)` lexes and parses a complete script and returns an immutable statement
list. Both lexer and parser use the same fail-fast error listener after their default listeners
are removed. The first lexical or syntax error throws `SqlParseException` containing a 1-based
line and 0-based column, and parsing logs one debug success or one error failure line with elapsed
milliseconds.

## Binding

`StorageEngine.schema(String)` returns an immutable schema copy and uses the existing catalogue
lookup behavior for unknown tables.

`Binder.bind(Statement)` dispatches by statement type. `SELECT` and `COPY` require an existing
table. A `SELECT` predicate additionally requires an existing column and the exact Java constant
class corresponding to its `ColumnType`. `CREATE TABLE` requires at least one column and unique
column names, but deliberately does not check whether the table already exists.

All binding failures use `IllegalArgumentException`, matching the storage API.

## Printing and demonstration

`SqlPrinter.print(Statement)` emits normalized uppercase SQL with a terminating semicolon. Each
AST shape is rendered completely, and every supported value printed by it parses back to an equal
AST value. String escaping remains out of scope, as specified by the exercise grammar.

`Engine.main` parses the four required example statements as one script and prints each normalized
statement on its own line. It performs no storage operation. Existing source comments are retained
in accordance with the repository instructions.

## Validation

Development follows red-green-refactor. Parser unit tests cover every statement shape, literal
type, negative numeric values, keyword casing, identifier-case preservation, comments and
whitespace, and at least five malformed inputs with exact positions. Printer tests cover the
round-trip property for all shapes. Binder integration tests use `@TempDir` and cover valid
statements plus every required rejection. A demo integration test captures standard output and
checks the four required lines.

After focused tests pass, `mvn verify` and `mvn compile exec:java` provide final verification. One
sanitized entry is then added under `docs/ai-usage/`, with its human ownership checkbox left
unchecked.

## Constraints

- Java 25 or later and Maven 3.9 or later.
- ANTLR 4.13.2; no SQL-processing library.
- No generated ANTLR Java files are tracked.
- No SQL execution in this exercise.
- No Git state changes, including commits, branches, or tags.
