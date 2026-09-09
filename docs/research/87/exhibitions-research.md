# Russian all-breed exhibition evidence beyond the population top five

Accessed 2026-09-09. This is a separate exhibition-entry context. It must not be
merged with household-population, user-profile, registration, win, or points
series.

## Included event

The Tyumen Regional Association of Animal Lovers / Felinological Centre
“Fashionable Cats” published the official event page and downloadable catalogue
for the WCF international show “Our Favorites”, Tyumen, 5–6 October 2024:

- event page: https://www.toolj.ru/vistovka_wcf_5_6_okt_24.html
- catalogue: https://www.toolj.ru/1_katalog_5_6_okt_wcf.pdf
- WCF licence printed in the catalogue: `242037`
- downloaded PDF SHA-256: `da3e427e9ebd5c27c3a24907e4e587e8344cefaf9d5100befff92fa26f96fe22`

The PDF has a text layer and 43 pages. Its numbered catalogue runs continuously
from 1 through 102. Parsing the EMS code on every line containing `Class
(класс)` yields 102 catalogue entries across 26 source codes. `HHS` is a
household-pet class and `XLH` is the catalogue's unrecognized-longhair category;
excluding those two gives 100 recognized-pedigree entries across 24 codes.

The leading counts are Maine Coon 16; Bengal and Kurilian Bobtail Longhair 14
each; Burmese 8; British Shorthair and Siberian 6 each. The useful result beyond
the already-known Russian population top five is broad: 20 of the 26 source
codes are outside those five labels, and 18 remain after excluding HHS and XLH.
Notable non-top-five entry counts are Bengal 14, Kurilian Bobtail Longhair 14,
Burmese 8, Exotic Shorthair 4, Kurilian Bobtail Shorthair 4, Russian Blue 4,
Scottish Straight 4, Cornish Rex 3, and Oriental Shorthair 3.

`exhibitions-tyumen-2024.csv` contains the complete normalized series. Ranking
uses competition ranking by `catalog_entry_count`: equal counts share a rank and
the following rank is skipped. Source EMS varieties remain separate; no
post-hoc aggregation is used to improve rank. The denominator is 102 catalogue
entries, not 102 unique cats: two records are litter-class entries, and the
public catalogue has no TopCat-style stable animal identifier. Consequently no
cross-event deduplication or claim about unique animals is made.

## Reproduction

```bash
curl -fL https://www.toolj.ru/1_katalog_5_6_okt_wcf.pdf -o /tmp/tyumen.pdf
sha256sum /tmp/tyumen.pdf
pdftotext -layout /tmp/tyumen.pdf /tmp/tyumen.txt
python3 docs/research/87/parse_exhibition_catalog.py /tmp/tyumen.txt
python3 docs/research/87/verify_exhibitions.py
python3 docs/research/87/verify_exhibitions_test.py
```

## TopCat and other-system audit

TopCat publicly exposes event pages and, for some completed events, a `/catalog`
page containing numbered entries and EMS codes. Examples include event 5033
(Nizhny Novgorod, 21–22 September 2024) and indexed catalogue 3511. Direct
unauthenticated retrieval returned HTTP 403 on 2026-09-09, including through the
in-app browser, while the search index exposed only partial snippets. The
TopCat organizer documentation states that accepted applications are included
in the catalogue and that managers can save it as RTF or print it; it does not
provide a public bulk endpoint. These pages therefore establish availability
and structure but were not converted into an incomplete count series:

- https://ru.top-cat.org/cat-shows/5033
- https://ru.top-cat.org/cat-shows/3511/catalog
- https://ru.top-cat.org/help/cat-show-manager-help

MFA licence pages document catalogue requirements but do not publish breed-level
catalogue rows. Example: https://www.cats-club.ru/Licences_2024/1800.htm.
Additional IFC Felis PDF catalogue URLs were discoverable, but their host failed
DNS resolution during direct reproducibility retrieval and were not counted:

- https://ifc-felis.ru/files/All_Catalog.pdf
- https://ifc-felis.ru/files/Catalog%26DopSpis.pdf

## Limitations and product use

This single regional all-breed show is a convenience sample shaped by geography,
club affiliation, travel cost, show eligibility, and the event programme. A
catalogue entry is neither a birth/registration nor a household. Litter entries
do not equal one animal. The data can support an explicitly labelled
“represented at this exhibition” ordering or a coverage-prioritization signal;
it cannot extend the nationwide population ranking. A multi-event Russian
series from comparable full catalogues is still needed before treating this as
a robust exhibition popularity rank.
