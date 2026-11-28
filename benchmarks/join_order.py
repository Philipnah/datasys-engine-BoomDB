# /// script
# requires-python = ">=3.12"
# dependencies = ["matplotlib==3.11.2"]
# ///
"""Run the Exercise 12 join benchmark against the packaged BoomDB CLI."""

import argparse
import csv
import platform
import random
import shlex
import shutil
import sys
import subprocess
from collections import Counter
from pathlib import Path
from statistics import median
from tempfile import mkdtemp
from typing import NamedTuple

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / "target" / "BoomDB.jar"
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
"""
JOIN_SQL = "SELECT * FROM logs, runs WHERE sessionId = r_session AND className = 'HashJoinOperator'"


class Timing(NamedTuple):
    session_id: str
    configuration: str
    repetition: int
    duration_ms: int
    join_order: str
    statement_number: int


class Method(argparse.Namespace):
    @property
    def matching_customers(self):
        return len(range(self.city, self.customers, self.cities))

    @property
    def result_rows(self):
        return self.matching_customers * self.orders_per_customer * self.shipments_per_customer

    @property
    def heap(self):
        return [f"-Xms{self.initial_heap_mb}m", f"-Xmx{self.max_heap_mb}m"]


def parse_method(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.ArgumentDefaultsHelpFormatter)
    for flag, default, help_text in [
        ("customers", 1000, "number of unique customers"),
        ("cities", 100, "number of round-robin cities"),
        ("orders-per-customer", 100, "orders for each customer"),
        ("shipments-per-customer", 100, "shipments for each customer"),
        ("repetitions", 5, "measured executions per configuration"),
        ("initial-heap-mb", 1024, "initial JVM heap in MiB"),
        ("max-heap-mb", 2048, "maximum JVM heap in MiB"),
    ]:
        parser.add_argument("--" + flag, type=int, default=default, help=help_text)
    parser.add_argument("--city", type=int, default=argparse.SUPPRESS,
                        help="zero-based filter city (default 42 or last city if smaller)")
    parser.add_argument("--seed", type=int, default=69, help="foreign-key shuffle seed")
    parser.add_argument("--warmups", type=int, default=1, help="untimed min queries in separate JVMs before sampling")
    parser.add_argument("--schedule", choices=["rotating", "fixed", "grouped", "shared-jvm"], default="rotating",
                        help="rotate or fix round order, or group repetitions with fresh or shared JVMs")
    parser.add_argument("--configurations", nargs="+", choices=CONFIGURATIONS, default=list(CONFIGURATIONS),
                        help="configurations to run in the given order")
    method = parser.parse_args(argv, namespace=Method())
    for name in ("customers", "cities", "orders_per_customer", "shipments_per_customer",
                 "repetitions", "initial_heap_mb", "max_heap_mb"):
        if getattr(method, name) < 1:
            parser.error("--" + name.replace("_", "-") + " must be positive")
    if not hasattr(method, "city"):
        method.city = min(42, method.cities - 1)
    if not 0 <= method.city < method.cities:
        parser.error("--city must be between zero and --cities minus one")
    if method.warmups < 0:
        parser.error("--warmups must be nonnegative")
    if method.initial_heap_mb > method.max_heap_mb:
        parser.error("--initial-heap-mb must fit within --max-heap-mb")
    if len(set(method.configurations)) != len(method.configurations):
        parser.error("each configuration may be selected once")
    return method


def write_csv(path, rows, header=None):
    with path.open("w", encoding="ascii", newline="") as file:
        writer = csv.writer(file, lineterminator="\n")
        if header is not None:
            writer.writerow(header)
        writer.writerows(rows)


def generate_inputs(directory, method):
    names = [f"customer{i:04d}" for i in range(method.customers)]
    write_csv(directory / "customers.csv",
              ((name, f"city{i % method.cities:03d}") for i, name in enumerate(names)))
    rng = random.Random(method.seed)
    for table, copies in (("orders", method.orders_per_customer), ("shipments", method.shipments_per_customer)):
        # Every customer occurs the configured number of times, spread across the partitions.
        keys = names * copies
        rng.shuffle(keys)
        write_csv(directory / f"{table}.csv", (
            (i, key, i % 100 + 1 if table == "orders" else (i % 1_000 + 1) / 10)
            for i, key in enumerate(keys, start=1)
        ))

    (directory / "setup.sql").write_text(SETUP_SQL, encoding="ascii")
    for name, tables in FROM_LISTS.items():
        (directory / f"{name}.sql").write_text(
            f"SELECT * FROM {tables}\n"
            f"WHERE o_custname = s_custname AND s_custname = c_name AND c_city = 'city{method.city:03d}';\n",
            encoding="ascii",
        )
    selects = [JOIN_SQL + ";"]
    if method.schedule == "shared-jvm":
        # A session contains several statements. Match their repetition labels inside BoomDB.
        selects = [f"{JOIN_SQL} AND statementNumber = {i} AND r_repetition = {i};"
                   for i in range(1, method.repetitions + 1)]
    (directory / "analysis.sql").write_text(ANALYSIS_SQL + "\n".join(selects) + "\n", encoding="ascii")


def run_engine(directory, method, mode, script):
    result = subprocess.run(
        ["java", *method.heap, "-jar", str(JAR), "-j", mode, "-f", script],
        cwd=directory, capture_output=True, text=True,
    )
    # The CLI also logs SQL errors that do not reach the process exit status.
    if result.returncode != 0 or ",ERROR," in result.stderr:
        details = "\n".join(result.stderr.splitlines()[-8:])
        raise RuntimeError(f"{script} ({mode}) failed\n{details}")
    return result


def canonical_rows(lines, from_list):
    rows = csv.reader(lines)
    if from_list == "max":
        # SELECT * follows the FROM list even when the executor reorders joins.
        return sorted(row[6:] + row[3:6] + row[:3] for row in rows)
    return sorted(rows)


def log_fields(message):
    # The SELECT summary's table field can contain spaces between table names.
    return dict(field.split("=", 1) for field in message.split() if "=" in field)


def batches(method):
    configs = method.configurations
    repetitions = range(1, method.repetitions + 1)
    if method.schedule in ("grouped", "shared-jvm"):
        for config in configs:
            if method.schedule == "shared-jvm":
                yield config, repetitions
            else:
                for repetition in repetitions:
                    yield config, [repetition]
        return
    for repetition in repetitions:
        # Interleave configurations and optionally rotate their position each round.
        offset = (repetition - 1) % len(configs) if method.schedule == "rotating" else 0
        for config in configs[offset:] + configs[:offset]:
            yield config, [repetition]


def measure(directory, method, expected):
    timings = []
    for config, repetitions in batches(method):
        mode, from_list = CONFIGURATIONS[config]
        script = f"{from_list}.sql"
        if len(repetitions) > 1:
            batch = directory / f"{from_list}-repeated.sql"
            batch.write_text((directory / script).read_text() * len(repetitions), encoding="ascii")
            script = batch.name
        result = run_engine(directory, method, mode, script)
        lines = result.stdout.splitlines()
        if len(lines) != method.result_rows * len(repetitions):
            raise RuntimeError(f"{config} returned an unexpected total number of rows")
        # The console appender mirrors the file log and isolates this JVM's statements.
        events = [row for row in csv.reader(result.stderr.splitlines()) if len(row) == 7]
        for statement, repetition in enumerate(repetitions, start=1):
            records = [row for row in events if row[2] == str(statement)]
            [summary] = [row for row in records if row[5] == "Executor" and row[6].startswith("statement=SELECT ")]
            [plan] = [row[6] for row in records if row[5] == "Planner" and row[6].startswith("statement=SELECT joinOrder=")]
            fields = log_fields(summary[6])
            start = (statement - 1) * method.result_rows
            rows = canonical_rows(lines[start:start + method.result_rows], from_list)
            if int(fields["rowsOut"]) != method.result_rows or (expected is not None and rows != expected):
                raise RuntimeError(f"{config} repetition {repetition} returned unexpected rows")
            if expected is None:
                expected = rows
            order = log_fields(plan)["joinOrder"]
            duration = int(fields["durationMs"])
            timings.append(Timing(summary[1], config, repetition, duration, order, statement))
            print(f"{config} {repetition}/{method.repetitions}  {duration} ms  {order}", flush=True)
    return timings


def analyze_joins(directory, method, timings):
    runs = {(t.session_id, str(t.statement_number)): (t.configuration, str(t.repetition)) for t in timings}
    write_csv(directory / "runs.csv", ((session, *run) for (session, _), run in runs.items()))

    # Keep the output of the BoomDB analysis join before summarizing it for the report.
    stdout = run_engine(directory, method, "optimized", "analysis.sql").stdout
    (directory / "labeled-joins.csv").write_text(stdout, encoding="ascii")
    pending = {(session, statement, step) for session, statement in runs for step in (1, 2)}
    sizes = Counter()
    for row in csv.reader(stdout.splitlines()):
        _, session, statement, _, _, logger, message, run_session, config, repetition = row
        fields = log_fields(message)
        step, actual = int(fields["join"]), int(fields["rowsOut"])
        if (session != run_session or runs.get((session, statement)) != (config, repetition)
                or logger != "HashJoinOperator"
                or fields["completed"] != "true" or (session, statement, step) not in pending):
            raise RuntimeError(f"Unexpected join log for {config} session {session}, step {step}")
        pending.remove((session, statement, step))
        expected = method.result_rows if step == 2 else {
            "min": method.matching_customers * method.shipments_per_customer,
            "max": method.customers * method.orders_per_customer * method.shipments_per_customer,
        }.get(config)
        if expected is not None and actual != expected:
            raise RuntimeError(f"{config} join {step} emitted {actual} rows, expected {expected}")
        sizes[config, step, fields["tables"], fields["buildTables"], fields["probeTables"],
              fields["estRows"], actual] += 1
    if pending:
        raise RuntimeError(f"Missing {len(pending)} completed join logs from measured sessions")
    write_csv(directory / "join-sizes.csv", ((*key, count) for key, count in sorted(sizes.items())),
              ("configuration", "join", "tables", "build_tables", "probe_tables",
               "estimated_rows", "actual_rows", "executions"))


def write_runtime_report(directory, method, timings):
    from matplotlib.figure import Figure

    write_csv(directory / "timings.csv", timings, Timing._fields)
    summary = []
    for config in method.configurations:
        durations = [t.duration_ms for t in timings if t.configuration == config]
        summary.append((config, len(durations), median(durations), min(durations), max(durations)))
    write_csv(directory / "runtime-summary.csv", summary,
              ("configuration", "executions", "median_ms", "min_ms", "max_ms"))

    configs, _, medians, lows, highs = zip(*summary)
    figure = Figure(figsize=(7, 4), layout="constrained")
    axis = figure.subplots()
    centers = range(len(configs))
    width = 0.24
    # Place the median on each configuration tick and offset the range endpoints.
    for offset, label, values, color in (
        (-1, "Minimum", lows, "#0072B2"),
        (0, "Median", medians, "#E69F00"),
        (1, "Maximum", highs, "#009E73"),
    ):
        positions = [center + offset * width for center in centers]
        bars = axis.bar(positions, values, width=width, label=label, color=color)
        axis.bar_label(bars, fmt="%g", padding=3, fontsize=8)
    axis.set_xticks(centers, configs)
    axis.margins(y=0.20)
    axis.set(xlabel="Join configuration", ylabel="SELECT runtime (ms)", ylim=(0, None),
             title="Join benchmark")
    axis.legend(loc="upper center", ncols=3, frameon=False)
    axis.set_axisbelow(True)
    axis.grid(axis="y", alpha=0.3)
    figure.savefig(directory / "runtime.png", dpi=220)


def write_conditions(directory, method):
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
    replay = shlex.join(["uv", "run", "--no-project", "--python", "3.12", "benchmarks/join_order.py", *sys.argv[1:]])
    (directory / "conditions.txt").write_text(
        f"Machine/OS: {machine}\nJVM:\n{jvm}Heap flags: {' '.join(method.heap)}\n"
        f"Git HEAD: {commit}\nWorking tree has changes: {bool(changes)}\n"
        f"Python: {platform.python_version()}\nEffective parameters: {vars(method)}\n"
        f"Untimed min queries before measurement: {method.warmups}. OS cache left in place.\n"
        f"Measurement JVM: {'one per configuration' if method.schedule == 'shared-jvm' else 'one per query'}\n"
        f"Replay from repository root: {replay}\n"
        "Timing: Executor SELECT durationMs covers binding, planning, and execution through operator close.\n",
        encoding="utf-8",
    )


def main():
    method = parse_method()
    if not JAR.is_file():
        raise SystemExit("Build target/BoomDB.jar with mvn -B verify first.")
    root = JAR.parent / "join-order-benchmark"
    root.mkdir(exist_ok=True)
    directory = Path(mkdtemp(prefix="run-", dir=root))
    print(f"Benchmark directory {directory}", flush=True)
    write_conditions(directory, method)
    generate_inputs(directory, method)
    run_engine(directory, method, "optimized", "setup.sql")

    expected = None
    for _ in range(method.warmups):
        result = run_engine(directory, method, "syntax", "min.sql")
        expected = canonical_rows(result.stdout.splitlines(), "min")
        if len(expected) != method.result_rows:
            raise RuntimeError(f"Warm-up returned {len(expected)} rows, expected {method.result_rows}")
    timings = measure(directory, method, expected)
    # Analysis writes additional logs, so ingest a snapshot of the experiment including rotated files.
    logs = directory / "logs"
    archives = sorted(logs.glob("engine-*.log"), key=lambda path: int(path.stem.split("-")[1]))
    with (directory / "engine-log.csv").open("wb") as snapshot:
        for path in [*archives, logs / "engine.log"]:
            with path.open("rb") as source:
                shutil.copyfileobj(source, snapshot)
    analyze_joins(directory, method, timings)
    write_runtime_report(directory, method, timings)
    print(directory)


if __name__ == "__main__":
    main()
