#!/usr/bin/env python3
import csv
import json
import pathlib

ROOT = pathlib.Path(__file__).parent
CSV_PATH = ROOT / "exhibitions-tyumen-2024.csv"
CATALOG_PATH = ROOT.parents[2] / "core/src/main/resources/breed_catalog.json"
EXPECTED_FIELDS = [
    "source_id", "event_id", "event_name", "event_date", "city", "country",
    "system", "license", "source_breed_code", "breed_name_en", "breed_name_ru",
    "scalesync_vbo_id", "mapping_decision", "catalog_entry_count", "rank",
    "total_catalog_entries", "recognized_pedigree_entries", "URL", "accessed_at",
    "metric", "limitations",
]


def verify(path: pathlib.Path = CSV_PATH) -> None:
    with path.open(newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        assert reader.fieldnames == EXPECTED_FIELDS
        rows = list(reader)
    catalog = json.loads(CATALOG_PATH.read_text(encoding="utf-8"))
    cat_ids = {breed["id"] for breed in catalog["breeds"] if breed["species"] == "cat"}
    assert len(rows) == 26
    assert len({row["source_breed_code"] for row in rows}) == len(rows)
    assert {row["metric"] for row in rows} == {"catalog entries"}
    assert {row["mapping_decision"] for row in rows} == {"exact", "unresolved"}
    assert {row["total_catalog_entries"] for row in rows} == {"102"}
    assert {row["recognized_pedigree_entries"] for row in rows} == {"100"}
    assert sum(int(row["catalog_entry_count"]) for row in rows) == 102
    assert sum(int(row["catalog_entry_count"]) for row in rows if row["source_breed_code"] not in {"HHS", "XLH"}) == 100
    assert rows == sorted(rows, key=lambda row: (int(row["rank"]), row["source_breed_code"]))
    counts = [int(row["catalog_entry_count"]) for row in rows]
    for row in rows:
        expected_rank = 1 + sum(count > int(row["catalog_entry_count"]) for count in counts)
        assert int(row["rank"]) == expected_rank
        if row["mapping_decision"] == "exact":
            assert row["scalesync_vbo_id"] in cat_ids
        else:
            assert not row["scalesync_vbo_id"]
    assert rows[0]["source_breed_code"] == "MCO"
    assert rows[0]["catalog_entry_count"] == "16"


if __name__ == "__main__":
    verify()
    print("exhibition dataset verified")
