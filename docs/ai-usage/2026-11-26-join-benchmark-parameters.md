# Code change: Configurable join-order benchmark

- Date: 2026-11-26
- Contributor: Philip Han
- Assistance: AI-assisted
- AI tool/model: OpenAI Codex

## Request summary

Allow contributors to vary and reproduce the Exercise 12 measurement method.

## Change and assistance summary

Codex added command-line parameters for the dataset, sampler and JVM heap.
The runner records the effective settings and replay command and derives the
expected cardinalities from the generated workload. Shared-JVM sampling runs
a script of repeated statements for each configuration. Its log analysis uses
statement numbers to associate each join with the correct repetition.

## Changed files

- `benchmarks/join_order.py` — parameter parsing, workload generation, sampling, and log attribution.
- `benchmarks/test_join_order.py` — schedule and cardinality regression tests.
- `README.md` — options and the Week 6 shared-JVM invocation.

## Validation

- `python3 -B -m unittest discover -s benchmarks` passed both tests.
- `mvn -B verify` passed.
- Full-size default and shared-JVM runs each completed 20 measured queries.
  Raw-log checks matched all 40 labeled join records in each experiment.
- A shared-JVM run with 13 customers, five cities and unequal fact multiplicities
  produced the expected 36 result rows. A separate single-configuration run
  completed with two warm-ups, a custom heap and fixed ordering.

## Ownership checkpoint

- [ ] I reviewed this change and can explain its design, correctness, and trade-offs.
