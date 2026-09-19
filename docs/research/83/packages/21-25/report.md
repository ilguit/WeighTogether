# Issue 83 research package: ranks 21–25

Snapshot researched on 2026-09-19. This package keeps event ranking, catalog mapping, adult evidence, and the absence of acceptable 0–24-month evidence separate so later integration cannot silently convert an adult standard into a puppy curve.

## Reproducible ranking

The ranking unit is **one breed/variety's registrations summed across the two official Eurasia 2024 tables**. It is not a unique-dog count: the same dog may be registered on both days.

| Rank | Catalog mapping | 16 Nov | 17 Nov | Sum |
|---:|---|---:|---:|---:|
| 21 | `VBO:0200724` Jack Russell Terrier | 51 | 49 | 100 |
| 22 | `VBO:0200898` Miniature Schnauzer, Pepper And Salt | 48 | 51 | 99 |
| 23 | `VBO:0200420` Dachshund, Standard Smooth-Haired | 48 | 47 | 95 |
| 24 | `VBO:0200193` Border Collie | 50 | 44 | 94 |
| 25 | `VBO:0201089` Pug | 42 | 50 | 92 |

The per-day male/female arithmetic and exact event labels are retained in `registrations_by_day.csv`. Ranks follow descending sums supplied by the complete official two-day baseline; this package does not claim that five rows alone can validate boundary ranks 20 or 26.

## Adult evidence

- **Jack Russell Terrier:** FCI 345 specifies ideal height 25–30 cm and the equivalence 1 kg per 5 cm height. The exact result is 5.0–6.0 kg. This is an ideal standard, not a population percentile.
- **Miniature Schnauzer, Pepper And Salt:** FCI 183 gives 4–8 kg and explicitly lists pepper and salt among the four colour varieties. The weight interval is common to the standard, not colour-specific empirical evidence.
- **Standard Smooth-haired Dachshund:** FCI 148 recognizes the exact size/coat variety but defines size by chest circumference at a minimum age of 15 months and supplies no weight. Following the required fallback, Wikipedia's standard-size 16–32 lb becomes 7.3–14.5 kg after unit conversion. The fallback is size-specific but not coat-specific.
- **Border Collie:** FCI 297 supplies no weight. The Italian Wikipedia fallback gives males 14–20 kg and females 12–19 kg; `breeds.csv` stores their combined 12–20 kg envelope and this report preserves the sex-specific values.
- **Pug:** FCI 253 gives an ideal 6.3–8.1 kg for the breed and lists four colour varieties. This is an ideal standard, not an empirical percentile.

## Ages 0–24 months

No quantitative breed/variety-specific longitudinal table meeting the evidence rules was found for any of the five mappings. Consequently this package contains **no inferred or interpolated puppy weights**. `growth_gaps.csv` records one explicit 0–24-month gap for every mapping.

Salt et al.'s peer-reviewed charts are strong evidence for general size-category monitoring but do not justify relabelling a generic curve as a breed/variety curve. The Dog Aging Project summary and the Swedish young-adult screening study are cross-sectional rather than breed-specific longitudinal puppy references. A Royal Canin Jack Russell chart covers only 0–2 months and is a manufacturer visual; its plotted values were not digitised and the remaining ages were not extrapolated.

## Files and integration constraints

- `breeds.csv`: one row per rank, exact catalog ID/name, registration sum, adult bounds, method, applicability, and growth status.
- `registrations_by_day.csv`: reproducible official day totals including male/female subtotals.
- `growth_gaps.csv`: explicit age coverage and reason that no numeric trajectory is present.
- `sources.csv`: provenance and source-specific limitations.
- `verify.py`: structural, arithmetic, rank-order, mapping, and gap checks.

Do not treat an ideal breed-standard range as a healthy-population reference interval. Do not apply the Miniature Schnauzer adult interval to non-miniature Schnauzers, the Dachshund fallback to miniature/rabbit sizes, or a generic Dachshund value to a puppy curve. Do not synthesize missing ages from endpoints.
