"""Run BoomDB's fixed-seed partition sweep and preserve every measurement artifact.

Generation, orchestration and validation use the standard library. Only the
chart command imports Matplotlib. See docs/experiment-design.md for the protocol.
"""
import argparse
import csv
import hashlib
import json
import math
import os
import platform
import random
import re
import shutil
import statistics
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path


REPOSITORY = Path(__file__).resolve().parents[1]
SEED = 67
REPETITIONS = 5
HEAP_OPTIONS = "-Xms2g -Xmx2g"
RAW_FIELDS = ["partition_size", "repetition", "query_label", "session_id",
              "statement_number", "durationMs", "rowsOut", "partitionsTotal",
              "partitionsRead", "partitionsPruned"]
SUMMARY_FIELDS = ["partition_size", "samples", "median_ms", "minimum_ms", "maximum_ms"]


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def generate_dataset(path, rows, target):
    if rows <= 0 or not 1 <= target <= rows:
        raise ValueError("Target ID must be in the nonempty dataset")
    rng = random.Random(SEED)
    expected = None
    path.parent.mkdir(parents=True, exist_ok=True)
    # Refuse overwrites so a failed run cannot silently replace its input.
    with path.open("x", encoding="ascii", newline="") as output:
        writer = csv.writer(output, lineterminator="\n")
        for identifier in range(1, rows + 1):
            row = [identifier, rng.choice(["Slava", "Vasya", "Anton", "Taras"]), rng.random()]
            writer.writerow(row)
            if identifier == target:
                expected = row
    metadata = dict(rows=rows, seed=SEED, target_id=target, expected_row=expected,
                    sha256=sha256(path), columns="id LONG, name STRING, random_double DOUBLE")
    write_json(path.with_suffix(".json"), metadata)
    return metadata


def validate_output(text, expected):
    records = list(csv.reader(text.splitlines()))
    if len(records) != REPETITIONS:
        raise ValueError(f"Expected five result rows, found {len(records)}")
    for record in records:
        if len(record) != 3:
            raise ValueError(f"Malformed result row: {record!r}")
        if [int(record[0]), record[1], float(record[2])] != expected:
            raise ValueError(f"Incorrect result row: {record!r}")


def extract_samples(log_directory, partition_size, partitions):
    samples = []
    errors = []
    # Rotation order is irrelevant: session and statement identify each sample.
    # Stream all files rather than materializing millions of decision records.
    for path in sorted(log_directory.glob("engine*.log")):
        with path.open(encoding="utf-8") as source:
            for line in source:
                fields = line.rstrip("\n").split(",", 6)
                if len(fields) != 7:
                    raise ValueError(f"Malformed log record in {path}")
                _, session, statement, _, level, logger, message = fields
                if level == "ERROR":
                    errors.append((session, statement, message))
                if logger != "Executor" or not message.startswith("statement=SELECT "):
                    continue
                values = dict(word.split("=", 1) for word in message.split() if "=" in word)
                if level != "DEBUG":
                    continue
                if values.get("table") != "experiment" or values.get("outcome") == "ERROR":
                    raise ValueError("Unexpected SELECT summary")
                duration = float(values["durationMs"])
                stats = {key: int(values[key]) for key in RAW_FIELDS[6:]}
                if not math.isfinite(duration) or duration < 0:
                    raise ValueError("Invalid duration")
                if stats != dict(rowsOut=1, partitionsTotal=partitions,
                                 partitionsRead=1, partitionsPruned=partitions - 1):
                    raise ValueError(f"Incorrect SELECT statistics: {stats}")
                number = int(statement)
                samples.append(dict(partition_size=partition_size, repetition=number,
                                    query_label="first query" if number == 1 else "subsequent query",
                                    session_id=session, statement_number=number,
                                    durationMs=duration, **stats))
    if errors:
        raise ValueError(f"Engine logged errors: {errors[:5]}")
    if len(samples) != REPETITIONS or {row["repetition"] for row in samples} != set(range(1, 6)):
        raise ValueError("Expected exactly one successful sample for each statement 1–5")
    if len({row["session_id"] for row in samples}) != 1:
        raise ValueError("Five SELECTs must share one measurement session")
    return sorted(samples, key=lambda row: row["repetition"])


