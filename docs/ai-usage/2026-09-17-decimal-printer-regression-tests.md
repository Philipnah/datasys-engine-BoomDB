# Code change: Decimal printer regression tests

- Date: 2026-09-17
- Contributor: Anton Yakovenko
- Assistance: AI-assisted
- AI tool/model: Codex / GPT-5

## Request summary

Add round-trip regression coverage for large and small decimal literals and signed zero, then make the printer render exponent-form doubles as grammar-valid fixed-point literals.

## Change and assistance summary

Extended the SQL printer's existing statement round-trip cases with two values that print in exponent notation and a negative-zero value. Updated the printer to expand exponent-form doubles with `BigDecimal.toPlainString()` and append `.0` when the expanded value would otherwise be tokenized as a `LONG_LITERAL`; non-exponent output preserves `-0.0` unchanged. The AI helped identify the regression cases and validate the grammar interaction.

## Changed files

- `src/main/java/dk/itu/boomdb/SqlPrinter.java` — prints exponent-form doubles as fixed-point double literals.
- `src/test/java/dk/itu/boomdb/SqlPrinterTest.java` — adds decimal round-trip regression cases.
- `docs/ai-usage/2026-09-17-decimal-printer-regression-tests.md` — records this AI-assisted change.

## Validation

- `mvn -Dtest=SqlPrinterTest test` — passes: all 10 printer tests, including large decimal, small decimal, and signed zero round trips.
- `mvn verify` — passes: 45 unit tests and 33 integration tests.

## Ownership checkpoint

- [ ] I reviewed this change and can explain its design, correctness, and trade-offs.
