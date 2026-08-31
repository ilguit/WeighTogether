# Weight-reference snapshot generator

`weight_references.source.json` is the reviewed, canonical input. The generator
normalizes JSON and calculates the checksum of the numerical `profiles` array.
It deliberately does not estimate centiles from raw observations: neither
Liverpool dataset includes the fitted GAMLSS models or complete fitting choices.

Run `./gradlew -p tools/weight-references verifySnapshot` to prove that the
bundled resource is derived from this input. Upstream artifact URLs and SHA-256
digests are recorded in the manifest and `THIRD_PARTY_NOTICES.md`.
