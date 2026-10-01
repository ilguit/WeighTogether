#!/usr/bin/env python3
"""Trace the approved reference into Android vector source (never edit the bitmap).

Requires Pillow. Run from the repository root. See README.md for the contract.
"""
from collections import defaultdict
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[3]
image = Image.open(Path(__file__).with_name('approved-composition.png'))
pixels = image.load()
# Alpha separates the opaque silhouettes/lettering from the translucent crossing.
solid = {(x, y) for y in range(200, 935) for x in range(image.width)
         if pixels[x, y][3] >= 225}
edges = defaultdict(list)
for x, y in solid:
    for neighbor, a, b in [
        ((x, y - 1), (x, y), (x + 1, y)),
        ((x + 1, y), (x + 1, y), (x + 1, y + 1)),
        ((x, y + 1), (x + 1, y + 1), (x, y + 1)),
        ((x - 1, y), (x, y + 1), (x, y)),
    ]:
        if neighbor not in solid:
            edges[a].append(b)


def simplify(points, epsilon=1.1):
    if len(points) < 3:
        return points
    a, b = points[0], points[-1]
    dx, dy = b[0] - a[0], b[1] - a[1]
    length_squared = dx * dx + dy * dy
    distances = [
        ((dy * (x - a[0]) - dx * (y - a[1])) ** 2 / length_squared
         if length_squared else (x - a[0]) ** 2 + (y - a[1]) ** 2)
        for x, y in points
    ]
    pivot = max(range(len(points)), key=lambda i: distances[i])
    if distances[pivot] <= epsilon * epsilon:
        return [a, b]
    return simplify(points[:pivot + 1], epsilon)[:-1] + simplify(points[pivot:], epsilon)


contours = []
while edges:
    start = min(edges)
    points, current = [start], start
    while True:
        following = edges[current].pop()
        if not edges[current]:
            del edges[current]
        points.append(following)
        current = following
        if current == start:
            break
    area = abs(sum(points[i][0] * points[i + 1][1] - points[i + 1][0] * points[i][1]
                   for i in range(len(points) - 1))) / 2
    # Discard alpha speckles/texture; preserve letter counters and major contours.
    if area > 100:
        contours.append(simplify(points))

# Uniform fit: the original 1208 x 858 composition's diagonal becomes 188dp,
# leaving 2dp clearance inside the platform's 192dp-diameter safe circle.
scale = 188 / (1208 ** 2 + 858 ** 2) ** .5


def point(p):
    return f'{144 + (p[0] - 624) * scale:.3f},{144 + (p[1] - 637) * scale:.3f}'


def path(points):
    return 'M' + point(points[0]) + ''.join('L' + point(p) for p in points[1:]) + 'Z'


lines = [
    '<?xml version="1.0" encoding="utf-8"?>',
    '<!-- Approved #150 composition; see docs/design/150/README.md. -->',
    '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
    '    android:width="288dp" android:height="288dp"',
    '    android:viewportWidth="288" android:viewportHeight="288">',
]
for index, (left, right) in enumerate([(20, 230), (358, 598), (675, 915), (1014, 1228)]):
    ratio = (838 - 580) / (1066 - 580)
    points = [(627 + (left - 627) * ratio, 838),
              (627 + (right - 627) * ratio, 838), (right, 1066), (left, 1066)]
    lines.append(f'    <path android:name="crossing_{index}" android:fillColor="#28766B" '
                 f'android:fillAlpha="0.8" android:pathData="{path(points)}" />')
lines.append('    <path android:name="wordmark_and_family" android:fillColor="#28766B" '
             'android:fillAlpha="1" android:fillType="evenOdd" android:pathData="' +
             ' '.join(path(points) for points in contours) + '" />')
lines.append('</vector>')
(ROOT / 'app/src/main/res/drawable/ic_weigh_together_splash.xml').write_text('\n'.join(lines) + '\n')
print(f'{len(contours)} contours; uniform scale {scale}')