def summarize(samples):
    summaries = []
    for size in sorted({row["partition_size"] for row in samples}):
        durations = [row["durationMs"] for row in samples if row["partition_size"] == size]
        if len(durations) != REPETITIONS:
            raise ValueError(f"Partition size {size} does not have five samples")
        summaries.append(dict(partition_size=size, samples=len(durations),
                              median_ms=statistics.median(durations),
                              minimum_ms=min(durations), maximum_ms=max(durations)))
    return summaries


def write_csv(path, fields, records):
    with path.open("w", encoding="utf-8", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=fields, lineterminator="\n")
        writer.writeheader()
        writer.writerows(records)


def chart(root):
    os.environ.setdefault("MPLCONFIGDIR", str(root / "matplotlib-cache"))
    os.environ.setdefault("XDG_CACHE_HOME", str(root / "cache"))
    Path(os.environ["XDG_CACHE_HOME"]).mkdir(parents=True, exist_ok=True)
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    with (root / "summary.csv").open() as source:
        records = list(csv.DictReader(source))
    sizes = [int(row["partition_size"]) for row in records]
    medians = [float(row["median_ms"]) for row in records]
    minima = [float(row["minimum_ms"]) for row in records]
    maxima = [float(row["maximum_ms"]) for row in records]
    metadata = json.loads((root / "dataset.json").read_text())
    caption = (f"{metadata['rows']:,} rows; seed {SEED}; target ID {metadata['target_id']}. "
               "Five identical SELECTs in one fresh JVM per size; no warm-up.\n"
               "Median includes the first query; error bars show minimum–maximum. "
               "Timing: binding through operator cleanup, including DEBUG logging.\n"
               "Filesystem-cache state is uncontrolled; subsequent queries do not guarantee JVM steady state.")
    fig, ax = plt.subplots(figsize=(11, 6.5))
    ax.errorbar(sizes, medians,
                yerr=[[middle - low for middle, low in zip(medians, minima)],
                      [high - middle for middle, high in zip(medians, maxima)]],
                fmt="o-", capsize=4, linewidth=1.6, markersize=4, label="Median and min–max")
    ax.set_xscale("log", base=2)
    ax.set_xticks(sizes)
    ax.set_xticklabels([str(size) for size in sizes], rotation=45, ha="right")
    ax.set_xlabel("Maximum rows per partition (rows)")
    ax.set_ylabel("SELECT duration (ms)")
    ax.set_ylim(bottom=0)
    ax.set_title("BoomDB partition-size experiment")
    ax.grid(True, alpha=0.25)
    ax.legend()
    fig.text(0.07, 0.035, caption, fontsize=8, va="bottom")
    fig.tight_layout(rect=(0, 0.16, 1, 1))
    for suffix in ["png", "pdf"]:
        fig.savefig(root / f"partition-size.{suffix}", dpi=180)
    plt.close(fig)
    (root / "chart-caption.txt").write_text(caption + "\n")


def command_output(args, cwd=REPOSITORY):
    completed = subprocess.run(args, cwd=cwd, text=True, capture_output=True, check=True)
    return (completed.stdout + completed.stderr).strip()


def snapshot(root):
    destination = root / "source"
    destination.mkdir()
    (destination / "commit.txt").write_text(command_output(["git", "rev-parse", "HEAD"]) + "\n")
    (destination / "git-status.txt").write_text(command_output(["git", "status", "--short"]) + "\n")
    patch = subprocess.run(["git", "diff", "HEAD", "--binary"], cwd=REPOSITORY,
                           capture_output=True, check=True).stdout
    (destination / "uncommitted.patch").write_bytes(patch)
    tracked = command_output(["git", "ls-files"]).splitlines()
    untracked = command_output(["git", "ls-files", "--others", "--exclude-standard"]).splitlines()
    checksums = {}
    # Exact source files supplement the patch, which cannot describe untracked files.
    for name in sorted(set(tracked + untracked)):
        source = REPOSITORY / name
        if source.is_file():
            target = destination / "repository" / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
            checksums[name] = sha256(source)
    write_json(destination / "checksums.json", checksums)
    shutil.copy2(REPOSITORY / "target/BoomDB.jar", root / "BoomDB.jar")


