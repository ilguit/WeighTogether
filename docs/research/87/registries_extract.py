#!/usr/bin/env python3
"""Reproduce Russian cattery-by-breed supply snapshots for issue #87.

Only Python's standard library is required. By default pages are downloaded from
the two public federation registers. ``--cache-dir`` accepts a directory holding
``farus-index.html``, ``farus-<category>.html`` and ``felis.html`` for offline use.
"""

from __future__ import annotations

import argparse
import csv
import html
import re
import urllib.request
from collections import Counter
from datetime import date, datetime
from pathlib import Path

ACCESSED = date(2026, 9, 9)
FARUS_BASE = "https://xn--80a6adhc.xn--p1ai"
FARUS_INDEX = f"{FARUS_BASE}/catteries/"
FELIS_URL = "https://felis-russica.com/pitomniki-felis-russica.html"

# FARUS uses a mixture of FIFe/WCF EMS and its own extensions. The target names
# are copied verbatim from core/src/main/resources/breed_catalog.json.
CODE_MAP = {
    "ABY": ("Abyssinian", "VBO:0100000"),
    "ALC": ("Asian Leopard Cat", "VBO:0100029"),
    "ASH": ("American Shorthair", "VBO:0100018"),
    "BAL": ("Balinese", "VBO:0100036"),
    "BAM": ("Bambino", "VBO:0100037"),
    "BBN": ("Bambino", "VBO:0100037"),
    "BEN": ("Bengal", "VBO:0100040"),
    "BLH": ("British Longhair", "VBO:0100051"),
    "BOM": ("Bombay", "VBO:0100045"),
    "BRI": ("British Shorthair", "VBO:0100052"),
    "BRL": ("British Longhair", "VBO:0100051"),
    "BUR": ("Burmese", "VBO:0100053"),
    "CHS": ("Chausie", "VBO:0100068"),
    "CRX": ("Cornish Rex", "VBO:0100077"),
    "DRX": ("Devon Rex", "VBO:0100084"),
    "DSX": ("Donskoy", "VBO:0100086"),
    "DWF": ("Dwelf", "VBO:0100089"),
    "DWE": ("Dwelf", "VBO:0100089"),
    "ELF": ("Elf", "VBO:0100091"),
    "EXO": ("Exotic Shorthair", "VBO:0100096"),
    "KBL": ("Kurilian Bobtail Longhair", "VBO:0100147"),
    "KBS": ("Kurilian Bobtail Shorthair", "VBO:0100148"),
    "MAU": ("Egyptian Mau", "VBO:0100090"),
    "MCO": ("Maine Coon", "VBO:0100154"),
    "MCP": ("Maine Coon Polydactyl", "VBO:0100155"),
    "MIL": ("Minuet Longhair", "VBO:0100164"),
    "MIS": ("Minuet", "VBO:0100163"),
    "MNC": ("Munchkin", "VBO:0100169"),
    "MNL": ("Munchkin Longhair", "VBO:0100170"),
    "MNS": ("Munchkin (Munchk), Short-Haired", "VBO:0100303"),
    "NEB": ("Nebelung", "VBO:0100172"),
    "NFO": ("Norwegian Forest Cat", "VBO:0100178"),
    "OLH": ("Oriental Longhair", "VBO:0100183"),
    "ORI": ("Oriental Shorthair", "VBO:0100184"),
    "OSH": ("Oriental Shorthair", "VBO:0100184"),
    "PBD": ("Peterbald", "VBO:0100189"),
    "PER": ("Persian", "VBO:0100188"),
    "RAG": ("Ragdoll", "VBO:0100196"),
    "RUS": ("Russian Blue", "VBO:0100200"),
    "SAV": ("Savannah", "VBO:0100208"),
    "SBI": ("Sacred Birman", "VBO:0100204"),
    "SCL": ("Scottish Straight Longhair", "VBO:0100214"),
    "SCS": ("Scottish Straight", "VBO:0100213"),
    "SFL": ("Scottish Fold Longhair", "VBO:0100210"),
    "SFS": ("Scottish Fold", "VBO:0100209"),
    "SIA": ("Siamese", "VBO:0100221"),
    "SKL": ("Selkirk Rex Longhair", "VBO:0100216"),
    "SKS": ("Selkirk Rex Shorthair", "VBO:0100217"),
    "SOM": ("Somali", "VBO:0100229"),
    "SPH": ("Sphynx", "VBO:0100230"),
    "SRL": ("Selkirk Rex Longhair", "VBO:0100216"),
    "SRS": ("Selkirk Rex Shorthair", "VBO:0100217"),
    "STB": ("Toybob", "VBO:0100244"),
    "SYS": ("Seychellois Short Hair", "VBO:0100314"),
    "THA": ("Thai", "VBO:0100235"),
    "TGR": ("Toyger", "VBO:0100245"),
    "TUA": ("Turkish Angora", "VBO:0100249"),
    "ULV": ("Ukrainian Levkoy", "VBO:0100252"),
    "URS": ("Ural Rex", "VBO:0100253"),
}
UNRESOLVED_CODES = {"BBS", "DLF", "NIB", "SBT", "SPL"}

