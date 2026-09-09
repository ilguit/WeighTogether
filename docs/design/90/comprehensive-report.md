# Integrated weight evidence for issue #90

Research integration date: 2026-09-09. Scope: the 48 VBO concepts outside the five already implemented breeds. The package is self-contained under `docs/design/90/`; raw inputs used by this script are in `upstream/`.

## Result

There are **36** raw VBO concepts with female and male adult ranges, **11** with only points, means, bounds, or combined-sex evidence, and **1** with no numeric adult evidence. Raw readiness is **18 official + 9 professional fallback**, but `0100061` Canadian Sphynx canonicalizes to `0100230` Sphynx. The approved production evidence total is therefore **17 official + 9 professional fallback = 26 new profiles**, or **31 total** with the five already implemented. The 48 raw VBO concepts represent **47 semantic candidates**.

Numeric birth evidence exists for **14** concepts: **11** exact/tabulated published records and **3** approximate graph-digitized records. Only **2** concepts have exact/tabulated intermediate-age points. **9** concepts have official graphical neonatal evidence somewhere in birth/intermediate coverage, but graph-only or digitized values remain research evidence, not production values.

## Canonical interpretation rules

- `lowerKg`, `medianKg`, and `upperKg` are used only for intervals. If a source publishes bounds but no median, the arithmetic midpoint is stored with `derivation=derived_midpoint`, `midpoint_derived`, or `midpoint`; it is never described as an observed median.
- Published single observations use `valueKg`. Published means use `meanKg`, with `sdKg` only when the source states an SD. A mean is never placed in `upperKg`.
- Pounds use exactly `1 lb = 0.45359237 kg`. The verifier checks nonnegative values, interval ordering, midpoint arithmetic, source references, exact VBO format, and mandatory adult/birth/intermediate coverage.
- Sex-combined, breed-group, coat-group, and proxy evidence remains explicit in `sex`, `pooledGroup`, `statistic`, and `notes`; it is not silently duplicated into an implementation-ready sex profile.
- Descriptive breed ranges are orientation data, not clinical healthy-weight limits. The existing top-five method may normalize the same-sex general kitten P50 shape and scale it to adult lower/midpoint/upper only for the 26 canonical ready profiles. The generated curve remains modelled, not breed-observed, and unsupported early ages remain unavailable. When a source gives no maturity age, the approved fallback adult knot is day 730.

## Staged implementation

1. **Batch 1 — 17 official + Russian Blue fallback (18):** Abyssinian, Balinese, Bengal, Burmese, Cornish Rex, Devon Rex, Munchkin, Munchkin Longhair, Norwegian Forest Cat, Oriental Longhair, Oriental Shorthair, Peterbald, Ragdoll, Sphynx, Thai, Toyger, Munchkin Short-Haired, Russian Blue.
2. **Batch 2 — remaining professional fallbacks (8):** American Shorthair, Bombay, Burmilla, Egyptian Mau, Neva Masquerade, Persian, Selkirk Rex Longhair, Turkish Angora. All fallback claims ship with explicit provenance/limitations and the same model disclaimer.
3. **Research-only:** birth means/ranges and the two isolated exact intermediate records. Do not connect birth to day 56 or synthesize longitudinal breed curves. Graphical evidence needs calibrated digitization plus independent verification or underlying tables.
4. **Excluded pending resolution (21):** Asian Leopard Cat, Bambino, Chausie, Donskoy, Dwelf, Elf, Exotic Shorthair, Kurilian Bobtail Longhair, Kurilian Bobtail Shorthair, Minuet, Minuet Longhair, Nebelung, Ocicat, Sacred Birman, Savannah, Selkirk Rex Shorthair, Somali, Toybob, Ukrainian Levkoy, Ural Rex, Seychellois Short Hair. Reasons include sex-combined evidence, point/mean only, missing bounds, proxies, low authority, source contradictions, or a taxonomy defect.

