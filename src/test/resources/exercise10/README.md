# Exercise 10 oracle fixtures

The four input CSV files reproduce section 5 of `exercise-descriptions/Exercise10.md`.
The query constants and CREATE/COPY setup live in `Exercise10TestData`.

`query-1.expected.csv` through `query-4.expected.csv` are sorted, headerless
results recorded from DuckDB v2.0.0 (Cyanoptera), commit `d54cbbe3df`, on
2026-11-12. Each query ran against a fresh in-memory database with the exercise's
explicit schemas and `COPY <table> FROM '<absolute-file-path>' (HEADER false)`.
The row counts were hand-checked against the exercise: 4, 4, 4, and 3.

To reproduce an answer, use the CREATE statements and CSV paths from
`Exercise10TestData.setupSql()`, add `(HEADER false)` to each COPY, and append
the corresponding query from `Exercise10TestData.QUERIES`. Run that script with
`duckdb -csv -noheader :memory: -f <script.sql>` and sort its output. On DuckDB
versions with agent mode, add `-no-agent` to keep ordinary CSV output.

The integration tests compare sorted output with these recorded answers,
including duplicate rows. DuckDB is needed to regenerate or independently
check the oracle, but is not a CI dependency.
