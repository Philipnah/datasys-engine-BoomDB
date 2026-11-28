"""Regression checks for benchmark sampling and reporting."""

import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

from join_order import Timing, batches, parse_method, write_runtime_report


class MethodTest(unittest.TestCase):
    def test_uneven_city_sizes_and_asymmetric_facts(self):
        options = ["--customers", "13", "--cities", "5", "--orders-per-customer", "3",
                   "--shipments-per-customer", "4"]
        self.assertEqual(36, parse_method([*options, "--city", "2"]).result_rows)
        self.assertEqual(24, parse_method([*options, "--city", "4"]).result_rows)
        self.assertEqual(100_000, parse_method([]).result_rows)

    def test_repetitions_and_jvm_boundaries(self):
        # Each tuple is one JVM invocation. Order and statement grouping affect warmth.
        for schedule, expected in {
            "rotating": [("min", [1]), ("max", [1]), ("max", [2]), ("min", [2])],
            "fixed": [("min", [1]), ("max", [1]), ("min", [2]), ("max", [2])],
            "grouped": [("min", [1]), ("min", [2]), ("max", [1]), ("max", [2])],
            "shared-jvm": [("min", [1, 2]), ("max", [1, 2])],
        }.items():
            with self.subTest(schedule=schedule):
                method = parse_method(["--configurations", "min", "max", "--repetitions", "2",
                                       "--schedule", schedule])
                self.assertEqual(expected, [(name, list(reps)) for name, reps in batches(method)])

    def test_runtime_chart_groups_minimum_median_and_maximum(self):
        try:
            from matplotlib.figure import Figure
        except ModuleNotFoundError:
            self.skipTest("Matplotlib is required to render the benchmark chart")

        method = parse_method(["--configurations", "max", "min", "--repetitions", "3"])
        timings = [Timing(config, config, repetition, duration, "", 1)
                   for config, durations in [("max", [900, 400, 600]), ("min", [150, 100, 120])]
                   for repetition, duration in enumerate(durations, start=1)]
        with TemporaryDirectory() as directory:
            with patch.object(Figure, "savefig", autospec=True, side_effect=Figure.savefig) as savefig:
                write_runtime_report(Path(directory), method, timings)
            self.assertTrue((Path(directory) / "runtime.png").is_file())

        axis, = savefig.call_args.args[0].axes
        self.assertEqual("Join benchmark", axis.get_title())
        self.assertEqual(["max", "min"], [tick.get_text() for tick in axis.get_xticklabels()])
        self.assertEqual(["Minimum", "Median", "Maximum"], axis.get_legend_handles_labels()[1])
        self.assertEqual([[400, 100], [600, 120], [900, 150]],
                         [[bar.get_height() for bar in series] for series in axis.containers])
        self.assertEqual(3, len({series[0].get_facecolor() for series in axis.containers}))
        self.assertEqual(3, len({series[0].get_x() for series in axis.containers}))
