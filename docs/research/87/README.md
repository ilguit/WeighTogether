# Russian cat-breed popularity dataset (issue #87)

Snapshot date: 2026-09-09. The detailed source assessment is in
[`source-audit.md`](source-audit.md). The independent comparison, aggregation
sensitivity analysis, final range recommendation, UI fallback, and data-request
registry are in [`final-analysis.md`](final-analysis.md).

## Result

No open, reproducible Russian dataset covering at least 50 comparable breeds was
found. Consequently this directory does **not** manufacture a 1–50 statistical
ranking. `population-ranking.csv` contains the only evidence-backed population
order: the five breed labels published by the 2023 nationwide pet census. The
source publishes neither breed counts nor breed shares, so `count` is null.

The product fallback implied by the issue is therefore: preserve the proven
top five, then order the remaining applicable catalog breeds alphabetically for
display. Those alphabetical positions are not popularity ranks and are
deliberately not materialized in the evidence table.

## Files and metric boundaries

- `population-ranking.csv` is the sole breed-ranking evidence table. It contains
  all mandatory provenance and ScaleSync/VBO mapping fields.
- `quantitative-contexts.csv` preserves numeric denominators and contextual
  observations that cannot rank breeds. Population, cattery, and exhibition
  entry units remain explicit and must not be summed.
- `excluded-series.csv` records registration, litter, cattery, user-registry,
  exhibition-entry, and points sources that cannot yield comparable breed
  counts. `numeric_data_available=yes` means numbers exist but have the wrong
  meaning; it does not make the series suitable for ranking.
- `verify_dataset.py` checks schema, catalog/VBO IDs, null breed counts, metric
  separation, and the published rank order.

## Mapping decisions

`exact` means that the published label maps directly to the current ScaleSync
catalog concept. `aggregate` means that a household survey label is broader than
registry-level variants. The generic Scottish Fold and Siamese VBO concepts are
retained as targets, while the limitation explains the unresolved internal
composition. `split` would be used only when a source provides separable variant
counts; `unresolved` means no defensible catalog target. Neither occurs in the
five published rows.

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

The >=50-breed grading criterion remains unmet. Promotion beyond the top five
requires a broad Russian source with comparable units, reproducible totals, and
a leading group that does not contradict an independent source.
