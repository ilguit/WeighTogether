"""Exploratory empirical bands, NOT a reproduction of the Salt GAMLSS model.

Usage: python analyze.py /path/KGC1_dataset.csv
Requires matplotlib. Raw animal-level observations are not copied into docs.
"""
import collections
import csv
import hashlib
import json
import math
from pathlib import Path
import sys

EXPECTED_SHA = '76be97fd71d5139fb648e58c69db58945c221df33f1b7f15fc12e244db90e094'
OUT = Path(__file__).resolve().parent
PROBS = (.02, .09, .25, .5, .75, .91, .98)


def quantile(values, p):
    values = sorted(values)
    pos = (len(values) - 1) * p
    lo = math.floor(pos)
    hi = math.ceil(pos)
    return values[lo] + (values[hi] - values[lo]) * (pos - lo)


def main():
    raw = Path(sys.argv[1]).read_bytes()
    assert hashlib.sha256(raw).hexdigest() == EXPECTED_SHA, 'Unexpected source bytes'
    records = list(csv.DictReader(raw.decode('utf-8-sig').splitlines()))
    # The actual header is Sex; the upstream README calls it SEX.
    counts = collections.Counter((r['DataSet'], r['Sex']) for r in records)
    groups = {s: [] for s in ('F', 'M')}
    for r in records:
        if r['DataSet'] != 'Modelling' or r['Sex'] not in groups:
            continue
        age = float(r['Visit_Age_Yrs']) * 365.25 / 7
        weight = float(r['Weight_kg'])
        if math.isfinite(age) and math.isfinite(weight) and 8 <= age <= 78 and weight > 0:
            groups[r['Sex']].append((age, weight, r['Cat_ID']))
    results = []
    # Full window widths; windows are clipped at 8 and 78 weeks.
    # Compare all visits against one visit per animal nearest the centre.
    for sex, observations in groups.items():
        for width in (2, 4, 8):
            for week in range(8, 79):
                window = [r for r in observations if abs(r[0] - week) <= width / 2]
                animals = {}
                for row in window:
                    key = (abs(row[0] - week), row[0], row[1])
                    prev = animals.get(row[2])
                    if prev is None or key < (abs(prev[0] - week), prev[0], prev[1]):
                        animals[row[2]] = row
                for mode, rows in [('all_visits', window), ('one_per_cat', list(animals.values()))]:
                    record = dict(sex=sex, week=week, window_weeks=width, mode=mode,
                                  observations=len(rows), cats=len(animals))
                    for p in PROBS:
                        record[f'p{round(p * 100):02}'] = round(quantile([r[1] for r in rows], p), 6) if rows else ''
                    results.append(record)
    with (OUT / 'empirical-bands.csv').open('w', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=list(results[0]), lineterminator='\n')
        writer.writeheader()
        writer.writerows(results)
    summary = dict(source_sha256=EXPECTED_SHA, raw_rows=len(records),
                   raw_groups={f'{k[0]}:{k[1]}': v for k, v in sorted(counts.items())},
                   eligible={s: dict(rows=len(rows), cats=len({r[2] for r in rows})) for s, rows in groups.items()},
                   windows={})
    for sex in groups:
        for width in (2, 4, 8):
            rows = [r for r in results if r['sex'] == sex and r['window_weeks'] == width and r['mode'] == 'one_per_cat']
            summary['windows'][f'{sex}:{width}'] = dict(
                min_cats=min(r['cats'] for r in rows), max_cats=max(r['cats'] for r in rows),
                centres_below_30=sum(r['cats'] < 30 for r in rows),
                centres_below_250=sum(r['cats'] < 250 for r in rows))
    (OUT / 'audit.json').write_text(json.dumps(summary, indent=2) + '\n')
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    fig, axes = plt.subplots(2, 2, figsize=(13, 8), sharex=True)
    for col, sex in enumerate(('F', 'M')):
        rows = [r for r in results if r['sex'] == sex and r['window_weeks'] == 4 and r['mode'] == 'one_per_cat']
        x = [r['week'] for r in rows]
        ax = axes[0, col]
        ax.fill_between(x, [r['p02'] for r in rows], [r['p98'] for r in rows], color='#bad3e8', label='P2–P98')
        ax.fill_between(x, [r['p09'] for r in rows], [r['p91'] for r in rows], color='#639dca', label='P9–P91')
        ax.plot(x, [r['p50'] for r in rows], color='#152e49', label='P50')
        ax.set_title('Female' if sex == 'F' else 'Male')
        ax.set_ylabel('Weight (kg)')
        ax.set_ylim(bottom=0)
        ax.legend()
        for width in (2, 4, 8):
            rr = [r for r in results if r['sex'] == sex and r['window_weeks'] == width and r['mode'] == 'one_per_cat']
            axes[1, col].plot(x, [r['cats'] for r in rr], label=f'{width}-week window')
        axes[1, col].axhline(30, color='gray', linestyle=':', label='30 animals')
        axes[1, col].set_ylabel('Unique animals in window')
        axes[1, col].set_xlabel('Age (weeks)')
        axes[1, col].legend()
    for ax in axes.flat:
        ax.grid(alpha=.2)
    fig.suptitle('Exploratory published Modelling subset: NOT fitted growth standards\n4-week windows; one observation per cat; no additional outlier removal')
    fig.tight_layout()
    fig.savefig(OUT / 'empirical-bands.png', dpi=150)
    plt.close(fig)
    for r in results:
        if r['observations']:
            qs = [r[f'p{round(p * 100):02}'] for p in PROBS]
            assert all(0 < a <= b for a, b in zip(qs, qs[1:]))
    print(json.dumps(summary, indent=2))


if __name__ == '__main__':
    main()
