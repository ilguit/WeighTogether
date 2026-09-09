#!/usr/bin/env python3
"""Build a non-population evidence-priority list from separately ranked sources."""

import csv
import statistics
from collections import defaultdict
from pathlib import Path


HERE = Path(__file__).resolve().parent
OUTPUT = HERE / "evidence-priority.csv"
FIELDS = [
    "priority", "evidence_tier", "breed_name_en", "scalesync_vbo_id",
    "source_series_count", "farus_rank", "felis_rank", "wcf_event_rank",
    "avito_rank", "vetas_rank", "ingos_rank", "median_normalized_rank",
    "interpretation",
]
SERIES = ("farus", "felis", "wcf_event", "avito", "vetas", "ingos")
MAX_RANK = {"farus": 42, "felis": 22, "wcf_event": 14, "avito": 10, "vetas": 5, "ingos": 5}
BASELINE_FAMILY_PREFIXES = ("British", "Scottish", "Siamese", "Maine Coon", "Siberian")


def outside_baseline_family(name: str) -> bool:
    return not name.startswith(BASELINE_FAMILY_PREFIXES)


def collect() -> dict[tuple[str, str], dict[str, int]]:
    evidence: dict[tuple[str, str], dict[str, int]] = defaultdict(dict)

    with (HERE / "registries-breed-counts.csv").open(encoding="utf-8", newline="") as stream:
        for row in csv.DictReader(stream):
            series = "farus" if "FARUS" in row["source_id"] else "felis"
            if row["scalesync_vbo_id"] and outside_baseline_family(row["breed_name_en"]):
                evidence[(row["scalesync_vbo_id"], row["breed_name_en"])][series] = int(row["rank"])

    with (HERE / "exhibitions-tyumen-2024.csv").open(encoding="utf-8", newline="") as stream:
        for row in csv.DictReader(stream):
            if (
                row["mapping_decision"] == "exact"
                and row["source_breed_code"] not in {"HHS", "XLH"}
                and outside_baseline_family(row["breed_name_en"])
            ):
                evidence[(row["scalesync_vbo_id"], row["breed_name_en"])]["wcf_event"] = int(row["rank"])

    consumer_series = {
        "RU-AVITO-DEMAND-2018": "avito",
        "RU-VETAS-MOSCOW-2023": "vetas",
        "RU-INGOS-FU-2023": "ingos",
    }
    with (HERE / "consumer-proxy-ranking.csv").open(encoding="utf-8", newline="") as stream:
        for row in csv.DictReader(stream):
            if row["outside_population_top5"] == "yes" and row["mapping_decision"] == "exact":
                evidence[(row["scalesync_vbo_id"], row["breed_name_en"])][consumer_series[row["source_id"]]] = int(row["source_rank"])
    return evidence


def build_rows() -> list[dict[str, str | int]]:
    candidates = []
    for (vbo_id, name), ranks in collect().items():
        support = len(ranks)
        tier = "A" if support >= 3 else "B" if support == 2 else "C"
        median = statistics.median(ranks[key] / MAX_RANK[key] for key in ranks)
        candidates.append((tier, -support, median, name, vbo_id, ranks))
    candidates.sort()

    rows = []
    for priority, (tier, negative_support, median, name, vbo_id, ranks) in enumerate(candidates, 1):
        row: dict[str, str | int] = {
            "priority": priority,
            "evidence_tier": tier,
            "breed_name_en": name,
            "scalesync_vbo_id": vbo_id,
            "source_series_count": -negative_support,
            "median_normalized_rank": f"{median:.6f}",
            "interpretation": "application coverage priority; not population rank",
        }
        row.update({f"{series}_rank": ranks.get(series, "") for series in SERIES})
        rows.append(row)
    return rows


def main() -> None:
    with OUTPUT.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=FIELDS, lineterminator="\n")
        writer.writeheader()
        writer.writerows(build_rows())


if __name__ == "__main__":
    main()
