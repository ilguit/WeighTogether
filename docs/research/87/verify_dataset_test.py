#!/usr/bin/env python3
"""Regression tests for the issue #87 dataset contract."""

import csv
import importlib.util
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("verify_dataset.py")
SPEC = importlib.util.spec_from_file_location("verify_dataset", MODULE_PATH)
verify_dataset = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verify_dataset)


class DatasetContractTest(unittest.TestCase):
    def write_csv(self, path, fields, rows):
        with path.open("w", encoding="utf-8", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=fields, lineterminator="\n")
            writer.writeheader()
            writer.writerows(rows)

    def test_rejects_reordered_columns(self):
        source = verify_dataset.POPULATION
        fields, rows = verify_dataset.read_csv(source)
        fields[0], fields[1] = fields[1], fields[0]

        with tempfile.TemporaryDirectory() as directory:
            candidate = Path(directory) / source.name
            self.write_csv(candidate, fields, rows)

            with self.assertRaisesRegex(AssertionError, "column order changed"):
                verify_dataset.read_validated_csv(
                    candidate,
                    verify_dataset.REQUIRED_POPULATION_FIELDS,
                )

    def test_rejects_unknown_sample_type(self):
        source = verify_dataset.CONTEXTS
        fields, rows = verify_dataset.read_csv(source)
        rows[0]["sample_type"] = "unknown sample"

        with tempfile.TemporaryDirectory() as directory:
            candidate = Path(directory) / source.name
            self.write_csv(candidate, fields, rows)

            with self.assertRaisesRegex(AssertionError, "unknown sample_type"):
                verify_dataset.read_validated_csv(
                    candidate,
                    verify_dataset.REQUIRED_CONTEXT_FIELDS,
                )

    def test_rejects_unknown_evidence_tier(self):
        source = verify_dataset.EVIDENCE_PRIORITY
        fields, rows = verify_dataset.read_csv(source)
        rows[0]["evidence_tier"] = "population"

        with tempfile.TemporaryDirectory() as directory:
            candidate = Path(directory) / source.name
            self.write_csv(candidate, fields, rows)

            with self.assertRaisesRegex(AssertionError, "unknown evidence tier"):
                verify_dataset.read_evidence_priority(candidate)

    def test_rejects_reordered_evidence_columns(self):
        source = verify_dataset.EVIDENCE_PRIORITY
        fields, rows = verify_dataset.read_csv(source)
        fields[-1], fields[-2] = fields[-2], fields[-1]

        with tempfile.TemporaryDirectory() as directory:
            candidate = Path(directory) / source.name
            self.write_csv(candidate, fields, rows)

            with self.assertRaisesRegex(AssertionError, "column order changed"):
                verify_dataset.read_evidence_priority(candidate)


if __name__ == "__main__":
    unittest.main()
