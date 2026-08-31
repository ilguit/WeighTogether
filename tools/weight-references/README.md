# Weight-reference snapshot generator

`weight_references.source.json` is the reviewed canonical snapshot, including
derived points and the complete filtering/binning rules. The bundled curves are
deterministic **empirical fallback references**, not the fitted GAMLSS standards
published in the cited papers. The raw data do not contain the fitted models.

Run `./gradlew -p tools/weight-references verifySnapshot` to prove that the
bundled resource is derived from the canonical source/config. This is offline
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
