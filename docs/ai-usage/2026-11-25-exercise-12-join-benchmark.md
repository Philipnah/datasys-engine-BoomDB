# Code change: Exercise 12 join-order benchmark

- Date: 2026-11-25
- Contributor: Philip Han
- Assistance: AI-assisted
- AI tool/model: OpenAI Codex

## Request summary

Implement the Exercise 12 benchmark and generate the timing chart, join-size
table, and supporting measurements needed for the Project 3 report.

## Change and assistance summary

Codex added a Python runner around the packaged BoomDB CLI. It generates the
fixed-seed workload, interleaves both forced orders and both optimized forms,
and checks that the query results agree after normalizing their column orders.
BoomDB joins its log snapshot with the measured session labels. The runner uses
that join output for the size table and the statement summaries for the runtime
chart. Each measured query runs in a fresh JVM after an untimed query warms the
filesystem cache. A second Codex agent reviewed the script and checked join-log
attribution.

## Changed files

- `benchmarks/join_order.py` — workload generation, CLI measurements, log analysis, and report artifacts.
- `README.md` — run commands, measurement method, and artifact locations.

## Validation

- `mvn -B verify` passed with zero test failures.
- `uv run --no-project --python 3.12 benchmarks/join_order.py` completed five
  runs per configuration. All results matched, the forced intermediate sizes
  were 1,000 and 10,000,000 rows, and the runtime ranges did not overlap.
- An independent check confirmed byte-identical input CSVs across two runs,
  checked timing summaries against the engine log, and matched all 40 labeled
  join rows to measured sessions. The generated chart was visually inspected.

## Ownership checkpoint

- [ ] I reviewed this change and can explain its design, correctness, and trade-offs.
