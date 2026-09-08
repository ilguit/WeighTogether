# Audited cat-breed adult ranges for issue #107

Retrieved 2026-09-08 from immutable Wikipedia revisions. Wikipedia is used by
explicit owner decision because stronger sources did not provide a complete,
sex-specific numerical range for every supported breed. These values are adult
typical ranges, not clinical limits and not observed kitten growth curves.

| Breed | Female | Male | Revision |
|---|---:|---:|---|
| British Shorthair | 3–4 kg | 5–8 kg | frwiki `British Shorthair`, oldid 38446796 |
| Scottish Fold | 2.7–4 kg | 4–6 kg | enwiki `Scottish Fold`, oldid 1369070582 |
| Siamese | 3–4 kg | 4–5 kg | dewiki `Siamkatze`, oldid 269975201 |
| Maine Coon | 12–15 lb = 5.44310844–6.80388555 kg | 18–22 lb = 8.16466266–9.97903214 kg | enwiki `Maine Coon`, oldid 1372828795 |
| Siberian | 3–6 kg | 4.5–8 kg | dewiki `Sibirische Katze`, oldid 269975535 |

Pound conversion is exact: `kg = lb × 0.45359237`. The HTML retrieved with
`oldid` and `printable=yes` has SHA-256 values recorded in the snapshot source
entries. The content is available under CC BY-SA 4.0.

## Model contract

For each sex, the generator divides every fitted domestic-shorthair population
P50 value by its final (546-day) P50. It multiplies that dimensionless shape by
the lower bound, midpoint, and upper bound above, then appends the exact adult
range at 730 days. The resulting band is explicitly `MODELLED_BREED_ADULT_RANGE`;
it is not a breed percentile estimate.

Maine Coon and Siberian retain Mugnier et al. (2023) mean ± one-SD birth
observations as empirical day-0 points with their own per-point source. The
resolver returns no modeled value for days 1–55, avoiding an unsupported bridge
between birth and the population-derived shape beginning at day 56.
