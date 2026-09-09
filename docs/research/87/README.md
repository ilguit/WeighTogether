# Russian cat-breed popularity dataset (issue #87)

Snapshot date: 2026-09-09. The detailed source assessment is in
[`source-audit.md`](source-audit.md). The independent comparison, aggregation
sensitivity analysis, final range recommendation, UI fallback, and data-request
registry are in [`final-analysis.md`](final-analysis.md).

## Result

No open, reproducible Russian dataset covering at least 50 comparable breeds was
found. Consequently this directory does **not** manufacture a 1–50 statistical
ranking. `population-ranking.csv` contains the evidence-backed population order:
the five breed labels published by the 2023 nationwide pet census. The already
known 490-thousand-profile Яндекс ID source is preserved independently in
`yandex-id-ranking.csv`; it publishes an ordinal cat top-5 and a 58% cat share,
but no breed counts. Accordingly, `count` is null in both ranking tables.

The product fallback implied by the issue is therefore: preserve the proven
top five, then order the remaining applicable catalog breeds alphabetically for
display. Those alphabetical positions are not popularity ranks and are
deliberately not materialized in the evidence table.

## Files and metric boundaries

- `population-ranking.csv` contains the population ranking evidence.
- `yandex-id-ranking.csv` contains an independent self-selected user-profile
  ranking; it must not be merged with or treated as a population estimate.
  Both ranking tables contain mandatory provenance and ScaleSync/VBO fields.
- `quantitative-contexts.csv` preserves numeric denominators and contextual
  observations that cannot rank breeds. Population, cattery, and exhibition
  entry units remain explicit and must not be summed.
- `excluded-series.csv` records registration, litter, cattery, user-registry,
  exhibition-entry, and points sources that cannot yield comparable breed
  counts. `numeric_data_available=yes` means numbers exist but have the wrong
  meaning; it does not make the series suitable for ranking.
- `verify_dataset.py` checks schema, catalog/VBO IDs, null breed counts, metric
  separation, the published rank order, and the SHA-256 checksums recorded in
  `CHECKSUMS.sha256`.

## Mapping decisions

`exact` means that the published label maps directly to the current ScaleSync
catalog concept. `aggregate`/`split` require explicit source support;
`unresolved` means no defensible single catalog target. The household labels
Scottish Fold and Siamese, and the Яндекс label Sphynx, are unresolved and carry
no VBO ID: a group observation must not be attached to one member concept.

## Rank and tie rule

Ranks 1–5 are source-published ordinal positions, not ranks reconstructed from
unknown counts. Sorting ascending by `rank` reproduces the published order. The
source has no ties. If a later source reports ties, equal ranks must be retained;
VBO ID may be used only as a stable serialization key and must not turn a tie
into distinct popularity positions.

Run the reproducibility check from the repository root:

```bash
python3 docs/research/87/verify_dataset.py
```

The two top-5 sets overlap on four labels (4/5, or 80% set overlap): British
Shorthair, Scottish Fold, Maine Coon, and Siberian. Their common order differs:
Maine Coon is third in Яндекс but fourth in the census. Siamese occurs only in
the census top-5; Sphynx only in Яндекс. This is ordinal corroboration, not a
count comparison.

The >=50-breed grading criterion remains unmet. Promotion beyond the top five
requires a broad Russian source with comparable units, reproducible totals, and
a leading group that does not contradict an independent source.
