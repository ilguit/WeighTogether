#!/usr/bin/env python3
"""Validate ranks 16-20 source links and fallback semantics."""

import csv
from pathlib import Path

HERE = Path(__file__).resolve().parent


def rows(name):
    with (HERE / name).open(encoding="utf-8", newline="") as stream:
        return list(csv.DictReader(stream))


adult = rows("adult-standards.csv")
assert sorted({int(row["rank"]) for row in adult}) == [16, 17, 18, 19, 20]
sources = {row["source_id"] for row in rows("sources.csv")}
assert all(row["source_id"] in sources for row in adult)

pomeranian = [row for row in adult if row["vbo_id"] == "VBO:0200599"]
assert len(pomeranian) == 2
fallback = [row for row in pomeranian if row["source_id"] == "WIKIPEDIA-POMERANIAN"]
assert len(fallback) == 1
assert (
    fallback[0]["sex"],
    fallback[0]["weight_min_kg"],
    fallback[0]["weight_max_kg"],
    fallback[0]["weight_value_kind"],
) == ("all", "1.36", "3.17", "range")
assert not any(
    row["vbo_id"] == "VBO:0200599" and row["field_or_age_range"] == "adult weight"
    for row in rows("gaps.csv")
)

print("validated ranks 16-20 research package")
