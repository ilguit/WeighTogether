#!/usr/bin/env python3
import csv
from pathlib import Path

HERE = Path(__file__).resolve().parent

def rows(name):
    with (HERE / name).open(encoding="utf-8", newline="") as stream:
        return list(csv.DictReader(stream))

breeds = rows("breeds.csv")
assert [int(r["rank"]) for r in breeds] == [11, 12, 13, 14, 15]
assert [int(r["registrations"]) for r in breeds] == [142, 133, 133, 132, 126]
ids = {r["vbo_id"] for r in breeds}
assert len(ids) == 5 and all(v.startswith("VBO:02") for v in ids)

sources = {r["source_id"] for r in rows("sources.csv")}
assert {r["popularity_source_id"] for r in breeds} <= sources
for row in rows("adult-reference.csv"):
    assert row["vbo_id"] in ids
    assert all(part in sources for part in row["source_id"].split("+"))
    if row["min_weight_kg"] and row["max_weight_kg"]:
        assert float(row["min_weight_kg"]) <= float(row["max_weight_kg"])
for row in rows("age-observations.csv"):
    assert row["vbo_id"] in ids and row["source_id"] in sources
    assert 0 <= float(row["age_months"]) <= 24
assert {r["vbo_id"] for r in rows("gaps.csv")} <= ids
assert all(r["do_not_infer"] == "true" for r in rows("gaps.csv"))
print("validated ranks 11-15 research package")
