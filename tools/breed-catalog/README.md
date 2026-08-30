# Breed catalog generator

This tool creates the bundled cat and dog breed snapshot without network access. Downloading is deliberately separate: generation accepts only a local VBO OBO file and rejects it unless its release version and SHA-256 match the expected values.

The tracked snapshot uses the immutable Vertebrate Breed Ontology release `2026-04-15`:

- source: `https://purl.obolibrary.org/obo/vbo/releases/2026-04-15/vbo.obo`
- SHA-256: `4ada4d18dcc2ea421f1ee630dfee29042ae2a45a34b0928419475377ee3304bd`
- license: [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)
- snapshot date: `2026-08-30`

Generate from a previously downloaded source, from the repository root:

```shell
./gradlew -p tools/breed-catalog run --args='\
  --source /path/to/vbo-2026-04-15.obo \
  --source-version 2026-04-15 \
  --source-url https://purl.obolibrary.org/obo/vbo/releases/2026-04-15/vbo.obo \
  --source-sha256 4ada4d18dcc2ea421f1ee630dfee29042ae2a45a34b0928419475377ee3304bd \
  --snapshot-date 2026-08-30 \
  --overrides russian-overrides.tsv \
  --output ../../app/src/main/res/raw/breed_catalog.json'
```

## Selection and output rules

The parser consumes `id`, `name`, `is_a`, `is_obsolete`, and `EXACT`/`RELATED` synonyms. It walks concrete breed concepts below the VBO `Cat breed` and `Dog breed` roots, excludes the roots themselves, obsolete terms, and non-canonical identifiers, and emits only canonical `VBO:NNNNNNN` identifiers. A concrete breed remains in the catalog when it has varieties or national populations below it (for example, Chihuahua); hierarchy is not mistaken for an abstract-only marker. Aliases are suffix-cleaned, case-insensitively deduplicated, and sorted. Russian overrides are explicit; every other Russian display label falls back to the cleaned canonical label.

Each species also gets application-owned stable `mixed` and `unknown` records. Output records are ordered by species and ID. `catalogSha256` is the SHA-256 of the UTF-8 bytes of the exact JSON array stored in `breeds`, from its opening `[` through its closing `]`. Given the same inputs and arguments, output is byte-for-byte identical.
