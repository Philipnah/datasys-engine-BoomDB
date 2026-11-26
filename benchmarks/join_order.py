# /// script
# requires-python = ">=3.12"
# dependencies = ["matplotlib==3.11.2"]
# ///
"""Run the Exercise 12 join-order benchmark against the packaged BoomDB CLI."""

import csv
import os
import platform
import random
import shutil
import subprocess
from collections import Counter
from pathlib import Path
from statistics import median
from tempfile import mkdtemp

from matplotlib.figure import Figure


ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / "target" / "BoomDB.jar"
HEAP = ("-Xms1g", "-Xmx2g")
SEED = 12
REPETITIONS = 5
RESULT_ROWS = 100_000
CONFIGURATIONS = {
    "min": ("syntax", "min"),
    "max": ("syntax", "max"),
    "optimized-min": ("optimized", "min"),
    "optimized-max": ("optimized", "max"),
}
FROM_LISTS = {
    "min": "customers, shipments, orders",
    "max": "orders, shipments, customers",
}
SETUP_SQL = """\
CREATE TABLE customers (c_name STRING, c_city STRING);
CREATE TABLE orders (o_orderkey LONG, o_custname STRING, o_quantity LONG);
CREATE TABLE shipments (s_shipkey LONG, s_custname STRING, s_weight DOUBLE);
COPY customers FROM 'customers.csv';
COPY orders FROM 'orders.csv';
COPY shipments FROM 'shipments.csv';
"""
ANALYSIS_SQL = """\
CREATE TABLE logs (timestamp STRING, sessionId STRING, statementNumber LONG,
                   threadId LONG, logLevel STRING, className STRING, logMessage STRING);
CREATE TABLE runs (r_session STRING, r_config STRING, r_repetition LONG);
COPY logs FROM 'engine-log.csv';
COPY runs FROM 'runs.csv';
SELECT * FROM logs, runs WHERE sessionId = r_session AND className = 'HashJoinOperator';
"""


def write_csv(path, rows, header=None):
    with path.open("w", encoding="ascii", newline="") as file:
        writer = csv.writer(file, lineterminator="\n")
        if header is not None:
            writer.writerow(header)
        writer.writerows(rows)


def generate_inputs(directory):
    names = [f"customer{i:04d}" for i in range(1_000)]
    write_csv(directory / "customers.csv",
              ((name, f"city{i % 100:03d}") for i, name in enumerate(names)))
    rng = random.Random(SEED)
    for table in ("orders", "shipments"):
        # Every customer occurs exactly 100 times, spread across the partitions.
        keys = names * 100
        rng.shuffle(keys)
        write_csv(directory / f"{table}.csv", (
            (i, key, i % 100 + 1 if table == "orders" else (i % 1_000 + 1) / 10)
            for i, key in enumerate(keys, start=1)
        ))

    (directory / "setup.sql").write_text(SETUP_SQL, encoding="ascii")
    for name, tables in FROM_LISTS.items():
        (directory / f"{name}.sql").write_text(
            f"SELECT * FROM {tables}\n"
            "WHERE o_custname = s_custname AND s_custname = c_name AND c_city = 'city042';\n",
            encoding="ascii",
        )
    (directory / "analysis.sql").write_text(ANALYSIS_SQL, encoding="ascii")


def run_engine(directory, mode, script):
    result = subprocess.run(
        ["java", *HEAP, "-jar", str(JAR), "-j", mode, "-f", script],
        cwd=directory, capture_output=True, text=True,
    )
    # The CLI also logs SQL errors that do not reach the process exit status.
    if result.returncode != 0 or ",ERROR," in result.stderr:
        details = "\n".join(result.stderr.splitlines()[-8:])
        raise RuntimeError(f"{script} ({mode}) failed\n{details}")
    return result.stdout


def canonical_rows(stdout, from_list):
    rows = csv.reader(stdout.splitlines())
    if from_list == "max":
        # SELECT * follows the FROM list even when the executor reorders joins.
        return sorted(row[6:] + row[3:6] + row[:3] for row in rows)
    return sorted(rows)


def log_fields(message):
    # The SELECT summary's table field can contain spaces between table names.
    return dict(field.split("=", 1) for field in message.split() if "=" in field)


def measure(directory, log_path, expected):
    timings = []
    configs = list(CONFIGURATIONS)
    with log_path.open(encoding="ascii", newline="") as log:
        log.seek(0, os.SEEK_END)
        for repetition in range(1, REPETITIONS + 1):
            # Interleave configurations and rotate their position each round.
            offset = (repetition - 1) % len(configs)
            for config in configs[offset:] + configs[:offset]:
                mode, from_list = CONFIGURATIONS[config]
                stdout = run_engine(directory, mode, f"{from_list}.sql")
                if canonical_rows(stdout, from_list) != expected:
                    raise RuntimeError(f"{config} repetition {repetition} returned different rows")

                events = list(csv.reader(log.readlines()))
                [summary] = [row for row in events if row[2] == "1" and row[5] == "Executor"
                             and row[6].startswith("statement=SELECT ")]
                session = summary[1]
                [plan] = [row[6] for row in events if row[1:3] == [session, "1"]
                          and row[5] == "Planner" and row[6].startswith("statement=SELECT joinOrder=")]
                duration = int(log_fields(summary[6])["durationMs"])
                order = log_fields(plan)["joinOrder"]
                timings.append((session, config, repetition, duration, order))
                print(f"{config} {repetition}/{REPETITIONS}  {duration} ms  {order}", flush=True)
    return timings


