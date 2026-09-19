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

age = rows("age_weight.csv")
assert not age, "No exact source-backed age rows are expected in this package"
gaps = rows("gaps.csv")
assert {r["breed_id"] for r in gaps if r["field"] == "age_weight_0_24_months"} == breed_ids

sources = rows("sources.csv")
assert all(None not in row for row in sources), "Malformed CSV row"
source_ids = {r["source_id"] for r in sources}
assert len(source_ids) == len(sources)
for row in registrations:
    assert row["day1_source_id"] in source_ids and row["day2_source_id"] in source_ids
for row in adult:
    assert row["source_id"] in source_ids

print("validated ranks 26-30: mapping, registrations, adult values, explicit age gaps, provenance")
