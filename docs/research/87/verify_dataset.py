#!/usr/bin/env python3
"""Validate issue #87 CSV schemas, VBO mappings, and published rank order."""

import csv
import hashlib
import importlib.util
import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
RESEARCH = Path(__file__).resolve().parent
CATALOG = ROOT / "core/src/main/resources/breed_catalog.json"
POPULATION = RESEARCH / "population-ranking.csv"
YANDEX = RESEARCH / "yandex-id-ranking.csv"
CONTEXTS = RESEARCH / "quantitative-contexts.csv"
EXCLUDED = RESEARCH / "excluded-series.csv"
FINAL_ANALYSIS = RESEARCH / "final-analysis.md"
CHECKSUMS = RESEARCH / "CHECKSUMS.sha256"
EVIDENCE_PRIORITY = RESEARCH / "evidence-priority.csv"
EXHIBITIONS = RESEARCH / "exhibitions-tyumen-2024.csv"
REGISTRY_DETAIL = RESEARCH / "registries-cattery-breed.csv"
REGISTRY_COUNTS = RESEARCH / "registries-breed-counts.csv"
CONSUMER_RANKING = RESEARCH / "consumer-proxy-ranking.csv"
CONSUMER_INVENTORY = RESEARCH / "consumer-source-inventory.csv"

REQUIRED_POPULATION_FIELDS = (
    "source_id", "breed_original", "variety_original", "breed_name_ru",
    "breed_name_en", "scalesync_vbo_id", "mapping_decision", "count", "rank",
    "year/period", "geography", "sample_type", "organization",
    "denominator/sample_size", "URL", "accessed_at", "limitations/bias",
)
ALLOWED_MAPPING_DECISIONS = {"exact", "aggregate", "split", "unresolved"}
REQUIRED_CONTEXT_FIELDS = (
    "source_id", "metric_type", "value", "unit", "year/period", "geography",
    "sample_type", "organization", "denominator/sample_size", "URL",
    "accessed_at", "usable_for_breed_ranking", "limitations/bias",
)
REQUIRED_EXCLUDED_FIELDS = (
    "source_id", "sample_type", "organization", "year/period", "geography",
    "URL", "accessed_at", "numeric_data_available", "exclusion_reason",
)
ALLOWED_SAMPLE_TYPES = {
    POPULATION.name: {"population"},
    YANDEX.name: {"user-entered pet profiles"},
    CONTEXTS.name: {
        "population",
        "user-entered pet profiles",
        "exhibition entries",
        "cattery",
    },
    EXCLUDED.name: {
        "registration",
        "litter",
        "cattery",
        "user registry",
        "exhibition entries",
        "points",
    },
}
REQUIRED_EVIDENCE_FIELDS = (
    "priority", "evidence_tier", "breed_name_en", "scalesync_vbo_id",
    "source_series_count", "farus_rank", "felis_rank", "wcf_event_rank",
    "avito_rank", "vetas_rank", "ingos_rank", "median_normalized_rank",
    "interpretation",
)


def read_csv(path: Path):
    with path.open(encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream)
        return reader.fieldnames or [], list(reader)


def read_validated_csv(path: Path, required_fields):
    fields, rows = read_csv(path)
    assert fields == list(required_fields), f"{path.name} schema or column order changed"
    allowed_sample_types = ALLOWED_SAMPLE_TYPES[path.name]
    unknown_sample_types = {row["sample_type"] for row in rows} - allowed_sample_types
    assert not unknown_sample_types, (
        f"{path.name} contains unknown sample_type values: {sorted(unknown_sample_types)}"
    )
    return rows


def read_evidence_priority(path: Path = EVIDENCE_PRIORITY):
    fields, rows = read_csv(path)
    assert fields == list(REQUIRED_EVIDENCE_FIELDS), (
        f"{path.name} schema or column order changed"
    )
    assert {row["evidence_tier"] for row in rows} <= {"A", "B", "C"}, (
        f"{path.name} contains unknown evidence tier"
    )
    return rows


