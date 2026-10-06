#!/usr/bin/env python3
"""Render the public policy from the exact UTF-8 text shipped in the APK."""
import argparse
import html
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "app/src/main/res/raw/privacy_policy_ru.txt"
OUTPUT = ROOT / "docs/rustore/privacy-policy.html"


def render() -> str:
    paragraphs = SOURCE.read_text(encoding="utf-8").strip().split("\n\n")
    elements = []
    for index, paragraph in enumerate(paragraphs):
        tag = "h1" if index == 0 else "h2" if re.fullmatch(r"[1-8]\. [^\n]+", paragraph) else "p"
        elements.append(f"<{tag}>{html.escape(paragraph)}</{tag}>")
    return """<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Политика конфиденциальности Weigh Together</title>
<style>
body { margin: auto; max-width: 48rem; padding: 1.5rem; font: 1rem/1.6 system-ui, sans-serif; color: #19211e; background: #f5f7f3; overflow-wrap: anywhere; }
h1 { font-size: 1.8rem; line-height: 1.25; } h2 { font-size: 1.2rem; margin-top: 2rem; }
</style>
</head>
<body>
<main>
""" + "\n".join(elements) + "\n</main>\n</body>\n</html>\n"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Fail if the generated page has drifted")
    args = parser.parse_args()
    rendered = render()
    if args.check:
        if not OUTPUT.exists() or OUTPUT.read_text(encoding="utf-8") != rendered:
            parser.exit(1, "Policy HTML is stale; run docs/rustore/render-privacy-policy.py\n")
        print("Policy HTML matches the APK text")
    else:
        OUTPUT.write_text(rendered, encoding="utf-8")