def analyze_joins(directory, timings):
    runs = {session: (config, str(repetition)) for session, config, repetition, _, _ in timings}
    write_csv(directory / "runs.csv", ((session, *run) for session, run in runs.items()))

    # Keep the output of the BoomDB analysis join before summarizing it for the report.
    stdout = run_engine(directory, "optimized", "analysis.sql")
    (directory / "labeled-joins.csv").write_text(stdout, encoding="ascii")
    pending = {(session, step) for session in runs for step in (1, 2)}
    sizes = Counter()
    for row in csv.reader(stdout.splitlines()):
        _, session, statement, _, _, logger, message, run_session, config, repetition = row
        fields = log_fields(message)
        step, actual = int(fields["join"]), int(fields["rowsOut"])
        if (session != run_session or runs.get(session) != (config, repetition)
                or statement != "1" or logger != "HashJoinOperator"
                or fields["completed"] != "true" or (session, step) not in pending):
            raise RuntimeError(f"Unexpected join log for {config} session {session}, step {step}")
        pending.remove((session, step))
        expected = RESULT_ROWS if step == 2 else {"min": 1_000, "max": 10_000_000}.get(config)
        if expected is not None and actual != expected:
            raise RuntimeError(f"{config} join {step} emitted {actual} rows, expected {expected}")
        sizes[config, step, fields["tables"], fields["buildTables"], fields["probeTables"],
              fields["estRows"], actual] += 1
    if pending:
        raise RuntimeError(f"Missing {len(pending)} completed join logs from measured sessions")
    write_csv(directory / "join-sizes.csv", ((*key, count) for key, count in sorted(sizes.items())),
              ("configuration", "join", "tables", "build_tables", "probe_tables",
               "estimated_rows", "actual_rows", "executions"))


def write_runtime_report(directory, timings):
    write_csv(directory / "timings.csv", timings,
              ("session_id", "configuration", "repetition", "duration_ms", "join_order"))
    summary = []
    for config in CONFIGURATIONS:
        durations = [duration for _, name, _, duration, _ in timings if name == config]
        summary.append((config, len(durations), median(durations), min(durations), max(durations)))
    write_csv(directory / "runtime-summary.csv", summary,
              ("configuration", "executions", "median_ms", "min_ms", "max_ms"))

    configs, _, medians, lows, highs = zip(*summary)
    figure = Figure(figsize=(7, 4), layout="constrained")
    axis = figure.subplots()
    axis.errorbar(configs, medians,
                  yerr=([mid - low for mid, low in zip(medians, lows)],
                        [high - mid for mid, high in zip(medians, highs)]),
                  fmt="o", markersize=4, capsize=4, color="#245b82")
    for config, low, high in zip(configs, lows, highs):
        axis.annotate(f"{low}–{high}", (config, high), xytext=(0, 7),
                      textcoords="offset points", ha="center", fontsize=9)
    axis.margins(y=0.10)
    axis.set(xlabel="Join configuration", ylabel="SELECT runtime (ms)", ylim=(0, None),
             title=f"Join-order benchmark · median and range of {REPETITIONS} runs")
    axis.grid(axis="y", alpha=0.3)
    figure.savefig(directory / "runtime.png", dpi=220)


def write_conditions(directory):
    machine = platform.platform()
    if platform.system() == "Darwin":
        hardware = subprocess.check_output(
            ["system_profiler", "SPHardwareDataType", "-detailLevel", "mini"], text=True,
        )
        machine += " / " + " / ".join(line.strip() for line in hardware.splitlines()
                                      if line.strip().startswith(("Model Identifier:", "Chip:", "Memory:")))
    jvm = subprocess.check_output(["java", "-version"], stderr=subprocess.STDOUT, text=True)
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    changes = subprocess.check_output(
        ["git", "--no-optional-locks", "status", "--porcelain"], cwd=ROOT, text=True,
    )
    (directory / "conditions.txt").write_text(
        f"Machine/OS: {machine}\nJVM:\n{jvm}Heap flags: {' '.join(HEAP)}\n"
        f"Git HEAD: {commit}\nWorking tree has changes: {bool(changes)}\n"
        f"Python: {platform.python_version()}\nSeed: {SEED}\nRepetitions per configuration: {REPETITIONS}\n"
        "Cache: filesystem warmed by an untimed min query, fresh JVM per execution.\n"
        "Timing: Executor SELECT durationMs covers binding, planning, and execution through operator close.\n",
        encoding="utf-8",
    )


def main():
    if not JAR.is_file():
        raise SystemExit("Build target/BoomDB.jar with mvn -B verify first.")
    root = JAR.parent / "join-order-benchmark"
    root.mkdir(exist_ok=True)
    directory = Path(mkdtemp(prefix="run-", dir=root))
    print(f"Benchmark directory {directory}", flush=True)
    write_conditions(directory)
    generate_inputs(directory)
    run_engine(directory, "optimized", "setup.sql")

    expected = canonical_rows(run_engine(directory, "syntax", "min.sql"), "min")
    if len(expected) != RESULT_ROWS:
        raise RuntimeError(f"Warm-up returned {len(expected)} rows, expected {RESULT_ROWS}")
    log_path = directory / "logs" / "engine.log"
    timings = measure(directory, log_path, expected)
    # Analysis writes additional logs, so ingest a snapshot of the experiment.
    shutil.copyfile(log_path, directory / "engine-log.csv")
    analyze_joins(directory, timings)
    write_runtime_report(directory, timings)
    print(directory)


if __name__ == "__main__":
    main()