RU_MAP = {
    "абиссинская": ("Abyssinian", "VBO:0100000", "exact"),
    "бенгальская": ("Bengal", "VBO:0100040", "exact"),
    "британская": ("British Shorthair", "VBO:0100052", "aggregate"),
    "британская короткошерстная": ("British Shorthair", "VBO:0100052", "exact"),
    "бурманская": ("Burmese", "VBO:0100053", "exact"),
    "бурма": ("Burmese", "VBO:0100053", "exact"),
    "бурмилла": ("Burmilla", "VBO:0100056", "exact"),
    "корниш рекс": ("Cornish Rex", "VBO:0100077", "exact"),
    "девон рекс": ("Devon Rex", "VBO:0100084", "exact"),
    "девон-рекс": ("Devon Rex", "VBO:0100084", "exact"),
    "канадский сфинкс": ("Sphynx", "VBO:0100230", "exact"),
    "курильский бобтейл": ("Kurilian Bobtail", "VBO:0100146", "aggregate"),
    "мейн кун": ("Maine Coon", "VBO:0100154", "exact"),
    "мейн-кун": ("Maine Coon", "VBO:0100154", "exact"),
    "менй-кун": ("Maine Coon", "VBO:0100154", "exact"),
    "мэйн-кун": ("Maine Coon", "VBO:0100154", "exact"),
    "невская маскарадная": ("Neva Masquerade", "VBO:0100173", "exact"),
    "норвежская лесная": ("Norwegian Forest Cat", "VBO:0100178", "exact"),
    "ориентальная": ("Oriental Shorthair", "VBO:0100184", "exact"),
    "осикет": ("Ocicat", "VBO:0100179", "exact"),
    "персидская": ("Persian", "VBO:0100188", "exact"),
    "персы и экзоты": ("Persian / Exotic group", "VBO:0100188|VBO:0100096", "aggregate"),
    "регдолл": ("Ragdoll", "VBO:0100196", "exact"),
    "рэгдолл": ("Ragdoll", "VBO:0100196", "exact"),
    "русская голубая": ("Russian Blue", "VBO:0100200", "exact"),
    "священная бирма": ("Sacred Birman", "VBO:0100204", "exact"),
    "сиамская": ("Siamese", "VBO:0100221", "exact"),
    "сибирская": ("Siberian", "VBO:0100223", "exact"),
    "сибирская традиционная": ("Siberian", "VBO:0100223", "exact"),
    "сомали": ("Somali", "VBO:0100229", "exact"),
    "сфинкс": ("Sphynx", "VBO:0100230", "exact"),
    "тайская": ("Thai", "VBO:0100235", "exact"),
    "экзотическая": ("Exotic Shorthair", "VBO:0100096", "aggregate"),
    "экзотическая короткошерстная": ("Exotic Shorthair", "VBO:0100096", "exact"),
}

FIELDS = [
    "source_id", "cattery_id", "cattery_name", "breed_original", "breed_name_en",
    "scalesync_vbo_id", "mapping_decision", "association_count", "geography",
    "sample_type", "organization", "denominator", "url", "accessed_at", "limitations_bias",
]


