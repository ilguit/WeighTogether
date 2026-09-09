# Russian cat-breed evidence beyond the top five (issue #87)

Snapshot date: 2026-09-09. The comprehensive conclusion and application
coverage recommendation are in [`final-analysis.md`](final-analysis.md); source
qualification is in [`source-audit.md`](source-audit.md).

## Result

The original nationwide population top five and Yandex ID cross-check remain
unchanged in `population-ranking.csv` and `yandex-id-ranking.csv`. The expanded
research adds evidence for breeds outside that baseline without pretending the
unlike metrics form one population ranking:

- FARUS: 1,920 cattery-breed associations, 53 mapped VBO concepts;
- Felis Russica: 224 associations, 23 mapped concepts;
- one WCF Tyumen event: 102 catalogue entries across 26 codes, of which 100
  entries and 24 codes are recognized-pedigree;
- 20 ordered rows from three consumer/veterinary proxy series.

The reproducible `evidence-priority.csv` covers 49 exact VBO concepts outside
the five conservatively excluded baseline families: tier A 9 (at least three
source series), tier B 14 (two series), and tier C 26 (one series). It is an
application coverage queue, not a population-popularity ranking. Counts from
catteries, exhibition entries, demand, veterinary records and owner surveys
are never added or converted into common shares.

## Artifacts

- `registries-cattery-breed.csv` and `registries-breed-counts.csv`: FARUS and
  Felis Russica supply-side series; see `registries-report.md`.
- `exhibitions-tyumen-2024.csv`: one complete WCF event-entry series; see
  `exhibitions-research.md`.
- `consumer-proxy-ranking.csv` and `consumer-source-inventory.csv`: ordered
  demand, veterinary and owner-survey proxies plus investigated dead ends; see
  `consumer-source-audit.md`.
- `evidence-priority.csv`: derived cross-source coverage priority. It stores
  each source rank in a separate column and never stores a synthetic popularity
  rank. `build_evidence_priority.py` reproduces it.
- `quantitative-contexts.csv` and `excluded-series.csv`: contextual numbers and
  sources that cannot provide a comparable breed order.
- `CHECKSUMS.sha256`: integrity manifest for all ten checked CSV artifacts.

## Mapping and ordering

Only `exact` single-concept mappings enter `evidence-priority.csv`. Household
labels and registry aggregates do not inherit child VBO IDs. In particular,
WCF `BUR` remains unresolved because it does not defensibly choose one of the
catalog's Burmese concepts; broad Scottish, Sphynx and Don Sphynx labels remain
unresolved; recognized varieties stay separate.

Priority is ordered by evidence tier, descending number of independent source
series, median normalized rank among the series where the concept is present,
then canonical name. Missing from a short source list is treated as unknown,
not as a last place.

The requested 1–10 and 11–30 boundaries may be used only as coverage batches.
The 31–50 batch is incomplete because exact evidence ends at priority 49. None
of these ranges may be presented as national-population ranks.

## Verification

Run from the repository root:

```bash
python3 docs/research/87/verify_exhibitions.py
python3 docs/research/87/verify_exhibitions_test.py
python3 docs/research/87/registries_verify.py
python3 docs/research/87/registries_extract_test.py
python3 docs/research/87/consumer-verify.py
python3 docs/research/87/verify_dataset.py
python3 docs/research/87/verify_dataset_test.py
sha256sum -c docs/research/87/CHECKSUMS.sha256
```
