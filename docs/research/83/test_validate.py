#!/usr/bin/env python3
import importlib.util
import json
import unittest
from pathlib import Path
from unittest.mock import patch

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

    def test_final_five_use_exact_shipped_catalog_mappings(self):
        self.assertEqual(
            {rank: self.by_rank[rank]["catalog_id"] for rank in range(26, 31)},
            VALIDATOR.EXPECTED_CATALOG_IDS,
        )
        self.assertTrue(all(not row["catalog_id"].startswith("local:") for row in self.manifest))

    def test_all_manifest_foreign_keys_resolve(self):
        for row in self.manifest:
            package = HERE / row["package"]
            for column in ("mapping_file", "registration_file", "adult_file", "gaps_file", "sources_file"):
                self.assertTrue((package / row[column]).is_file(), (row["rank"], column))

    def test_every_rank_21_to_30_has_exact_height_evidence_or_explicit_gap(self):
        for rank in range(21, 31):
            row = self.by_rank[rank]
            self.assertEqual(row["adult_height_file"], "adult_height.csv")
            package = HERE / row["package"]
            height_rows = VALIDATOR.read_csv(package / row["adult_height_file"])
            matching = [height for height in height_rows if int(height["rank"]) == rank]
            self.assertTrue(matching, rank)
            actual = [
                (height["sex"], height["min_height_cm"], height["max_height_cm"], height["source_id"], height["source_page"])
                for height in matching
            ]
            self.assertEqual(actual, VALIDATOR.EXPECTED_HEIGHT_ROWS[rank])
            self.assertTrue(all(height["unit"] == "cm" for height in matching))

    def test_fixed_wikipedia_revision_rejects_stale_oldid(self):
        with self.assertRaises(AssertionError):
            VALIDATOR.validate_fixed_wikipedia_revision(
                "https://en.wikipedia.org/w/index.php?title=Basenji&oldid=1351259636",
                "en",
                "Basenji",
                1351259637,
            )

    def test_fixed_wikipedia_revision_rejects_extra_query_parameter(self):
        with self.assertRaises(AssertionError):
            VALIDATOR.validate_fixed_wikipedia_revision(
                "https://ru.wikipedia.org/w/index.php?title=Среднеазиатская_овчарка&oldid=154838607&diff=prev",
                "ru",
                "Среднеазиатская_овчарка",
                154838607,
            )

    def test_fixed_wikipedia_revision_rejects_duplicate_conflicting_oldid(self):
        with self.assertRaises(AssertionError):
            VALIDATOR.validate_fixed_wikipedia_revision(
                "https://en.wikipedia.org/w/index.php?title=Basenji&oldid=1351259637&oldid=1",
                "en",
                "Basenji",
                1351259637,
            )

    def assert_full_validator_rejects_stale_runtime_source(self, source_id, stale_oldid):
        snapshot_path = HERE.parents[2] / "core/src/main/resources/breed_references.json"
        original_read_text = Path.read_text
        snapshot = json.loads(original_read_text(snapshot_path, encoding="utf-8"))
        source = next(row for row in snapshot["manifest"]["sources"] if row["id"] == source_id)
        current_oldid = source["url"].split("oldid=", 1)[1]
        source["url"] = source["url"].replace(
            f"oldid={current_oldid}",
            f"oldid={stale_oldid}",
        )
        mutated_snapshot = json.dumps(snapshot, ensure_ascii=False)

        def read_text(path, *args, **kwargs):
            if path == snapshot_path:
                return mutated_snapshot
            return original_read_text(path, *args, **kwargs)

        with patch.object(Path, "read_text", new=read_text):
            with self.assertRaises(AssertionError):
                VALIDATOR.validate()

    def test_full_validator_rejects_stale_basenji_oldid(self):
        self.assert_full_validator_rejects_stale_runtime_source(
            "wiki-basenji",
            1351259636,
        )

    def test_full_validator_rejects_stale_central_asian_oldid(self):
        self.assert_full_validator_rejects_stale_runtime_source(
            "wiki-central-asian-range",
            154838606,
        )


if __name__ == "__main__":
    unittest.main()
