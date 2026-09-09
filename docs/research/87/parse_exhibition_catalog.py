#!/usr/bin/env python3
"""Extract WCF EMS-code counts from the text layer of the Tyumen 2024 PDF.

Usage:
  pdftotext -layout 1_katalog_5_6_okt_wcf.pdf catalog.txt
  python3 parse_exhibition_catalog.py catalog.txt
"""

import collections
import pathlib
import re
import sys

ENTRY = re.compile(r"^\s*(SFL 71|SFS 71|[A-Z]{3})\b.*Class \(класс\)")


def extract(path: pathlib.Path) -> collections.Counter[str]:
    counts: collections.Counter[str] = collections.Counter()
    for line in path.read_text(encoding="utf-8").splitlines():
        match = ENTRY.match(line)
        if match:
            counts[match.group(1)] += 1
    return counts


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: parse_exhibition_catalog.py <pdftotext-output>")
    counts = extract(pathlib.Path(sys.argv[1]))
    for code, count in sorted(counts.items(), key=lambda item: (-item[1], item[0])):
        print(f"{code},{count}")
    print(f"TOTAL,{sum(counts.values())}")
