#!/usr/bin/env python3
import csv
from pathlib import Path

ROOT = Path(__file__).resolve().parent
RANKS = {26, 27, 28, 29, 30}

def rows(name):
    with (ROOT / name).open(newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))

breeds = rows("breeds.csv")
assert {int(r["rank"]) for r in breeds} == RANKS
assert len({r["breed_id"] for r in breeds}) == 5
breed_ids = {r["breed_id"] for r in breeds}
expected_ids = {
    26: "VBO:0201174",
    27: "VBO:0200899",
    28: "VBO:0200734",
    29: "VBO:0200897",
    30: "VBO:0200880",
}
assert {int(r["rank"]): r["breed_id"] for r in breeds} == expected_ids

registrations = rows("registrations.csv")
assert {int(r["rank"]) for r in registrations} == RANKS
for row in registrations:
    assert int(row["day1_total"]) + int(row["day2_total"]) == int(row["registrations_total"])
assert [int(r["registrations_total"]) for r in sorted(registrations, key=lambda x: int(x["rank"]))] == [92, 91, 89, 88, 85]

adult = rows("adult_weight.csv")
assert {r["breed_id"] for r in adult} == breed_ids
for row in adult:
    if row["min_kg"] or row["max_kg"]:
        assert row["min_kg"] and row["max_kg"]
        assert float(row["min_kg"]) <= float(row["max_kg"])

height = rows("adult_height.csv")
assert {int(row["rank"]) for row in height} == RANKS
assert {row["breed_id"] for row in height} == breed_ids
for row in height:
    assert row["breed_id"] == expected_ids[int(row["rank"])]
    assert row["source_id"] in {"fci_212", "fci_183", "fci_255", "fci_367"}
    assert row["unit"] == "cm" and row["statistic_semantics"] and row["applicability"] and row["limitation"]
    assert row["min_height_cm"] and row["max_height_cm"]
    assert float(row["min_height_cm"]) <= float(row["max_height_cm"])

age = rows("age_weight.csv")
assert not age, "No exact source-backed age rows are expected in this package"
gaps = rows("gaps.csv")
assert {r["breed_id"] for r in gaps if r["field"] == "age_weight_0_24_months"} == breed_ids

sources = rows("sources.csv")
assert all(None not in row for row in sources), "Malformed CSV row"
source_ids = {r["source_id"] for r in sources}
assert len(source_ids) == len(sources)
assert all(row["mapping_source_id"] in source_ids for row in breeds)
for row in registrations:
    assert row["day1_source_id"] in source_ids and row["day2_source_id"] in source_ids
for row in adult:
    assert row["source_id"] in source_ids
for row in gaps:
    assert row["breed_id"] == expected_ids[int(row["rank"])]
    assert all(source_id in source_ids for source_id in row["relevant_source_ids"].split(";"))

print("validated ranks 26-30: mapping, registrations, adult weight/height, explicit age gaps, provenance")