Ocicat is excluded despite two sex ranges because its TICA page contains contradictory ranges. Asian Leopard Cat (`Prionailurus bengalensis`) is a wild species and must not appear as a domestic-breed weight profile without a taxonomy/product decision. Savannah requires a filial-generation policy. Donskoy has no numeric adult range. Sacred Birman has identical sex points conflicting with prose. Kurilian males have only upper bounds. Experimental/proxy concepts remain provisional.

## Artifact contract

- `weights.csv`: canonical evidence rows plus explicit gaps. It is the only numeric table.
- `breed-coverage.csv`: exactly 48 raw VBO rows and evidence-readiness classification; production canonicalization is specified above and in `design-specification.md`.
- `sources.csv`: namespaced source inventory; package-local source IDs cannot collide.
- `conflicts-gaps.csv`: every gap, provisional record, and conflict that must stay excluded.
- `verify.py`: deterministic structural and semantic checks; `build.py` reproducibly integrates the preserved inputs under `upstream/`.

## Sources

The numbered list below preserves every source URL supplied by the three independent searches. Claim-level provenance is linked by `sourceId` in `weights.csv`; complete locations, samples, geography, claims, and limitations are in `sources.csv`.

Repeated URLs are retained as namespaced evidence records because the agents used them for different VBO concepts or scopes. `duplicateUrlGroup` identifies them explicitly; they are not independent corroboration. The repeated URLs are: https://static.royalcanin.nl/media/Corporate%20Affairs/vet-nurse-webinar/Kitten%20Neonatal%20Growth%20Curves%200-2_months.pdf, https://tica.org/breed/munchkin/, https://tica.org/breed/sphynx/.