def record_conditions(root, resources_confirmed):
    if not resources_confirmed:
        raise ValueError("Confirm resource availability with --resources-confirmed before running")
    free = shutil.disk_usage(root).free
    if free < 20 * 1024 ** 3:
        raise ValueError("Less than 20 GiB disk space available for partitions and retained logs")
    # JVM startup checks the fixed heap configuration before any dataset is ingested.
    launcher = command_output(["java", "-Xms2g", "-Xmx2g", "-version"], cwd=root)
    conditions = dict(started_utc=datetime.now(timezone.utc).isoformat(),
                      machine_model="not collected: machine-statistics permission declined",
                      cpu=platform.processor() or platform.machine(),
                      total_ram="not collected: machine-statistics permission declined",
                      resource_feasibility="confirmed by user", disk_free_bytes=free,
                      os=platform.platform(), python=sys.version, python_executable=sys.executable,
                      build_jvm=command_output(["mvn", "-version"]), launcher_jvm=launcher,
                      java_executable=shutil.which("java"), heap_options=HEAP_OPTIONS,
                      launcher=str(REPOSITORY / "engine.sh"),
                      source_commit=command_output(["git", "rev-parse", "HEAD"]),
                      jar_sha256=sha256(REPOSITORY / "target/BoomDB.jar"),
                      launcher_sha256=sha256(REPOSITORY / "engine.sh"),
                      logging_config_sha256=sha256(REPOSITORY / "src/main/resources/log4j2.xml"),
                      protocol="first query; four subsequent queries; no warm-up; cache uncontrolled")
    write_json(root / "conditions.json", conditions)
    shutil.copy2(REPOSITORY / "src/main/resources/log4j2.xml", root / "log4j2.xml")
    return conditions


def invoke_engine(work, phase, size):
    conditions = json.loads((work.parent / "conditions.json").read_text())
    # Follow the specified absolute launcher while protecting the recorded binary.
    for relative, key in [("target/BoomDB.jar", "jar_sha256"),
                          ("engine.sh", "launcher_sha256"),
                          ("src/main/resources/log4j2.xml", "logging_config_sha256")]:
        if key in conditions and sha256(REPOSITORY / relative) != conditions[key]:
            raise ValueError(f"Runtime artifact changed during the sweep: {relative}")
    env = dict(os.environ, ENGINE_JAVA_OPTS=f"{HEAP_OPTIONS} -Dboomdb.maxRowsPerPartition={size}")
    with (work / f"{phase}.stdout").open("wb") as output, (work / f"{phase}.stderr").open("wb") as errors:
        completed = subprocess.run([str(REPOSITORY / "engine.sh"), "-f", str(work / f"{phase}.sql")],
                                   cwd=work, env=env, stdout=output, stderr=errors)
    if completed.returncode != 0:
        raise ValueError(f"{phase} failed at partition size {size}; see {work}")


def validate_ingestion(work, rows, size):
    catalogs = work / "data/catalogs"
    # Only finalized versions count; a temporary publication is not committed.
    versions = [(int(match[1]), path) for path in catalogs.glob("experiment-v*.json")
                if path.is_file() and (match := re.fullmatch(r"experiment-v([0-9]+)\.json", path.name))]
    path = max(versions, default=(0, catalogs / "experiment.json"))[1]
    catalog = json.loads(path.read_text())
    partitions = catalog["partitions"]
    count = math.ceil(rows / size)
    if not catalog["copied"] or len(partitions) != count:
        raise ValueError("Ingestion produced an incorrect partition count")
    if sum(partition["rowCount"] for partition in partitions) != rows:
        raise ValueError("Ingestion produced an incorrect row count")
    for index, partition in enumerate(partitions):
        expected_count = min(size, rows - index * size)
        if partition["rowCount"] != expected_count or partition["id"] != index:
            raise ValueError("Incorrect partition row counts or order")
        if not (work / "data/data" / partition["fileName"]).is_file():
            raise ValueError("A published partition file is missing")
        bounds = partition["statistics"][0]
        if bounds["columnName"] != "id" or [int(bounds["min"]), int(bounds["max"])] != [
                index * size + 1, index * size + expected_count]:
            raise ValueError("Incorrect sorted-ID statistics")
    return count


