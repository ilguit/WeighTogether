#!/usr/bin/env python3
"""Local consistency checks for the registry snapshot artifacts."""

import csv
import json
from collections import Counter
from pathlib import Path

HERE = Path(__file__).parent
ROOT = HERE.parents[2]
DETAIL_FIELDS = [
    "source_id", "cattery_id", "cattery_name", "breed_original", "breed_name_en",
    "scalesync_vbo_id", "mapping_decision", "association_count", "geography",
    "sample_type", "organization", "denominator", "url", "accessed_at", "limitations_bias",
]
COUNT_FIELDS = ["source_id", "breed_name_en", "scalesync_vbo_id", "cattery_breed_records", "rank"]


def read(path: Path, fields: list[str]) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as stream:
        reader = csv.DictReader(stream)
        assert reader.fieldnames == fields, (path, reader.fieldnames)
        return list(reader)


detail = read(HERE / "registries-cattery-breed.csv", DETAIL_FIELDS)
summary = read(HERE / "registries-breed-counts.csv", COUNT_FIELDS)
assert len(detail) >= 2_000
assert len({row["cattery_id"] for row in detail if row["source_id"].startswith("RU-FARUS")}) >= 900
assert {row["mapping_decision"] for row in detail} <= {"exact", "aggregate", "split", "unresolved"}
assert all(row["association_count"] == "1" for row in detail)
assert len({(row["source_id"], row["cattery_id"], row["breed_original"]) for row in detail}) == len(detail)

catalog = json.loads((ROOT / "core/src/main/resources/breed_catalog.json").read_text(encoding="utf-8"))
cat_ids = {breed["id"] for breed in catalog["breeds"] if breed["species"] == "cat"}
for row in detail:
    ids = [item for item in row["scalesync_vbo_id"].split("|") if item]
    assert all(item in cat_ids for item in ids), row
    assert bool(ids) == (row["mapping_decision"] != "unresolved"), row

expected = Counter(
    (row["source_id"], row["breed_name_en"], row["scalesync_vbo_id"])
    for row in detail
    if row["scalesync_vbo_id"] and "|" not in row["scalesync_vbo_id"]
)
actual = {
    (row["source_id"], row["breed_name_en"], row["scalesync_vbo_id"]): int(row["cattery_breed_records"])
    for row in summary
}
assert dict(expected) == actual
for source in {row["source_id"] for row in summary}:
    rows = [row for row in summary if row["source_id"] == source]
    assert len(rows) >= 20
    prior_count = None
    prior_rank = 0
    for position, row in enumerate(rows, 1):
        count = int(row["cattery_breed_records"])
        rank = int(row["rank"])
        if prior_count == count:
            assert rank == prior_rank
        else:
            assert rank == position
        prior_count, prior_rank = count, rank

print(f"OK: {len(detail)} cattery-breed records; {len(summary)} per-breed source rows")