1. [Bengal](https://tica.org/breed/bengal/) — `A-S01`, official_breed_organization; approximate ranges; no sample
2. [Abyssinian](https://tica.org/breed/abyssinian/) — `A-S02`, official_breed_organization; page also gives narrower narrative averages; used displayed range fields
3. [Oriental Shorthair](https://tica.org/breed/oriental-shorthair/) — `A-S03`, official_breed_organization; approximate ranges; no sample
4. [Devon Rex](https://tica.org/breed/devon-rex/) — `A-S04`, official_breed_organization; no sample
5. [Exotic Shorthair](https://vcahospitals.com/florida-veterinary-league/know-your-pet/cat-breeds/exotic-shorthair) — `A-S05`, veterinary_reference; no sex split or sample
6. [Average Healthy Cat Weight](https://www.petmd.com/cat/general-health/average-weight-cats) — `A-S06`, veterinary_reference; no sample/method exposed
7. [Cornish Rex](https://tica.org/breed/cornish-rex/) — `A-S07`, official_breed_organization; no sample
8. [Russian Blue: Pet-to-Human Weight Comparison](https://www.petobesityprevention.org/weight-comparison/russian-blue) — `A-S08`, veterinary_reference; explicitly a starting point; underlying sample not published
9. [Norwegian Forest](https://tica.org/breed/norwegian-forest/) — `A-S09`, official_breed_organization; no sample
10. [Burmese](https://www.gccfcats.org/getting-a-cat/choosing/cat-breeds/burmese/) — `A-S10`, official_breed_organization; care guidance; no sample
11. [Ragdoll](https://tica.org/breed/ragdoll/) — `A-S11`, official_breed_organization; no sample
12. [Kurilian Bobtail](https://tica.org/breed/kurilian-bobtail/) — `A-S12`, official_breed_organization; male lower bound absent; duplicated only because VBO separates coat lengths
13. [Sphynx](https://tica.org/breed/sphynx/) — `A-S13`, official_breed_organization; no sample
14. [Thai](https://tica.org/breed/thai/) — `A-S14`, official_breed_organization; no sample
15. [Association between Birth Weight and Mortality over the Two First Months after Birth in Feline Species: Definition of Breed-Specific Thresholds](https://pmc.ncbi.nlm.nih.gov/articles/PMC10251906/) — `A-S15`, peer_reviewed_observation; sex-combined; some sister breeds pooled; retrospective breeder data
16. [Birth weight in the feline species: Description and factors of variation in a large population of purebred kittens](https://wp-debug.envt.fr/wp-content/uploads/2023/03/ID_1_2022_Mugnier_Birth-weight-in-the-feline-species-Description-and-factors-of-variation-Theriogenology.pdf) — `A-S16`, peer_reviewed_observation; pooled Balinese/Mandarin/Oriental/Siamese; sex-combined
17. [Kitten growth from birth to two months of age: breed-specific curves](https://air.unimi.it/retrieve/dfa8b9a3-6f58-748b-e053-3a05fe0a3a96/rda.13449.pdf) — `A-S17`, peer_reviewed_conference_observation; abstract gives only extrema, not full per-breed table; sex pooled
18. [Elf Cat: behaviour price appearance](https://www.zooplus.co.uk/magazine/cat/cat-breeds/elf-cat) — `A-S18`, open_professional_reference; rare breed; no sample, sex split, or registry validation
19. [Kitten Neonatal Growth Curves 0-2 months](https://static.royalcanin.nl/media/Corporate%20Affairs/vet-nurse-webinar/Kitten%20Neonatal%20Growth%20Curves%200-2_months.pdf) — `B-royalcanin-neonatal-2024`, official_professional; Values are graph-digitized approximations; handbook does not expose underlying table/sample sizes on chart pages
20. [Birman](https://tica.org/breed/birman/) — `B-tica-birman`, official_feline_org; UI gives 12 lb for both sexes despite narrative stating females smaller
21. [Neva Masquerade](https://www.anicura.nl/over-huisdieren/kat/kattenrassen/neva-masquerade/) — `B-anicura-neva`, professional_veterinary; No sample or distribution; says maturity at 3 years
22. [Somali](https://www.petmd.com/cat/breeds/somali) — `B-petmd-somali`, professional_veterinary_reviewed; No sex split/sample
23. [Chausie](https://tica.org/breed/chausie/) — `B-tica-chausie`, official_feline_org; No range/sample; female value requires arithmetic derivation
24. [Savannah](https://www.petmd.com/cat/breeds/savannah) — `B-petmd-savannah`, professional_veterinary_reviewed; No sex split; range depends on filial generation
25. [Toybob adult size](https://www.toybobcat.com/) — `B-toybobcat`, breed_club_member; Not an independent official standard; no sex split/sample
26. [Toyger](https://tica.org/breed/toyger/) — `B-tica-toyger`, official_feline_org; No sample/distribution
27. [Munchkin](https://tica.org/breed/munchkin/) — `B-tica-munchkin`, official_feline_org; At-a-glance sex ranges differ from pooled narrative 5-9 lb
28. [Munchkin Longhair](https://tica.org/breed/munchkin-longhair/) — `B-tica-munchkin-longhair`, official_feline_org; Coat variant; no sample/distribution
29. [Selkirk Rex revision 1322594071](https://en.wikipedia.org/w/index.php?title=Selkirk_Rex&oldid=1322594071) — `B-wikipedia-selkirk`, wikipedia_fixed_revision_fallback; Fixed revision last edited 2025-11-17; female numeric range absent
30. [Bambino vs Dwelf comparison](https://dogell.com/en/compare-cat-breeds/bambino-vs-dwelf) — `B-dogell-bambino-dwelf`, open_secondary_low_authority; No methodology/sample; retain only as low-confidence provisional data
31. [Sphynx](https://tica.org/breed/sphynx/) — `B-tica-sphynx`, official_feline_org; No sample/distribution
32. [Donskoy](https://tica.org/breed/donskoy/) — `B-tica-donskoy`, official_feline_org; No numeric values
33. [Ocicat](https://tica.org/breed/ocicat/) — `B-tica-ocicat`, official_feline_org; Internal conflict: at-a-glance F 8-10/M 10-12 lb; narrative F 6-9/M 9-14 lb
34. [Ukrainian Levkoy breed profile](https://zoobonus.ua/en/breed/ukrainian-levkoy) — `B-zoobonus-levkoy`, open_secondary; No methodology/sample; provisional
35. [Peterbald - TICA](https://tica.org/breed/peterbald/) — `C-S01`, official_feline_organization; Descriptive range; not a population study
36. [Selkirk Rex Cat Breed Information - Purina US](https://www.purina.com/cats/cat-breeds/selkirk-rex) — `C-S02`, professional_pet_reference; Not peer reviewed; range provenance not stated
37. [Minuet Longhair - TICA](https://tica.org/breed/minuet-longhair/) — `C-S03`, official_feline_organization; No sex split; wording 'on average 7-to-8 pounds' treated as range, not measured median
38. [Turkish Angora Cat Breed Information - Purina US](https://www.purina.com/cats/cat-breeds/turkish-angora) — `C-S04`, professional_pet_reference; Not peer reviewed
39. [Burmilla breed guide - Hill's Spain](https://www.hillspet.es/cat-care/cat-breeds/burmilla) — `C-S05`, professional_veterinary_reference; Both sexes given same 4-5 kg range; not peer reviewed
40. [American Shorthair Cat Breed Information - Purina US](https://www.purina.com/cats/cat-breeds/american-shorthair) — `C-S06`, professional_pet_reference; Not peer reviewed
41. [Live capture and handling of Taiwanese leopard cats](https://nsojournals.onlinelibrary.wiley.com/doi/abs/10.1002/wlb3.01032) — `C-S07`, peer_reviewed; Small wild sample; mean±SE only; not domestic breed; min/max not exposed
42. [Balinese - TICA](https://tica.org/breed/balinese/) — `C-S08`, official_feline_organization; Descriptive range; not a population study
43. [Bombay Cat Breed Information - Purina US](https://www.purina.com/cats/cat-breeds/bombay) — `C-S09`, professional_pet_reference; Not peer reviewed
44. [Egyptian Mau Cat Breed Information - Purina US](https://www.purina.com/cats/cat-breeds/egyptian-mau) — `C-S10`, professional_pet_reference; Not peer reviewed
45. [Munchkin - TICA](https://tica.org/breed/munchkin/) — `C-S11`, official_feline_organization; Descriptive range; not a population study
46. [Nebelung Cat Breed Health and Care - PetMD](https://www.petmd.com/cat/breeds/nebelung) — `C-S12`, professional_veterinary_reference; No sex split; not a population study
47. [Royal Canin Kitten Neonatal Growth Curves 0-2 months](https://static.royalcanin.nl/media/Corporate%20Affairs/vet-nurse-webinar/Kitten%20Neonatal%20Growth%20Curves%200-2_months.pdf) — `C-S13`, professional_research_reference; Graph only, no numeric table; most breeds combine sexes; some templates group similar breeds; exact graph values require separate documented digitization
48. [Preventing Fading Kitten Syndrome in Hairless Peterbald Cats](https://www.kantrowitz.com/peterbald/preventing-fading-kitten-syndrome.pdf) — `C-S14`, open_breeder_reference; Not official or peer reviewed; sample and geography not stated
49. [World Species - Prionailurus bengalensis](https://worldspecies.org/ntaxa/901844) — `C-S15`, open_secondary_reference; Underlying reference not exposed in search result; wild species; requires validation before product use
50. [Oriental Longhair - TICA](https://tica.org/breed/oriental-longhair/) — `C-S16`, official_feline_organization; Descriptive range; not a population study
51. [Siamese - TICA](https://tica.org/breed/siamese/) — `C-S17`, official_feline_organization; Proxy, not direct Seychellois measurement; requires product-owner acceptance
52. [Ural Rex - Russian Wikipedia](https://ru.wikipedia.org/wiki/%D0%A3%D1%80%D0%B0%D0%BB%D1%8C%D1%81%D0%BA%D0%B8%D0%B9_%D1%80%D0%B5%D0%BA%D1%81) — `C-S18`, open_encyclopedia; Fallback only; fixed oldid was not captured; official WCF standard gives morphology but no numeric weight
53. [WCF Ural Rex Longhair standard](https://wcf.de/pdf-en/breed/URL_en_2010-01-01.pdf) — `C-S19`, official_feline_organization; No numeric weight
