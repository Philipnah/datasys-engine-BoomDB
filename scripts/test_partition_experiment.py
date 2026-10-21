"""Regression checks for reproducibility and strict acceptance of measurements."""
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("partition_experiment.py")


class ExperimentTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not SCRIPT.exists():
            raise AssertionError("Experiment runner has not been implemented")
        spec = importlib.util.spec_from_file_location("experiment", SCRIPT)
        cls.runner = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.runner)

    def test_seed_draw_order_and_headerless_generation(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "data.csv"
            metadata = self.runner.generate_dataset(path, 3, 2)
            # Independently checked Random(67) draws: choice precedes random().
            self.assertEqual(path.read_text(),
                             "1,Slava,0.11609332089126656\n"
                             "2,Taras,0.9720936675938755\n"
                             "3,Taras,0.2668498005336233\n")
            self.assertEqual(metadata["expected_row"], [2, "Taras", 0.9720936675938755])
            other = Path(folder) / "same.csv"
            self.assertEqual(metadata["sha256"],
                             self.runner.generate_dataset(other, 3, 2)["sha256"])

    def test_rotated_logs_are_read_and_statements_are_sorted(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / "engine-2.log").write_text(self.record(1) + self.record(2))
            (root / "engine-1.log").write_text(self.record(3) + self.record(4))
            (root / "engine.log").write_text(self.record(5))
            samples = self.runner.extract_samples(root, 2, 4)
            self.assertEqual([sample["repetition"] for sample in samples], [1, 2, 3, 4, 5])
            self.assertEqual([sample["durationMs"] for sample in samples], [1.25] * 5)

    def test_missing_duplicate_failed_or_incorrect_samples_are_rejected(self):
        good = [self.record(number) for number in range(1, 6)]
        bad_cases = [good[:-1], good + [good[0]],
                     good + ["date,session,2,1,ERROR,Executor,statement=SELECT table=experiment outcome=ERROR\n"],
                     [line.replace("partitionsRead=1", "partitionsRead=2") for line in good],
                     [line.replace("rowsOut=1", "rowsOut=0") for line in good],
                     [line.replace("durationMs=1.25", "durationMs=NaN") for line in good],
                     [good[0].replace("session", "other")] + good[1:]]
        for lines in bad_cases:
            with self.subTest(lines=lines), tempfile.TemporaryDirectory() as folder:
                root = Path(folder)
                (root / "engine.log").write_text("".join(lines))
                with self.assertRaises(ValueError):
                    self.runner.extract_samples(root, 2, 4)

    def test_output_requires_five_exact_typed_rows(self):
        expected = [512, "Anton", 0.00001]
        # Java and Python format scientific notation differently; compare typed values.
        self.runner.validate_output("512,Anton,1.0E-5\n" * 5, expected)
        for text in ["512,Anton,1.0E-5\n" * 4,
                     "512,Slava,1.0E-5\n" * 5,
                     "512,Anton,2.0E-5\n" * 5,
                     "512,Anton,1.0E-5,extra\n" * 5]:
            with self.subTest(text=text), self.assertRaises(ValueError):
                self.runner.validate_output(text, expected)

    def test_summary_keeps_the_first_sample_and_spread(self):
        samples = [dict(partition_size=2, durationMs=value)
                   for value in [100, 4, 3, 2, 1]]
        self.assertEqual(self.runner.summarize(samples), [dict(
            partition_size=2, samples=5, median_ms=3, minimum_ms=1, maximum_ms=100)])

    def test_runtime_changes_stop_before_launching_another_size(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            repository = root / "repo"
            (repository / "target").mkdir(parents=True)
            jar = repository / "target/BoomDB.jar"
            jar.write_bytes(b"initial jar")
            launcher = repository / "engine.sh"
            launcher.write_text("#!/bin/sh\nexit 0\n")
            launcher.chmod(0o755)
            work = root / "partition-2"
            work.mkdir()
            (root / "conditions.json").write_text(json.dumps({
                "jar_sha256": self.runner.sha256(jar),
                "launcher_sha256": self.runner.sha256(launcher)}))
            jar.write_bytes(b"rebuilt jar")
            original = self.runner.REPOSITORY
            try:
                self.runner.REPOSITORY = repository
                with self.assertRaises(ValueError):
                    self.runner.invoke_engine(work, "setup", 2)
                self.assertFalse((work / "setup.stdout").exists())
            finally:
                self.runner.REPOSITORY = original

    @staticmethod
    def record(number):
        return (f"date,session,{number},1,DEBUG,Executor,statement=SELECT table=experiment "
                "rowsOut=1 partitionsTotal=4 partitionsRead=1 partitionsPruned=3 durationMs=1.25\n")


if __name__ == "__main__":
    unittest.main()