def fetch(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": "ScaleSync-research/87"})
    with urllib.request.urlopen(req, timeout=45) as response:
        return response.read().decode("utf-8", "replace")


def strip(fragment: str) -> str:
    fragment = re.sub(r"<br\s*/?>", "\n", fragment, flags=re.I)
    fragment = re.sub(r"<script\b.*?</script>", "", fragment, flags=re.I | re.S)
    fragment = re.sub(r"<[^>]+>", "", fragment)
    return re.sub(r"[ \t]+", " ", html.unescape(fragment)).strip()


def foreign_address(text: str) -> bool:
    markers = ("беларус", "казахстан", "usa", "сша", "германи", "израил", "латви", "эстони", "киргиз", "кыргыз")
    return any(marker in text.lower() for marker in markers)


def farus_rows(index: str, pages: dict[str, str]) -> list[dict[str, str]]:
    category_ids = list(dict.fromkeys(re.findall(r'href="/catteries/(\d+)/"', index)))
    raw: list[tuple[str, str, str, list[str], str]] = []
    for category in category_ids:
        page = pages[category]
        for tr in re.findall(r"<tr>(.*?)</tr>", page, re.I | re.S):
            link = re.search(r'<a href="([^"]+)" class="black">(.*?)</a>', tr, re.I | re.S)
            cells = re.findall(r"<td\b[^>]*>(.*?)</td>", tr, re.I | re.S)
            if not link or not cells:
                continue
            item_url = link.group(1)
            name = re.sub(r"\s+", " ", strip(link.group(2)))
            lines = [re.sub(r"\s+", " ", part).strip() for part in strip(cells[-1]).splitlines() if part.strip()]
            full = " | ".join(lines)
            raw.append((item_url, name, full, lines, category))

    # Item paths are stable record identifiers. Defensive de-duplication avoids
    # counting a card twice if it is ever linked from two club categories.
    unique = {record[0]: record for record in raw}
    active: list[tuple[str, str, str, list[str], str]] = []
    for record in unique.values():
        full = record[2]
        if foreign_address(full):
            continue
        if re.search(r"исключ[её]н|аннулирован|удален", full, re.I):
            continue
        expiry = re.search(r"до\s+(\d{2})\.(\d{2})\.(\d{2,4})", full, re.I)
        if expiry:
            day, month, year = map(int, expiry.groups())
            if year < 100:
                year += 2000
            if date(year, month, day) < ACCESSED:
                continue
        active.append(record)

    rows: list[dict[str, str]] = []
    seen: set[tuple[str, str]] = set()
    for item_url, name, full, lines, _category in active:
        # Breed fields are compact all-caps tokens; accept punctuation and known
        # long-form variants while rejecting registration numbers and addresses.
        codes: list[str] = []
        for line in lines:
            normalized = line.upper().replace("CHAUSIE", "CHS").replace("MCPVAR", "MCP")
            normalized = re.sub(r"\bBN\b", "BBN", normalized)
            for token in re.findall(r"\b[A-Z]{3}\b", normalized):
                if (token in CODE_MAP or token in UNRESOLVED_CODES) and token not in codes:
                    codes.append(token)
        for code in codes:
            key = (item_url, code)
            if key in seen:
                continue
            seen.add(key)
            breed_name, vbo_id = CODE_MAP.get(code, ("", ""))
            rows.append({
                "source_id": "RU-FARUS-CATTERIES-2026-09-09",
                "cattery_id": item_url.rsplit("/", 1)[-1].removesuffix(".html"),
                "cattery_name": name,
                "breed_original": code,
                "breed_name_en": breed_name,
                "scalesync_vbo_id": vbo_id,
                "mapping_decision": "exact" if vbo_id else "unresolved",
                "association_count": "1",
                "geography": "Russia (records with explicit foreign address excluded)",
                "sample_type": "active cattery-breed association",
                "organization": "FARUS",
                "denominator": str(len(active)),
                "url": FARUS_BASE + item_url,
                "accessed_at": ACCESSED.isoformat(),
                "limitations_bias": "Supply-side federation register; multi-breed catteries contribute once to each listed breed; no animal/litter counts; undated cards retained; unknown codes omitted",
            })
    return rows


def felis_rows(page: str) -> list[dict[str, str]]:
    # The official page is hand-authored. Convert block elements to lines, then
    # pair each breed line with the most recent *RU cattery heading.
    text = re.sub(r"</(?:p|div|h\d)>", "\n", page, flags=re.I)
    lines = [re.sub(r"\s+", " ", strip(part)).strip() for part in text.splitlines()]
    current = ""
    deleted = False
    raw: list[tuple[str, str]] = []
    for line in filter(None, lines):
        if "*RU" in line.upper() and not re.search(r"ПИТОМНИК|СПИСОК", line, re.I):
            match = re.search(r"([^|]{1,100}\*RU)", line, re.I)
            if match:
                current = re.sub(r"\s+", " ", match.group(1)).strip(" -–—")
                deleted = bool(re.search(r"удален|удалён|исключ", line, re.I))
        if current and re.match(r"пород(?:а|ы)\s*:", line, re.I):
            deleted = deleted or bool(re.search(r"удален|удалён|исключ", line, re.I))
            if deleted:
                continue
            breeds = re.sub(r"^пород(?:а|ы)\s*:\s*", "", line, flags=re.I)
            for label in re.split(r"[,;/]", breeds):
                label = re.sub(r"\s+", " ", label).strip(" . ").lower().replace("ё", "е")
                if label:
                    raw.append((current, label))

    unique = sorted(set(raw))
    cattery_count = len({name for name, _ in unique})
    rows: list[dict[str, str]] = []
    for name, label in unique:
        normalized = label.replace("короткошерстная", "короткошерстная")
        mapped = RU_MAP.get(normalized)
        if mapped:
            breed_name, vbo_id, decision = mapped
        elif normalized in {"восточные", "ориенталы", "сиамские и ориентальные"}:
            breed_name, vbo_id, decision = "Siamese / Oriental group", "VBO:0100221|VBO:0100184", "aggregate"
        else:
            breed_name, vbo_id, decision = "", "", "unresolved"
        rows.append({
            "source_id": "RU-FELIS-RUSSICA-CATTERIES-2026-09-09",
            "cattery_id": name.casefold(),
            "cattery_name": name,
            "breed_original": label,
            "breed_name_en": breed_name,
            "scalesync_vbo_id": vbo_id,
            "mapping_decision": decision,
            "association_count": "1",
            "geography": "Russia",
            "sample_type": "listed cattery-breed association",
            "organization": "Felis Russica (FIFe member)",
            "denominator": str(cattery_count),
            "url": FELIS_URL,
            "accessed_at": ACCESSED.isoformat(),
            "limitations_bias": "Supply-side federation webpage updated in place; no complete publication date or activity date per cattery; deleted entries excluded when marked; free-text breeds normalized conservatively; no animal/litter counts",
        })
    return rows


def summarize(rows: list[dict[str, str]], output: Path) -> None:
    mapped = [row for row in rows if row["mapping_decision"] == "exact"]
    counts = Counter((row["source_id"], row["breed_name_en"], row["scalesync_vbo_id"]) for row in mapped)
    fields = ["source_id", "breed_name_en", "scalesync_vbo_id", "cattery_breed_records", "rank"]
    with output.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields, lineterminator="\n")
        writer.writeheader()
        for source_id in sorted({key[0] for key in counts}):
            source_counts = [(key[1], key[2], value) for key, value in counts.items() if key[0] == source_id]
            source_counts.sort(key=lambda item: (-item[2], item[0]))
            rank = 0
            previous = None
            for position, (breed, vbo_id, count) in enumerate(source_counts, 1):
                if count != previous:
                    rank = position
                    previous = count
                writer.writerow({"source_id": source_id, "breed_name_en": breed, "scalesync_vbo_id": vbo_id, "cattery_breed_records": count, "rank": rank})


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache-dir", type=Path)
    parser.add_argument("--output-dir", type=Path, default=Path(__file__).parent)
    args = parser.parse_args()
    if args.cache_dir:
        index = (args.cache_dir / "farus-index.html").read_text(encoding="utf-8")
        felis = (args.cache_dir / "felis.html").read_text(encoding="utf-8")
        category_ids = list(dict.fromkeys(re.findall(r'href="/catteries/(\d+)/"', index)))
        pages = {key: (args.cache_dir / f"farus-{key}.html").read_text(encoding="utf-8") for key in category_ids}
    else:
        index = fetch(FARUS_INDEX)
        felis = fetch(FELIS_URL)
        category_ids = list(dict.fromkeys(re.findall(r'href="/catteries/(\d+)/"', index)))
        pages = {key: fetch(f"{FARUS_INDEX}{key}/") for key in category_ids}

    rows = farus_rows(index, pages) + felis_rows(felis)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    detail = args.output_dir / "registries-cattery-breed.csv"
    with detail.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=FIELDS, lineterminator="\n")
        writer.writeheader()
        writer.writerows(sorted(rows, key=lambda row: (row["source_id"], row["cattery_id"], row["breed_original"])))
    summarize(rows, args.output_dir / "registries-breed-counts.csv")


if __name__ == "__main__":
    main()
