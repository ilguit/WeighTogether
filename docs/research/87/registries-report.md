# Russian federation registry follow-up (issue #87)

Snapshot/access date: **2026-09-09**. This report deliberately keeps the
registry metric separate from household prevalence, pet profiles, animal
registrations, litters, and show entries.

## Usable result

Two official Russian federation webpages expose sufficiently broad breed
information to quantify a **supply-side cattery-breed association** metric:

| Source | Eligible/listed catteries represented | Cattery-breed rows | mapped VBO concepts | Notes |
|---|---:|---:|---:|---|
| FARUS public cattery register | 990 of 1,006 eligible cards | 1,920 | 53 | 33 current club/category pages; explicit foreign addresses, expired registrations and cards marked excluded/annulled/deleted removed; five associations unresolved |
| Felis Russica (FIFe member) cattery page | 195 | 224 | 25 | Hand-maintained Russian list; 22 aggregate mappings and one unresolved label; per-cattery activity dates absent |

The denominator in the detailed CSV is the number of eligible source cards for
FARUS (1,006), including 16 cards from which no recognized breed code could be
reliably extracted, and the number of Felis Russica catteries with a parsed
breed line (195). A cattery listing multiple breeds contributes one record to
each breed. These are **not counts of cats, kittens, registrations or litters**.

The normalized details are in `registries-cattery-breed.csv`; the independently
ranked per-source totals are in `registries-breed-counts.csv`. Ties use standard
competition ranking. The source page's ordering is not used as a rank.

### FARUS breadth and findings outside the known household top five

FARUS yields 53 mapped VBO concepts, with 30 concepts having at least ten
cattery-breed records. The leading counts are Maine Coon 204, Scottish Fold
182, Scottish Straight 174, Burmese 141, British Shorthair 140, Oriental
Shorthair 128, Abyssinian 99, Bengal 86, Scottish Fold Longhair 81, Scottish
Straight Longhair 72, Siamese 58, British Longhair 50, Maine Coon Polydactyl 44,
Sphynx 44, and Devon Rex 42.

When the household top-five families (British Shorthair, Scottish Fold,
Siamese, Maine Coon, Siberian) are treated conservatively as families rather
than only exact catalog labels, the strongest genuinely additional signals are
**Burmese (141), Oriental Shorthair (128), Abyssinian (99), Bengal (86), Sphynx
(44), Devon Rex (42), Munchkin Shorthair (36), Elf (33), Ragdoll (31), Selkirk
Rex Shorthair (31), Kurilian Bobtail Shorthair (24), Bambino (22), Kurilian
Bobtail Longhair (21), Donskoy (18), Persian (16), Exotic Shorthair (16), Dwelf
(15), Cornish Rex (14), Somali (12), Chausie (11), and Thai (10)**.

This is useful evidence for ordering research coverage outside the top five,
but it is not a population-popularity ranking: federation membership,
multi-system registration, breeder specialization, five-year FARUS cattery
terms, and organization-specific breed recognition all create selection bias.

### Felis Russica corroboration

Felis Russica is narrower but independently supports several outside-top-five
signals: Burmese 22, Oriental Shorthair 16, Ragdoll 11, Abyssinian 8, Devon Rex
6, Sacred Birman 5, Norwegian Forest Cat 4, Persian 4, Sphynx 4, Kurilian
Bobtail 3, Neva Masquerade 3, Bengal 2, Cornish Rex 2, Ocicat 2, Russian Blue 2,
and Thai 2. It is not directly comparable to FARUS: the webpage has no uniform
activity/expiry date and uses free-text breed labels. Counts remain a separate
series and are never summed with FARUS.

## Mapping rules

- FARUS compact breed codes are mapped one code at a time to the current
  ScaleSync catalog. `BRL` is FARUS British Longhair, `STB` is Skif Toy Bob
  (catalog `Toybob`), and `SYS` is Seychellois Shorthair. Equivalent source
  aliases (for example `OSH`/`ORI`, `BBN`/`BAM`) retain their original label in
  detail rows and converge only in the per-VBO summary.
- Five FARUS associations (`BBS`, `SBT`, `SPL`, `NIB`) remain `unresolved`.
  They are retained in detail and excluded from mapped summaries.
- Felis Russica exact breed names map directly. Broad labels such as
  `британская`, `экзотическая`, `курильский бобтейл`, `восточные`, and `персы и
  экзоты` are marked `aggregate`; a group is not silently assigned to one
  subtype. One `сиамы` label remains unresolved.
- Every mapped VBO identifier is checked against
  `core/src/main/resources/breed_catalog.json`.

## Source audit

### Included

1. **FARUS cattery register** — <https://xn--80a6adhc.xn--p1ai/catteries/>.
   The index exposes 33 category links, and each category's HTML contains stable
   cattery card URLs, compact breed codes and usually a registration expiry.
   No login or private endpoint is used. The site says catteries are entered in
   its stud book and normally removed after the five-year registration term.
2. **Felis Russica cattery list** —
   <https://felis-russica.com/pitomniki-felis-russica.html>. The official page
   explicitly describes these as catteries registered in Russia and publishes
   cattery names and free-text breeds. Entries visibly marked removed from the
   FIFe database are excluded.

### Investigated but not promoted to a Russian breed-count series

- **FIFe breeding statistics 2024** —
  <https://fifeweb.org/wp-content/uploads/2025/06/FIFe-breeding-statistics-2024.pdf>.
  This is an excellent 2024 registration table covering more than 40 breeds,
  but it aggregates all FIFe national members. Felis Russica is identified as
  the Russian member, yet no member-by-breed slice is published, so the global
  table cannot represent Russia.
- **FIFe show statistics 2024** —
  <https://fifeweb.org/wp-content/uploads/2025/06/FIFe-show-statistics-2024.pdf>.
  Also broad, but global and measured in show entries, not registrations,
  catteries, or population.
- **Felis Russica site and news** — <https://felis-russica.com/>. The site says
  it maintains a unified pedigree database and repeats FIFe-wide approximate
  totals, while its news notes the requirement that countries submit annual
  kitten totals by breed. No public Russian member-by-breed annual table, API,
  PDF, or download was found.
- **MFA cattery-name register** —
  <https://www.cats-club.ru/cattery_names.htm>. It is broad and public but lists
  names alphabetically without a breed field; the breeding rules and conference
  reports publish recognized-breed lists and aggregate new-cattery totals, not
  a breed distribution. It therefore cannot produce comparable breed counts.
- **WCF**: the international cattery-name lookup is useful for validating names
  but does not provide a reproducible public Russia-by-breed export. Russian
  club pages and commercial directories mix organizations, stale advertising,
  and self-submission; they were not substituted for a federation dataset.
- **WCA / World Cat Congress** publishes cross-organization breed-recognition
  comparisons, not Russian registrations or cattery counts.

No authentication wall, CAPTCHA, or private API was bypassed. No public
breed-resolved Russian annual kitten/litter/animal registration series was
found in these systems. The new evidence is therefore explicitly limited to
cattery supply.

## Reproduction and checks

Online extraction (standard-library Python only):

```bash
python3 docs/research/87/registries_extract.py
python3 docs/research/87/registries_verify.py
```

For deterministic inspection of previously downloaded HTML, supply
`--cache-dir`; expected names are documented in the extractor module. The
verifier checks exact schemas, unique source/cattery/breed keys, mapping
vocabulary, all VBO IDs against the application catalog, summary counts, rank
and tie behavior, and minimum breadth. Because both source pages are mutable,
rerunning online later is a new snapshot and may legitimately change counts.
