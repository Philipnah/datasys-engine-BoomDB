# Code change: Benchmark runtime bars

- Date: 2026-11-28
- Contributor: Philip Han
- Assistance: AI-assisted
- AI tool/model: OpenAI Codex

## Request summary

Show the minimum, median, and maximum runtimes as three differently colored
bars for every join configuration. Use the title “Join benchmark”.

## Change and assistance summary

Codex used the existing runtime summary to render three side-by-side bars per
configuration. The plot labels each value and uses consistent colors for the
three statistics. The README and command help use the shorter benchmark name.

## Changed files

- `benchmarks/join_order.py` — grouped runtime chart and legend.
- `benchmarks/test_join_order.py` — checks the title, bar heights, labels, colors, and positions.
- `README.md` — describes the chart and its CSV source.

## Validation

- The benchmark Python suite passed all three tests with Matplotlib installed.
- The default benchmark completed 20 measurements. The generated chart was
  inspected and its values matched `runtime-summary.csv`.
- The chart was regenerated under the new title from the same measurements.
  Both regenerated CSV files matched the original files byte for byte.

## Ownership checkpoint

- [ ] I reviewed this change and can explain its design, correctness, and trade-offs.
