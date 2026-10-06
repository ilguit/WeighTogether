#!/usr/bin/env python3
"""Rasterize the existing polygon-only Android vector (Pillow required).

Run from any directory: python3 docs/rustore/assets/render-icon.py
Even-odd masks preserve the vector's cutouts. Fail on unsupported path commands.
"""
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from PIL import Image, ImageChops, ImageDraw

root = Path(__file__).resolve().parents[3]
android = "{http://schemas.android.com/apk/res/android}"
vector = ET.parse(root / "app/src/main/res/drawable/ic_app_foreground.xml").getroot()
colors = ET.parse(root / "app/src/main/res/values/colors.xml").getroot()
background = next(c.text for c in colors if c.attrib.get("name") == "scalesync_primary")
size = 512
supersampling = 4
canvas_size = size * supersampling
image = Image.new("RGB", (canvas_size, canvas_size), background)
scale_x = canvas_size / float(vector.attrib[android + "viewportWidth"])
scale_y = canvas_size / float(vector.attrib[android + "viewportHeight"])
for path in vector:
    assert path.tag == "path"
    assert path.attrib[android + "fillType"] == "evenOdd"
    data = path.attrib[android + "pathData"]
    assert not re.search(r"[A-KN-Yac-z]", data), "Only absolute M/L/Z paths are supported"
    mask = Image.new("1", image.size)
    for contour in data.split("Z"):
        if not contour.strip():
            continue
        pairs = re.findall(r"[ML]\s*(-?[\d.]+),(-?[\d.]+)", contour)
        assert len(pairs) >= 3
        polygon = Image.new("1", image.size)
        ImageDraw.Draw(polygon).polygon(
            [(float(x) * scale_x, float(y) * scale_y) for x, y in pairs], fill=1
        )
        mask = ImageChops.logical_xor(mask, polygon)
    image.paste(path.attrib[android + "fillColor"], mask=mask.convert("L"))
image.resize((size, size), Image.Resampling.LANCZOS).save(
    Path(__file__).with_name("icon-512.png"), optimize=True
)
