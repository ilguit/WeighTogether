#!/usr/bin/env python3
import csv
from pathlib import Path

ROOT = Path(__file__).parent


def rows(name):
    with (ROOT / name).open(newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


breeds = rows("breeds.csv")
registrations = rows("registrations_by_day.csv")
gaps = rows("growth_gaps.csv")
sources = rows("sources.csv")

assert [int(row["rank"]) for row in breeds] == [21, 22, 23, 24, 25]
assert len({row["catalog_id"] for row in breeds}) == 5
assert [int(row["registrations_total"]) for row in breeds] == [100, 99, 95, 94, 92]
assert all(float(row["adult_min_kg"]) <= float(row["adult_max_kg"]) for row in breeds)

source_ids = {row["source_id"] for row in sources}
assert all(row["adult_source_id"] in source_ids for row in breeds)
assert all(row["source_id"] in source_ids for row in registrations)

for breed in breeds:
    matching = [row for row in registrations if row["catalog_id"] == breed["catalog_id"]]
    assert len(matching) == 2
    assert sum(int(row["total"]) for row in matching) == int(breed["registrations_total"])
    assert all(int(row["males"]) + int(row["females"]) == int(row["total"]) for row in matching)

assert {row["catalog_id"] for row in gaps} == {row["catalog_id"] for row in breeds}
assert all((row["age_start_month"], row["age_end_month"], row["status"]) == ("0", "24", "gap") for row in gaps)
print("package 21-25 validation: OK")
