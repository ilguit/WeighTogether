# Issue 83 research package: ranks 26–30

## Result

The official two-day Eurasia 2024 registration baseline yields this reproducible order. Each row maps to the exact VBO concept shipped in the project catalog; Japanese Akita uses `VBO:0200734` rather than the ontology's ambiguous generic Akita entry:

| Rank | Entry | Day 1 | Day 2 | Total |
|---:|---|---:|---:|---:|
| 26 | Samoyed | 47 | 45 | 92 |
| 27 | Miniature Schnauzer — pure black | 45 | 46 | 91 |
| 28 | Akita | 45 | 44 | 89 |
| 29 | Miniature Schnauzer — black and silver | 44 | 44 | 88 |
| 30 | Miniature American Shepherd | 42 | 43 | 85 |

The calculation is `day1_total + day2_total`; source pages and the literal operands are retained in `registrations.csv`. The RKF tables name Schnauzer colour varieties separately, so they remain separate entries rather than being collapsed to breed level.

## Adult reference decisions

- FCI 183 directly supplies an approximate 4–8 kg range for dogs and bitches and lists pure black and black/silver among the four varieties. The same official range applies to both requested colour entries.
- FCI 212 and FCI 255 supply height but no weight. Following the fallback rule, the adult weights are copied from Wikipedia without conversion or narrowing.
- “Akita” is mapped to FCI 255 (Japanese Akita), not FCI American Akita. Wikipedia's 27–59 kg male and 25–45 kg female values are unusually broad, while the article itself explains international Japanese/American naming ambiguity. The values are therefore preserved as `published_secondary_ambiguous`, not promoted to an official or precise ideal.
- FCI 367 explicitly says healthy weight varies with individual size, sex, and substance. Wikipedia has no numeric weight. The Miniature American Shepherd adult row remains numeric-null; no common web range is substituted.

## Age reference decision

`age_weight.csv` intentionally contains only its header. Searches prioritized peer-reviewed/open research. Salt et al. provide 12-week-to-2-year size-category charts; the Miniature Schnauzer study reports a breed growth figure; Leclerc et al. place Samoyed and Akita in a 20-breed cluster. None supplies an exact, directly applicable monthly 0–24-month table for every requested breed and Schnauzer colour variety. Plot digitisation, category-to-breed relabelling, and interpolation would create unsupported values, so each missing series is explicit in `gaps.csv`.

## Adult height at the withers

`adult_height.csv` transcribes the official FCI clauses and keeps their
semantics. Samoyed is 57 ±3 cm for males and 53 ±3 cm for females (FCI 212,
PDF page 5); the stored endpoints are the exact tolerance expansion. FCI 183
gives 30–35 cm for dogs and bitches (page 6), applicable to both requested
Miniature Schnauzer colour varieties. Japanese Akita is 67 ±3 cm for dogs and
61 ±3 cm for bitches (FCI 255, page 5); applicability excludes American Akita.
Miniature American Shepherd is 35.5–46 cm for males and 33–43.5 cm for females
(FCI 367, page 8), with the standard's caveat that its minima do not apply to
dogs under six months. These are conformation values, not empirical adult
population intervals.

## Files and semantics

- `breeds.csv`: stable rank-to-entry mapping and variety scope.
- `registrations.csv`: both official day totals, sum, and provenance.
- `adult_weight.csv`: sourced adult values, sex/applicability, status, and caveats.
- `adult_height.csv`: sex/applicability-aware official withers-height values, units, semantics, pages, and limitations.
- `age_weight.csv`: exact sourced age observations only (none qualified).
- `gaps.csv`: actionable absence and ambiguity records.
- `sources.csv`: source tier, URL, use, access date, and notes.
- `validate.py`: structural, arithmetic, coverage, and provenance checks.

All weights are kilograms. Blank numeric cells mean “not published/qualified,” never zero. This is a research package, not a veterinary target: individual body condition and clinical advice remain outside its scope.
