#!/usr/bin/env python3
"""Validate issue #87 CSV schemas, VBO mappings, and published rank order."""

import csv
import hashlib
import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
RESEARCH = Path(__file__).resolve().parent
CATALOG = ROOT / "core/src/main/resources/breed_catalog.json"
POPULATION = RESEARCH / "population-ranking.csv"
CONTEXTS = RESEARCH / "quantitative-contexts.csv"
EXCLUDED = RESEARCH / "excluded-series.csv"
FINAL_ANALYSIS = RESEARCH / "final-analysis.md"
CHECKSUMS = RESEARCH / "CHECKSUMS.sha256"

REQUIRED_POPULATION_FIELDS = {
    "source_id", "breed_original", "variety_original", "breed_name_ru",
    "breed_name_en", "scalesync_vbo_id", "mapping_decision", "count", "rank",
    "year/period", "geography", "sample_type", "organization",
    "denominator/sample_size", "URL", "accessed_at", "limitations/bias",
}
ALLOWED_MAPPING_DECISIONS = {"exact", "aggregate", "split", "unresolved"}


def read_csv(path: Path):
    with path.open(encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream)
        return reader.fieldnames or [], list(reader)


def main():
    expected_files = {POPULATION.name, CONTEXTS.name, EXCLUDED.name}
    checksum_rows = {}
    for line in CHECKSUMS.read_text(encoding="utf-8").splitlines():
        digest, filename = line.split("  ", maxsplit=1)
        assert re.fullmatch(r"[0-9a-f]{64}", digest), "invalid SHA-256 digest"
        assert filename not in checksum_rows, f"duplicate checksum for {filename}"
        checksum_rows[filename] = digest
    assert set(checksum_rows) == expected_files, "checksum file set changed"
    for filename, expected_digest in checksum_rows.items():
        actual_digest = hashlib.sha256((RESEARCH / filename).read_bytes()).hexdigest()
        assert actual_digest == expected_digest, f"checksum mismatch for {filename}"

    fields, rows = read_csv(POPULATION)
    assert set(fields) == REQUIRED_POPULATION_FIELDS, "population schema changed"
    assert rows, "population ranking must not be empty"

    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    cats = {row["id"]: row for row in catalog["breeds"] if row["species"] == "cat"}

    observed_ranks = []
    for row in rows:
        assert row["sample_type"] == "population"
        assert row["mapping_decision"] in ALLOWED_MAPPING_DECISIONS
        assert row["count"] == "", "source publishes order, not breed counts"
        assert row["scalesync_vbo_id"] in cats
        assert cats[row["scalesync_vbo_id"]]["canonicalName"] == row["breed_name_en"]
        observed_ranks.append(int(row["rank"]))

    # The source publishes a strict top-5 order and no ties. If a future source
    # publishes ties, preserve its equal ranks and serialize ties by VBO id only
    # for deterministic output; never break a tie into invented popularity ranks.
    assert observed_ranks == sorted(observed_ranks), "rows are not rank-sorted"
    assert observed_ranks == list(range(1, len(rows) + 1)), "top-5 ranks must be contiguous"
    assert len(rows) == 5, "no evidence-backed population ranks beyond top 5"

    _, contexts = read_csv(CONTEXTS)
    assert contexts
    assert all(row["usable_for_breed_ranking"] == "no" for row in contexts)
    assert {row["sample_type"] for row in contexts} >= {"population", "exhibition entries", "cattery"}

    _, excluded = read_csv(EXCLUDED)
    assert excluded
    assert {row["sample_type"] for row in excluded} >= {
        "registration", "litter", "cattery", "user registry", "exhibition entries", "points",
    }

    analysis = FINAL_ANALYSIS.read_text(encoding="utf-8")
    normalized_analysis = re.sub(r"\s+", " ", analysis)
    for required_statement in (
        "не определены",
        "псевдорепликацией",
        "1–10",
        "11–30",
        "31–50",
        "Все породы",
        "не перенумеровывает",
        "Центр изучения питания и благополучия животных / Ipsos",
        "Felis Russica",
        "Animal-ID",
    ):
        assert required_statement in normalized_analysis, (
            f"final analysis lost required conclusion: {required_statement}"
        )

    print(
        f"OK: {len(rows)} population ranks, {len(contexts)} quantitative contexts, "
        f"{len(excluded)} excluded series; {len(cats)} catalog cat concepts checked"
    )


if __name__ == "__main__":
    main()
