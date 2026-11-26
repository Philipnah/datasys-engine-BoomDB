"""Regression checks for the benchmark method and sampling schedule."""

import unittest

from join_order import batches, parse_method


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
