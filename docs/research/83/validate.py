#!/usr/bin/env python3
"""Validate the unified issue-83 manifest and heterogeneous evidence packages."""

import csv
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
EXPECTED_COUNTS = [142, 133, 133, 132, 126, 120, 117, 111, 103, 102,
                   100, 99, 95, 94, 92, 92, 91, 89, 88, 85]
PRIORITIES = {
    "primary_official", "primary_official_non_numeric", "secondary_fallback",
    "secondary_fallback_ambiguous",
}
EXPECTED_CATALOG_IDS = {
    26: "VBO:0201174",
    27: "VBO:0200899",
    28: "VBO:0200734",
    29: "VBO:0200897",
    30: "VBO:0200880",
}
EXPECTED_HEIGHT_ROWS = {
    21: [("all", "25", "30", "FCI-345", "4")],
    22: [("all", "30", "35", "FCI-183", "6")],
    23: [("all", "", "", "FCI-148", "8")],
    24: [("male", "53", "53", "FCI-297", "4"), ("female", "", "", "FCI-297", "4")],
    25: [("all", "", "", "FCI-253", "all")],
    26: [("male", "54", "60", "fci_212", "5"), ("female", "50", "56", "fci_212", "5")],
    27: [("all", "30", "35", "fci_183", "6")],
    28: [("male", "64", "70", "fci_255", "5"), ("female", "58", "64", "fci_255", "5")],
    29: [("all", "30", "35", "fci_183", "6")],
    30: [("male", "35.5", "46", "fci_367", "8"), ("female", "33", "43.5", "fci_367", "8")],
}
EXPECTED_RUNTIME_31_50 = [
    (31, 84, "VBO:0201415"),
    (32, 84, "VBO:0201448"),
    (33, 83, "VBO:0200161"),
    (34, 83, "VBO:0200339"),
    (35, 82, "VBO:0200962"),
    (36, 80, "VBO:0200485"),
    (37, 79, "VBO:0200163"),
    (38, 79, "VBO:0201198"),
    (39, 78, "VBO:0200713"),
    (40, 77, "VBO:0201403"),
    (41, 77, "VBO:0200340"),
    (42, 76, "VBO:0200345"),
    (43, 76, "VBO:0201348"),
    (44, 76, "VBO:0200410"),
    (45, 74, "VBO:0200027"),
    (46, 74, "VBO:0201217"),
    (47, 73, "VBO:0200882"),
    (48, 73, "VBO:0200764"),
    (49, 73, "VBO:0200375"),
    (50, 70, "VBO:0201143"),
]
EXPECTED_RUSSIAN_NAMES_31_50 = [
    "Вест-хайленд-уайт-терьер", "Йоркширский терьер", "Бернский зенненхунд",
    "Чихуахуа длинношёрстный", "Норвич-терьер", "Английский бульдог", "Бишон-фризе",
    "Шотландский терьер", "Малая итальянская борзая", "Веймаранер короткошёрстный",
    "Чихуахуа короткошёрстный", "Китайская хохлатая собака", "Тайский риджбек",
    "Такса миниатюрная гладкошёрстная", "Американская акита", "Шетландская овчарка",
    "Миниатюрный бультерьер", "Керри-блю-терьер", "Колли длинношёрстный", "Ротвейлер",
]


