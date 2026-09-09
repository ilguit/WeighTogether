# Issue #90 design and evidence package

This directory is the self-contained, approved design package for applying cat-breed weight references in ScaleSync. It contains the product/UX contract, the complete claim-level evidence inventory, preserved research inputs, and reproducible validation tooling.

## Approved scope

- 48 researched VBO concepts represent 47 semantic candidates because deprecated `VBO:0100061` Canadian Sphynx canonicalizes to `VBO:0100230` Sphynx.
- Production adds 26 canonical profiles to the five already implemented profiles, so the breed picker contains exactly 31 supported breeds.
- The evidence total is 17 official + 9 professional fallback profiles.
- Batch 1 contains the 17 official profiles plus Russian Blue (professional fallback), for 18 profiles. Batch 2 contains the remaining 8 fallback profiles.
- Munchkin (`0100169`), Munchkin Longhair (`0100170`), and Munchkin Short-Haired (`0100303`) remain distinct IDs with distinct Russian display names.
- Published breed maturity age is used when available; otherwise the approved model fallback is day 730.
- Birth and intermediate-age observations remain research-only in this implementation cycle.

The authoritative product, UI, accessibility, state, and acceptance contract is in [design-specification.md](design-specification.md).

## Artifact contract

- `design-specification.md` — approved product and implementation-facing design specification.
- `weights.csv` — canonical numeric evidence rows and explicit gap rows; `sourceId` provides claim-level provenance.
- `breed-coverage.csv` — one row for each of the 48 raw VBO concepts and its evidence-readiness classification. Production canonicalization is a separate, explicit rule.
- `sources.csv` — complete namespaced source inventory, including URL, authority class, location, access date, method/sample/geography, claims used, and limitations.
- `conflicts-gaps.csv` — gaps, conflicts, provisional evidence, and the action required before excluded evidence can become production-ready.
- `comprehensive-report.md` — human-readable synthesis of evidence and approved production interpretation.
- `upstream/` — the six raw CSV inputs from the three independent research packages required to reproduce the integrated CSVs and report.
- `build.py` — deterministic integration script; regenerates the four integrated CSV/report artifacts from `upstream/`.
- `verify.py` — structural, numeric, provenance, canonical-scope, and naming validation.
- `test_integrated.py` — regression tests for evidence invariants and approved production scope.

`build.py` overwrites `weights.csv`, `breed-coverage.csv`, `sources.csv`, `conflicts-gaps.csv`, and `comprehensive-report.md`. Do not edit generated artifacts without updating the builder and raw inputs as applicable.

## Reproduce and verify

Run from this directory:

```sh
python3 build.py
python3 verify.py
python3 -m unittest -v test_integrated.py
```

The package uses only the Python standard library and does not require network access.

## Approval trail

- [Issue #90](https://github.com/ilguit/XiaomiScaleSync/issues/90)
- [Authoritative implementation addendum](https://github.com/ilguit/XiaomiScaleSync/issues/90#issuecomment-5600424292)
- [Owner approval](https://github.com/ilguit/XiaomiScaleSync/issues/90#issuecomment-5600506557)

Earlier research inputs remain provenance evidence, while these two comments resolve the final production batching, canonicalization, picker boundary, maturity fallback, Munchkin naming, and research-only treatment of birth/intermediate observations.
