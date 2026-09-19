# Issue #83: dog-breed weight evidence, ranks 11–30

This directory integrates four independently researched evidence packages for the
20 Eurasia 2024 registration rows ranked 11 through 30. The ranking metric is the
sum of entries in two all-breed show days. It is an event-popularity proxy, not a
unique-dog count, national ownership estimate, or annual pedigree-registration
total.

## Machine-readable contract

`manifest.csv` is the unified index. Each row identifies one exact breed or
variety, its rank/count, package directory, and the package files containing
mapping/registration, adult, age, gap, and source evidence. A blank `age_file`
means the package has no qualifying age-observation table; the corresponding
`gaps_file` remains mandatory. Package schemas are intentionally preserved
instead of flattening unlike value semantics into lossy min/max columns.

Follow a manifest row into its referenced files to retain:

- provenance and source type/priority (`sources_file`, `adult_source_priority`);
- adult value status and applicability (`adult_file`), including ideal points,
  minima, approximate ranges, non-numeric standards, and secondary fallbacks;
- sex, exact age/day interval, units, sample size, statistic, method, and
  limitations for observations (`age_file`);
- explicit missing windows, search scope, and non-inference decisions
  (`gaps_file`, `age_coverage`).

`validate.py` enforces the cross-package contract; `test_validate.py` contains
regression tests for coverage and deliberate breed-variety distinctions. Run:

```bash
python3 docs/research/83/validate.py
python3 docs/research/83/test_validate.py
```

## Interpretation and limitations

Official breed standards have first priority. A secondary fallback is used only
where recorded, and is never silently upgraded to an official healthy-weight
interval. Standards describe conformation, not clinical percentiles. Sparse
birth/neonatal observations are not growth curves; no chart was digitised and no
missing month was interpolated or borrowed from a size class or related breed.

Miniature Schnauzer colour varieties at ranks 22, 27, and 29 are separate event
rows and catalog/mapping concepts and must not be deduplicated. Rank 23 is the
standard smooth-haired Dachshund variety. Rank 28 maps “Akita” to Japanese FCI
Akita, but its secondary adult range has explicit Japanese/American ambiguity.
Ranks 26–30 map to the exact catalog concepts already shipped by the project,
including separate VBO concepts for both Miniature Schnauzer colour varieties
and the Japanese Akita concept rather than the ontology's ambiguous Akita entry.

These files are research inputs, not veterinary targets. Individual body
condition, health, neuter status, and clinician judgement remain outside scope.