def read_csv(path):
    with path.open(newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        assert reader.fieldnames, f"missing header: {path}"
        rows = list(reader)
    assert all(None not in row for row in rows), f"malformed row: {path}"
    return rows


def number(value, label):
    try:
        return float(value)
    except (TypeError, ValueError) as error:
        raise AssertionError(f"invalid number for {label}: {value!r}") from error


def validate_manifest():
    rows = read_csv(ROOT / "manifest.csv")
    assert [int(row["rank"]) for row in rows] == list(range(11, 31))
    assert [int(row["registration_count"]) for row in rows] == EXPECTED_COUNTS
    assert len(rows) == len({row["catalog_id"] for row in rows}) == 20
    assert all(row["adult_source_priority"] in PRIORITIES for row in rows)
    assert all(row["age_coverage"] in {"gap", "partial"} for row in rows)
    for row in rows:
        package = ROOT / row["package"]
        assert package.is_dir(), f"missing package: {package}"
        for key in ("mapping_file", "registration_file", "adult_file",
                    "gaps_file", "sources_file"):
            assert (package / row[key]).is_file(), f"missing {key} for rank {row['rank']}"
        if row["age_file"]:
            assert (package / row["age_file"]).is_file()
        if int(row["rank"]) >= 21:
            assert row["adult_height_file"] == "adult_height.csv"
            assert (package / row["adult_height_file"]).is_file()
    return rows


def validate_11_15():
    root = ROOT / "packages/11-15"
    breeds = read_csv(root / "breeds.csv")
    ids = {row["vbo_id"] for row in breeds}
    sources = {row["source_id"] for row in read_csv(root / "sources.csv")}
    assert [int(row["rank"]) for row in breeds] == list(range(11, 16))
    assert [int(row["registrations"]) for row in breeds] == EXPECTED_COUNTS[:5]
    assert len(ids) == 5
    assert {row["popularity_source_id"] for row in breeds} <= sources
    adult = read_csv(root / "adult-reference.csv")
    for row in adult:
        assert row["vbo_id"] in ids
        assert all(source in sources for source in row["source_id"].split("+"))
        if row["min_weight_kg"] and row["max_weight_kg"]:
            assert number(row["min_weight_kg"], "adult min") <= number(row["max_weight_kg"], "adult max")
        assert row["sex"] in {"male", "female", "all"}
        assert row["value_status"] in {"standard", "partial"}
        assert row["limitations"]
    fallbacks = {
        row["vbo_id"]: (row["sex"], row["min_weight_kg"], row["max_weight_kg"])
        for row in adult if row["source_id"].startswith("WIKIPEDIA-")
    }
    assert fallbacks == {
        "VBO:0200610": ("all", "25", "34"),
        "VBO:0200095": ("all", "16", "32"),
    }
    assert all(
        not row["min_weight_kg"]
        for row in adult
        if row["source_id"].startswith("FCI-") and row["vbo_id"] in fallbacks
    )
    age = read_csv(root / "age-observations.csv")
    for row in age:
        assert row["vbo_id"] in ids and row["source_id"] in sources
        assert 0 <= number(row["age_days"], "age days") <= 731
        assert row["method"] and row["limitations"]
    gaps = read_csv(root / "gaps.csv")
    assert {row["vbo_id"] for row in gaps} <= ids
    assert all(row["do_not_infer"] == "true" for row in gaps)


def validate_16_20():
    root = ROOT / "packages/16-20"
    adult = read_csv(root / "adult-standards.csv")
    ids = {row["vbo_id"] for row in adult}
    sources = {row["source_id"] for row in read_csv(root / "sources.csv")}
    assert sorted({int(row["rank"]) for row in adult}) == list(range(16, 21))
    assert len(ids) == 5
    for row in adult:
        assert row["source_id"] in sources and row["notes"]
        if row["weight_min_kg"] and row["weight_max_kg"]:
            assert number(row["weight_min_kg"], "adult min") <= number(row["weight_max_kg"], "adult max")
        assert row["weight_value_kind"] in {"ideal", "not_numeric", "standard_point", "minimum", "range"}
    pomeranian = [row for row in adult if row["vbo_id"] == "VBO:0200599"]
    assert len(pomeranian) == 2
    pomeranian_fallback = [row for row in pomeranian if row["source_id"] == "WIKIPEDIA-POMERANIAN"]
    assert len(pomeranian_fallback) == 1
    assert (
        pomeranian_fallback[0]["sex"],
        pomeranian_fallback[0]["weight_min_kg"],
        pomeranian_fallback[0]["weight_max_kg"],
        pomeranian_fallback[0]["weight_value_kind"],
    ) == ("all", "1.36", "3.17", "range")
    for row in read_csv(root / "age-observations.csv"):
        assert row["vbo_id"] in ids and row["source_id"] in sources
        assert 0 <= int(row["age_start_days"]) <= int(row["age_end_days"]) <= 731
        assert row["unit"] in {"g", "kg", "percent"}
        assert row["sample_n"] and row["method"] and row["limitations"]
    gaps = read_csv(root / "gaps.csv")
    assert {row["vbo_id"] for row in gaps} == ids
    assert all(row["gap"] and row["search_scope"] and row["decision"] for row in gaps)
    assert not any(
        row["vbo_id"] == "VBO:0200599" and row["field_or_age_range"] == "adult weight"
        for row in gaps
    )


def validate_21_25():
    root = ROOT / "packages/21-25"
    breeds = read_csv(root / "breeds.csv")
    ids = {row["catalog_id"] for row in breeds}
    sources = {row["source_id"] for row in read_csv(root / "sources.csv")}
    assert [int(row["rank"]) for row in breeds] == list(range(21, 26))
    assert [int(row["registrations_total"]) for row in breeds] == EXPECTED_COUNTS[10:15]
    assert len(ids) == 5
    for row in breeds:
        assert row["adult_source_id"] in sources
        assert number(row["adult_min_kg"], "adult min") <= number(row["adult_max_kg"], "adult max")
        assert row["adult_value_method"] and row["adult_applicability"]
    registrations = read_csv(root / "registrations_by_day.csv")
    for row in registrations:
        assert row["catalog_id"] in ids and row["source_id"] in sources
        assert int(row["males"]) + int(row["females"]) == int(row["total"])
    for breed in breeds:
        matches = [row for row in registrations if row["catalog_id"] == breed["catalog_id"]]
        assert len(matches) == 2
        assert sum(int(row["total"]) for row in matches) == int(breed["registrations_total"])
    gaps = read_csv(root / "growth_gaps.csv")
    assert {row["catalog_id"] for row in gaps} == ids
    assert all((row["age_start_month"], row["age_end_month"], row["status"]) == ("0", "24", "gap") for row in gaps)
    validate_height_rows(read_csv(root / "adult_height.csv"), ids, sources, "catalog_id")


def validate_26_30():
    root = ROOT / "packages/26-30"
    breeds = read_csv(root / "breeds.csv")
    ids = {row["breed_id"] for row in breeds}
    sources = {row["source_id"] for row in read_csv(root / "sources.csv")}
    assert [int(row["rank"]) for row in breeds] == list(range(26, 31))
    assert len(ids) == 5
    assert {int(row["rank"]): row["breed_id"] for row in breeds} == EXPECTED_CATALOG_IDS
    assert all(row["mapping_source_id"] in sources for row in breeds)
    catalog_path = ROOT.parents[2] / "core/src/main/resources/breed_catalog.json"
    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    catalog_ids = {row["id"] for row in catalog["breeds"]}
    assert ids <= catalog_ids
    registrations = read_csv(root / "registrations.csv")
    assert [int(row["registrations_total"]) for row in registrations] == EXPECTED_COUNTS[15:]
    for row in registrations:
        assert row["breed_id"] in ids
        assert row["breed_id"] == EXPECTED_CATALOG_IDS[int(row["rank"])]
        assert int(row["day1_total"]) + int(row["day2_total"]) == int(row["registrations_total"])
        assert row["day1_source_id"] in sources and row["day2_source_id"] in sources
    adult = read_csv(root / "adult_weight.csv")
    assert {row["breed_id"] for row in adult} == ids
    for row in adult:
        assert row["source_id"] in sources and row["limitation"]
        assert row["breed_id"] == EXPECTED_CATALOG_IDS[int(row["rank"])]
        if row["min_kg"] or row["max_kg"]:
            assert row["min_kg"] and row["max_kg"]
            assert number(row["min_kg"], "adult min") <= number(row["max_kg"], "adult max")
    validate_height_rows(read_csv(root / "adult_height.csv"), ids, sources, "breed_id")
    assert not read_csv(root / "age_weight.csv")
    gaps = read_csv(root / "gaps.csv")
    assert {row["breed_id"] for row in gaps if row["field"] == "age_weight_0_24_months"} == ids
    assert all(row["breed_id"] == EXPECTED_CATALOG_IDS[int(row["rank"])] for row in gaps)
    assert all(row["reason"] and row["relevant_source_ids"] for row in gaps)
    assert all(source in sources for row in gaps for source in row["relevant_source_ids"].split(";"))


def validate_height_rows(rows, ids, sources, id_column):
    ranks = {int(row["rank"]) for row in rows}
    expected_ranks = set(range(21, 26)) if id_column == "catalog_id" else set(range(26, 31))
    assert ranks == expected_ranks
    assert {row[id_column] for row in rows} == ids
    assert all(row["source_id"] in sources for row in rows)
    limitation_column = "limitations" if id_column == "catalog_id" else "limitation"
    assert all(row["unit"] == "cm" and row["statistic_semantics"] and row["applicability"] and row[limitation_column] for row in rows)
    actual = {}
    for row in rows:
        rank = int(row["rank"])
        actual.setdefault(rank, []).append((row["sex"], row["min_height_cm"], row["max_height_cm"], row["source_id"], row["source_page"]))
        if row["min_height_cm"] or row["max_height_cm"]:
            assert row["min_height_cm"] and row["max_height_cm"]
            assert number(row["min_height_cm"], "height min") <= number(row["max_height_cm"], "height max")
        else:
            assert row["value_status"] in {"official_non_numeric", "official_non_numeric_relative", "official_not_published"}
    assert actual == {rank: EXPECTED_HEIGHT_ROWS[rank] for rank in expected_ranks}


def validate_distinctions(manifest):
    by_rank = {int(row["rank"]): row for row in manifest}
    schnauzers = [by_rank[rank] for rank in (22, 27, 29)]
    assert len({row["catalog_id"] for row in schnauzers}) == 3
    assert len({row["variety_scope"] for row in schnauzers}) == 3
    assert "smooth-haired" in by_rank[23]["variety_scope"]
    assert "Japanese" in by_rank[28]["variety_scope"]
    assert by_rank[28]["adult_source_priority"] == "secondary_fallback_ambiguous"
    assert by_rank[30]["adult_source_priority"] == "primary_official_non_numeric"
    assert by_rank[17]["adult_source_priority"] == "secondary_fallback"
    assert {rank: by_rank[rank]["catalog_id"] for rank in range(26, 31)} == EXPECTED_CATALOG_IDS


def validate_runtime_snapshot_31_50():
    snapshot_path = ROOT.parents[2] / "core/src/main/resources/breed_references.json"
    snapshot = json.loads(snapshot_path.read_text(encoding="utf-8"))
    manifest = snapshot["manifest"]
    breeds = snapshot["breeds"]
    assert manifest["snapshotVersion"] == "2026.09.20.2"
    assert manifest["snapshotDate"] == "2026-09-20"
    assert len(breeds) >= 50
    assert [row["popularityRank"] for row in breeds[:50]] == list(range(1, 51))
    assert [
        (row["popularityRank"], row["registrations"], row["breedId"])
        for row in breeds[30:50]
    ] == EXPECTED_RUNTIME_31_50
    assert [row["russianName"] for row in breeds[30:50]] == EXPECTED_RUSSIAN_NAMES_31_50
    canonical = json.dumps(breeds, ensure_ascii=False, separators=(",", ":"))
    assert hashlib.sha256(canonical.encode("utf-8")).hexdigest() == manifest["numericalDataSha256"]

    by_id = {row["breedId"]: row for row in breeds}
    sources = {row["id"]: row for row in manifest["sources"]}
    for _, _, breed_id in EXPECTED_RUNTIME_31_50:
        values = by_id[breed_id]["values"]
        growth = [row for row in values if not row["adult"]]
        assert len(growth) == 1
        assert (growth[0]["ageMinimumDays"], growth[0]["ageMaximumDays"]) == (0, 730)
        assert growth[0]["statistic"] == "documented_gap"
        assert "size-category curves were not substituted" in growth[0]["gap"]

    dachshund = next(row for row in by_id["VBO:0200410"]["values"] if row["id"] == "dms-adult")
    assert dachshund["statistic"] == "maximum" and dachshund["upper"] == 5
    assert "lower" not in dachshund and "center" not in dachshund
    assert any("5.5 kg" in item for item in dachshund["limitations"])
    assert dachshund["sourceId"] == "wiki-dachshund-miniature"
    miniature_source = sources[dachshund["sourceId"]]
    assert miniature_source["pageOrTable"] == "Infobox"
    assert "Miniature-size infobox maximum of 5.0 kg" in miniature_source["method"]
    assert "16–32 lb" not in miniature_source["method"]

    american_akita = by_id["VBO:0200027"]["values"]
    sheltie = by_id["VBO:0201217"]["values"]
    assert [(row["sex"], row["lower"], row["upper"]) for row in american_akita if row["id"].startswith("ama-wiki-")] == [
        ("male", 45, 59),
        ("female", 32, 45),
    ]
    sheltie_range = next(row for row in sheltie if row["id"] == "she-wiki-adult")
    assert (sheltie_range["sex"], sheltie_range["lower"], sheltie_range["upper"]) == ("combined", 6.8, 11.3)
    assert next(row for row in american_akita if row["id"] == "ama-adult-gap")["activeForProduct"] is False
    assert next(row for row in sheltie if row["id"] == "she-adult-gap")["activeForProduct"] is False

    basenji = by_id["VBO:0200120"]["values"]
    basenji_range = next(row for row in basenji if row["id"] == "bas-wiki-adult")
    assert (basenji_range["lower"], basenji_range["upper"]) == (9.1, 10.9)
    assert all(row["activeForProduct"] is False for row in basenji if row["id"] in {"bas-mw", "bas-fw"})

    rottweiler = by_id["VBO:0201143"]["values"]
    assert [(row["sex"], row["lower"], row["upper"]) for row in rottweiler if row["id"].startswith("rot-wiki-")] == [
        ("male", 50, 60),
        ("female", 35, 48),
    ]
    assert all(row["activeForProduct"] is False for row in rottweiler if row["id"] in {"rot-male", "rot-female"})
    collie_female = next(row for row in by_id["VBO:0200375"]["values"] if row["id"] == "col-female-gap")
    assert collie_female["sex"] == "female" and collie_female["statistic"] == "documented_gap"
    assert "no numeric female interval was synthesized" in collie_female["gap"]

    assert sources["fci086"]["pageOrTable"] == "p.4"
    assert sources["fci147"]["pageOrTable"] == "p.6"
    assert "CC BY-SA" in sources["wiki-westie"]["method"]
    assert "oldid=1351259637" in sources["wiki-basenji"]["url"]
    assert "oldid=154838607" in sources["wiki-central-asian-range"]["url"]
    assert by_id["VBO:0200764"]["values"][0]["sourceId"] == "fci003"
    assert by_id["VBO:0200764"]["values"][1]["sourceId"] == "wiki-kerry-blue"


def validate():
    manifest = validate_manifest()
    validate_11_15()
    validate_16_20()
    validate_21_25()
    validate_26_30()
    validate_distinctions(manifest)
    validate_runtime_snapshot_31_50()


if __name__ == "__main__":
    validate()
    print("issue 83/125 validation: research ranks 11-30 and runtime ranks 31-50, provenance, semantics, gaps and varieties OK")
