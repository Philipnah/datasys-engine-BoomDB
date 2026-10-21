# Code change: Reproducible partition-size experiment

- Date: 2026-10-11
- Contributor: Philip Han
- Assistance: AI-assisted
- AI tool/model: Codex, GPT-6

## Request summary

Implement and execute the existing detailed partition-size experiment: one
fixed-seed million-row dataset, eighteen partition sizes, five SELECTs per size,
strict validation, preserved artifacts, and median/minimum/maximum charts.

## Change and assistance summary

Codex added the CLI partition-size property and SELECT timing summaries in
Executor. The timer includes binding, planning, execution and operator cleanup;
it excludes parsing, startup, ingestion, output and the summary log itself.
Failures emit ERROR records and do not emit successful timing samples. The
launcher returns a nonzero status for failed scripts after logging session stop.

The Python runner uses the standard library for deterministic generation,
orchestration, log extraction and statistics, and Matplotlib for PNG/PDF charts.
It validates the returned row, catalog row/partition counts and sorted-ID bounds,
and requires exactly five successful samples in one session with the expected
partition statistics. It retains each run's source snapshot, patch, packaged
JAR, checksums, conditions, dataset, SQL, stdout/stderr and rotated logs.
Runtime hashes are checked before each invocation to prevent a rebuild from
silently mixing binaries across sizes. Existing DEBUG logging remains enabled.

Implementation reuses the existing planner, operators and storage APIs. This
keeps the experiment focused but means measured time includes catalog loading
and per-partition diagnostic logging. Small partitions create many files and
large logs; the first sample stays in the median and cache state is uncontrolled.
SQL literal escaping is not supported by the current grammar, so run-directory
paths containing apostrophes are unsupported. Machine-statistics access was
declined; the user confirmed resource feasibility, and unavailable model/RAM
fields are identified explicitly rather than inferred.

## Changed files

- `src/main/java/dk/itu/boomdb/Engine.java` — CLI property validation and failure exit status
- `src/main/java/dk/itu/boomdb/Executor.java` — SELECT duration/statistics summaries and ERROR records
- `src/test/java/dk/itu/boomdb/ExperimentIT.java` — configuration, timing, failures and launcher checks
- `scripts/partition_experiment.py` — generation, smoke/full sweeps, extraction and charts
- `scripts/test_partition_experiment.py` — reproducibility and acceptance regression checks
- `docs/experiment-design.md` — valid SQL, timing/cache protocol and reproduction commands
- `.gitignore` — exclude Python bytecode caches
- `docs/ai-usage/2026-10-11-partition-size-experiment.md` — this learning log

## Validation

- New Java behavior tests initially failed for the missing configuration and timing records, then passed after implementation.
- `mvn -B verify` — 109 Java checks passed (52 unit, 57 integration), including 12 experiment checks.
- `mvn -B package` — packaged successfully; Maven reported dependency resource overlap warnings.
- `python3 -m unittest discover -s scripts -p 'test_*.py'` — six checks passed; the runtime-change regression was observed failing before the checksum guard was added.
- Smoke sweep — 15 samples accepted for 1,024 rows and sizes 2, 128 and 1,024; first and subsequent samples retained.
- Smoke PNG and rendered PDF — inspected for legible axes, caption and error bars.
- Full sweep — all 90 samples accepted across 18 partition sizes, using the fixed 2 GB heap and one shared million-row CSV. Every query returned ID 500000, read one partition and pruned all other partitions. Artifacts are preserved at `/private/tmp/boomdb-full-20261011-1410`; the final smoke run is separate at `/private/tmp/boomdb-smoke-20261011-1405`.
- Full PNG and rendered PDF — inspected; base-2 logarithmic x-axis, linear y-axis, median curve, minimum–maximum bars and protocol caption are present.
- Final artifact audit — re-extracted all 90 samples from the retained logs, checked 18 distinct measurement sessions and all result rows, recomputed the summary, and verified CSV/JAR checksums. All checks passed; 142 rotated log files were retained.
- Read-only code review — runtime artifact-consistency issue fixed; unsupported apostrophes documented.
- `git diff --check` — passed. Git was used only for read-only inspection.

## Ownership checkpoint

- [ ] I reviewed this change and can explain its design, correctness, and trade-offs.
