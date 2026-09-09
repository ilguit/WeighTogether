# Weight-reference snapshot generator

`weight_references.source.json` is the reviewed canonical snapshot, including
derived points and the complete filtering/binning rules. The bundled curves are
deterministic **empirical fallback references**, not the fitted GAMLSS standards
published in the cited papers. The raw data do not contain the fitted models.

Run `./gradlew -p tools/weight-references verifySnapshot` to prove that the
bundled resource is derived from the canonical source/config and the pinned
`docs/research/97/bccg-curves.csv`. The fitted cat profiles copy only P9/P50/P91;
P2/P98 and model parameters remain in the research package. This is offline
and does not require the 140 MB compressed / 1.15 GB expanded dog artifact.

To independently rebuild the points from pinned raw artifacts, download the
URLs recorded in the manifest, verify their recorded SHA-256 checksums, then run:

```
./gradlew -p tools/weight-references run --args="--derive weight_references.source.json /path/Final_Data_PLOS.zip /path/KGC1_dataset.csv /tmp/weight_references.json"
```

The dog input is streamed directly from ZIP. Only sex × adult-weight-category
profiles are produced; raw `BREED_ID` values are intentionally not presented as
VBO breed mappings. Eligibility, category boundaries, weekly binning, minimum
bin sizes, quantile definition, rounding, and limitations are embedded in the
snapshot under `manifest.derivation`.

## Cat breed evidence staged for production

`cat_breed_evidence.csv` is the validated implementation input derived from the
approved `docs/design/90` evidence package. It contains exactly 26 canonical
breeds and one female plus one male adult range for each breed. Batch 1 contains
17 profiles backed by feline-organization sources plus Russian Blue with a
professional fallback; Batch 2 contains eight professional fallbacks.

The adult center is always the arithmetic midpoint of the published typical
range, not an observed population median. `maturityDerivation=published` keeps a
published breed maturity age; otherwise `model_fallback` must be exactly day 730.
The maturity day is the final growth point; its adult range carries forward for
all later ages instead of ending reference availability at maturity.
Each row carries claim-level source class, URL, claim, and limitations. Birth and
intermediate observations are intentionally absent because they remain
research-only for issue #90. `VBO:0100230` is the sole Sphynx record and declares
deprecated alias `VBO:0100061`.

`verifySnapshot` validates this input while continuing to compare the existing
generated snapshot byte-for-byte. To run only the contract check:

```sh
./gradlew -p tools/weight-references validateCatBreedEvidence
```