def run_sweep(root, smoke, resources_confirmed):
    root = root.resolve()
    if Path("/private/tmp") not in root.parents:
        raise ValueError("Run directories must be below /private/tmp, outside OneDrive")
    root.mkdir(parents=True, exist_ok=False)
    conditions = record_conditions(root, resources_confirmed)
    snapshot(root)
    rows, target = (1024, 512) if smoke else (1_000_000, 500_000)
    sizes = [2, 128, 1024] if smoke else [2 ** power for power in range(1, 19)]
    metadata = generate_dataset(root / "dataset.csv", rows, target)
    samples = []
    try:
        for size in sizes:
            work = root / f"partition-{size}"
            work.mkdir()
            print(f"Partition size {size}: ingesting {rows:,} rows", flush=True)
            (work / "setup.sql").write_text(
                "CREATE TABLE experiment (id LONG, name STRING, random_double DOUBLE);\n"
                f"COPY experiment FROM '{root / 'dataset.csv'}';\n")
            (work / "measure.sql").write_text(
                f"SELECT * FROM experiment WHERE id = {target};\n" * REPETITIONS)
            invoke_engine(work, "setup", size)
            partitions = validate_ingestion(work, rows, size)
            if (work / "setup.stdout").read_text():
                raise ValueError("Setup unexpectedly produced result rows")
            print(f"Partition size {size}: measuring five SELECTs ({partitions:,} partitions)", flush=True)
            invoke_engine(work, "measure", size)
            validate_output((work / "measure.stdout").read_text(), metadata["expected_row"])
            measured = extract_samples(work / "logs", size, partitions)
            samples.extend(measured)
            # Preserve accepted samples immediately, even if a later size fails.
            write_csv(root / "raw-results.csv", RAW_FIELDS, samples)
            write_csv(root / "summary.csv", SUMMARY_FIELDS, summarize(samples))
            write_json(root / "progress.json", dict(completed_sizes=[s["partition_size"]
                       for s in summarize(samples)], accepted_samples=len(samples)))
            print(f"Partition size {size}: accepted five samples; "
                  f"median {statistics.median(s['durationMs'] for s in measured):.6f} ms", flush=True)
        chart(root)
        conditions.update(completed_utc=datetime.now(timezone.utc).isoformat(),
                          status="complete", measurements=len(samples), sizes=sizes)
        write_json(root / "conditions.json", conditions)
        print(f"Completed {len(samples)} measurements. Artifacts: {root}", flush=True)
    except Exception as error:
        conditions.update(status="failed", failure=str(error), accepted_samples=len(samples))
        write_json(root / "conditions.json", conditions)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subcommands = parser.add_subparsers(dest="command", required=True)
    generate = subcommands.add_parser("generate", help="Generate the full reproducible CSV")
    generate.add_argument("path", type=Path)
    for name in ["smoke-run", "full-run"]:
        run = subcommands.add_parser(name)
        run.add_argument("directory", type=Path, help="New directory below /private/tmp")
        run.add_argument("--resources-confirmed", action="store_true")
    plot = subcommands.add_parser("chart", help="Regenerate PNG and PDF from summary.csv")
    plot.add_argument("directory", type=Path)
    args = parser.parse_args()
    try:
        if args.command == "generate":
            print(json.dumps(generate_dataset(args.path, 1_000_000, 500_000), indent=2))
        elif args.command == "chart":
            chart(args.directory.resolve())
        else:
            run_sweep(args.directory, args.command == "smoke-run", args.resources_confirmed)
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        print(f"Experiment stopped: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
