#!/usr/bin/env python3
import importlib.util
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("issue83_validate", HERE / "validate.py")
VALIDATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VALIDATOR)


class Issue83DatasetTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest = VALIDATOR.validate_manifest()
        cls.by_rank = {int(row["rank"]): row for row in cls.manifest}

    def test_full_validator(self):
        VALIDATOR.validate()

    def test_exact_rank_and_count_regression(self):
        self.assertEqual(list(self.by_rank), list(range(11, 31)))
        self.assertEqual(
            [int(self.by_rank[rank]["registration_count"]) for rank in self.by_rank],
            VALIDATOR.EXPECTED_COUNTS,
        )

    def test_source_priority_and_required_gap_annotations(self):
        self.assertTrue(all(row["adult_source_priority"] in VALIDATOR.PRIORITIES for row in self.manifest))
        self.assertTrue(all(row["age_coverage"] in {"gap", "partial"} for row in self.manifest))
        self.assertEqual(self.by_rank[28]["adult_source_priority"], "secondary_fallback_ambiguous")
        self.assertEqual(self.by_rank[30]["adult_source_priority"], "primary_official_non_numeric")

    def test_varieties_are_not_collapsed_as_duplicates(self):
        schnauzers = [self.by_rank[rank] for rank in (22, 27, 29)]
        self.assertEqual(len({row["catalog_id"] for row in schnauzers}), 3)
        self.assertEqual(len({row["variety_scope"] for row in schnauzers}), 3)
        self.assertIn("smooth-haired", self.by_rank[23]["variety_scope"])
        self.assertIn("Japanese", self.by_rank[28]["variety_scope"])

    def test_all_manifest_foreign_keys_resolve(self):
        for row in self.manifest:
            package = HERE / row["package"]
            for column in ("mapping_file", "registration_file", "adult_file", "gaps_file", "sources_file"):
                self.assertTrue((package / row[column]).is_file(), (row["rank"], column))


if __name__ == "__main__":
    unittest.main()
