#!/usr/bin/env python3
"""Verify issue #87 consumer/veterinary proxy artifacts."""

import csv
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
RANKING = HERE / "consumer-proxy-ranking.csv"
INVENTORY = HERE / "consumer-source-inventory.csv"
CATALOG = ROOT / "core/src/main/resources/breed_catalog.json"

RANKING_FIELDS = [
    "source_id", "source_rank", "breed_original", "breed_name_en",
    "scalesync_vbo_id", "mapping_decision", "value", "unit", "year/period",
    "geography", "sample_type", "organization", "denominator/sample_size",
    "URL", "accessed_at", "limitations/bias", "outside_population_top5",
]
INVENTORY_FIELDS = [
    "source_id", "category", "organization", "year/period", "geography",
    "URL", "accessed_at", "status", "published_breed_labels",
    "outside_population_top5", "denominator_status", "reason",
]


def rows(path, expected_fields):
    with path.open(encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream)
        assert reader.fieldnames == expected_fields, f"{path.name}: schema changed"
        result = list(reader)
    assert result, f"{path.name}: empty"
    return result


def main():
    ranking = rows(RANKING, RANKING_FIELDS)
    inventory = rows(INVENTORY, INVENTORY_FIELDS)
    cats = {
        item["id"]: item for item in json.loads(CATALOG.read_text(encoding="utf-8"))["breeds"]
        if item["species"] == "cat"
    }

    assert len(ranking) == 20
    assert {row["sample_type"] for row in ranking} == {
        "classified-search demand", "veterinary registry", "owner survey",
    }
    assert {row["source_id"] for row in ranking} <= {row["source_id"] for row in inventory}
    for row in ranking:
        assert row["mapping_decision"] in {"exact", "unresolved"}
        assert row["outside_population_top5"] in {"yes", "no"}
        assert row["URL"].startswith("https://")
        assert row["limitations/bias"] and row["denominator/sample_size"]
        vbo_id = row["scalesync_vbo_id"]
        if row["mapping_decision"] == "exact":
            assert vbo_id in cats
            assert cats[vbo_id]["canonicalName"] == row["breed_name_en"]
        else:
            assert not vbo_id

    by_source = {}
    for row in ranking:
        by_source.setdefault(row["source_id"], []).append(int(row["source_rank"]))
    assert by_source == {
        "RU-AVITO-DEMAND-2018": list(range(1, 11)),
        "RU-VETAS-MOSCOW-2023": list(range(1, 6)),
        "RU-INGOS-FU-2023": list(range(1, 6)),
    }
    assert max(int(row["outside_population_top5"]) for row in inventory) < 10
    assert not any(row["denominator_status"] == "published breed denominator" for row in inventory)
    print("OK: 20 ordered proxy rows; no source meets >=10 outside-top-five with a published breed denominator")


if __name__ == "__main__":
    main()