def main():
    expected_files = {
        POPULATION.name, YANDEX.name, CONTEXTS.name, EXCLUDED.name,
        EVIDENCE_PRIORITY.name, EXHIBITIONS.name, REGISTRY_DETAIL.name,
        REGISTRY_COUNTS.name, CONSUMER_RANKING.name, CONSUMER_INVENTORY.name,
    }
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

    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    cats = {row["id"]: row for row in catalog["breeds"] if row["species"] == "cat"}

    ranking_tables = []
    for path, sample_type in (
        (POPULATION, "population"),
        (YANDEX, "user-entered pet profiles"),
    ):
        rows = read_validated_csv(path, REQUIRED_POPULATION_FIELDS)
        assert rows, f"{path.name} must not be empty"
        observed_ranks = []
        for row in rows:
            assert all(row[field].strip() for field in set(REQUIRED_POPULATION_FIELDS) - {
                "variety_original", "scalesync_vbo_id", "count",
            }), f"missing provenance in {path.name}"
            assert row["sample_type"] == sample_type
            assert row["mapping_decision"] in ALLOWED_MAPPING_DECISIONS
            assert row["count"] == "", "source publishes order, not breed counts"
            vbo_id = row["scalesync_vbo_id"]
            if row["mapping_decision"] == "exact":
                assert vbo_id in cats
                assert cats[vbo_id]["canonicalName"] == row["breed_name_en"]
            else:
                assert not vbo_id, "non-exact aggregate must not claim one child VBO ID"
            observed_ranks.append(int(row["rank"]))
        assert observed_ranks == list(range(1, 6)), f"{path.name} must contain ordered top-5"
        ranking_tables.append(rows)

    # The source publishes a strict top-5 order and no ties. If a future source
    # publishes ties, preserve its equal ranks and serialize ties by VBO id only
    # for deterministic output; never break a tie into invented popularity ranks.
    population_rows, yandex_rows = ranking_tables
    population_names = {row["breed_original"] for row in population_rows}
    yandex_names = {row["breed_original"] for row in yandex_rows}
    assert population_names & yandex_names == {
        "британская короткошёрстная", "шотландская вислоухая", "мейн-кун", "сибирская",
    }, "published top-5 overlap changed"
    assert population_names - yandex_names == {"сиамская"}
    assert yandex_names - population_names == {"сфинкс"}
    assert all(row["denominator/sample_size"].startswith("490000 pet profiles") for row in yandex_rows)
    assert all("breed counts unpublished" in row["denominator/sample_size"] for row in yandex_rows)

    contexts = read_validated_csv(CONTEXTS, REQUIRED_CONTEXT_FIELDS)
    assert contexts
    assert all(all(row[field].strip() for field in REQUIRED_CONTEXT_FIELDS) for row in contexts)
    context_keys = [
        (row["source_id"], row["metric_type"], row["year/period"], row["geography"])
        for row in contexts
    ]
    assert len(context_keys) == len(set(context_keys)), "duplicate quantitative context key"
    assert all(row["usable_for_breed_ranking"] == "no" for row in contexts)
    assert {row["sample_type"] for row in contexts} >= {"population", "exhibition entries", "cattery"}
    yandex_contexts = [row for row in contexts if row["source_id"] == "RU-YANDEX-ID-2024"]
    assert {(row["metric_type"], row["value"], row["unit"]) for row in yandex_contexts} == {
        ("pet profiles", "490000", "pet profiles"),
        ("cat share", "58", "percent of pet profiles"),
    }
    assert not any(row["value"] in {"284200", "284 200"} for row in contexts), (
        "rounded total and share must not be multiplied into a false absolute"
    )

    excluded = read_validated_csv(EXCLUDED, REQUIRED_EXCLUDED_FIELDS)
    assert excluded
    assert all(all(row[field].strip() for field in REQUIRED_EXCLUDED_FIELDS) for row in excluded)
    excluded_source_ids = [row["source_id"] for row in excluded]
    assert len(excluded_source_ids) == len(set(excluded_source_ids)), "duplicate excluded source_id"
    assert all(row["numeric_data_available"] in {"yes", "no", "partial"} for row in excluded)
    assert {row["sample_type"] for row in excluded} >= {
        "registration", "litter", "cattery", "user registry", "exhibition entries", "points",
    }

    evidence_rows = read_evidence_priority()
    assert len(evidence_rows) == 49, "outside-top-five exact VBO coverage changed"
    assert [int(row["priority"]) for row in evidence_rows] == list(range(1, 50))
    assert len({row["scalesync_vbo_id"] for row in evidence_rows}) == 49
    assert all(row["scalesync_vbo_id"] in cats for row in evidence_rows)
    assert all(cats[row["scalesync_vbo_id"]]["canonicalName"] == row["breed_name_en"] for row in evidence_rows)
    tier_counts = {tier: sum(row["evidence_tier"] == tier for row in evidence_rows) for tier in "ABC"}
    assert tier_counts == {"A": 9, "B": 14, "C": 26}
    assert all(row["interpretation"] == "application coverage priority; not population rank" for row in evidence_rows)

    build_path = RESEARCH / "build_evidence_priority.py"
    spec = importlib.util.spec_from_file_location("build_evidence_priority", build_path)
    builder = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(builder)
    assert evidence_rows == [
        {field: str(row[field]) for field in REQUIRED_EVIDENCE_FIELDS}
        for row in builder.build_rows()
    ], "evidence priority no longer matches source-specific ranks"

    analysis = FINAL_ANALYSIS.read_text(encoding="utf-8")
    normalized_analysis = re.sub(r"\s+", " ", analysis)
    for required_statement in (
        "не определены",
        "псевдорепликацией",
        "4 из 5",
        "80%",
        "сиамская",
        "сфинкс",
        "1–10",
        "11–30",
        "31–50",
        "Все породы",
        "не перенумеровывает",
        "Центр изучения питания и благополучия животных / Ipsos",
        "Felis Russica",
        "Animal-ID",
        "49",
        "FARUS",
        "WCF",
        "Tier A",
        "не является популяционным рейтингом",
    ):
        assert required_statement.casefold() in normalized_analysis.casefold(), (
            f"final analysis lost required conclusion: {required_statement}"
        )

    print(
        f"OK: {len(population_rows)} population and {len(yandex_rows)} Yandex ranks, "
        f"4/5 overlap, {len(contexts)} quantitative contexts, "
        f"{len(excluded)} excluded series, 49 outside-top-five concepts "
        f"(tiers A/B/C: 9/14/26); {len(cats)} catalog cat concepts checked"
    )


if __name__ == "__main__":
    main()
