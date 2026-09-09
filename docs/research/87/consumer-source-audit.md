# Russian cat-breed evidence beyond the population top five: consumer and veterinary sources

Snapshot date: 2026-09-09. This note audits Russian owner surveys, veterinary
registries, identification databases, insurance portfolios and marketplace
demand. The 2023 nationwide census and Яндекс ID top five are treated only as
the existing baseline and are not reconstructed here.

## Result

No public series in these categories meets the planned acceptance rule: a
dated Russian table with a clear unit and denominator and at least ten breed
labels outside the census top five. The strongest newly located source is the
official Moscow veterinary-system (ВетАС) infographic. It adds Abyssinian and
Bengal evidence, but publishes only five ranks, combines British Shorthair and
Scottish Fold at rank 1, and gives no registered-cat denominator.

The broadest complete order is Avito's January 2018 national top ten. It adds
six labels outside the population top five (Bengal, Canadian Sphynx, Don
Sphynx, Abyssinian, Neva Masquerade and Persian), so it still fails the
ten-outside-top-five rule. It is also an old marketplace-demand proxy and the
accessible full list is a secondary reproduction rather than an archived
first-party export.

`consumer-proxy-ranking.csv` preserves only explicitly ordered rows. These
rows must remain separate by `sample_type`; they are not a continuation of the
population rank. `consumer-source-inventory.csv` records both partial sources
and dead ends so the same endpoints need not be searched again.

## Source findings

### Moscow ВетАС veterinary registry

The Moscow Veterinary Committee's official Telegram publication says that the
ranking comes from pets registered in the Veterinary Automated System. Its
infographic gives: (1) British Shorthair and Scottish Fold jointly, (2) Maine
Coon, (3) Abyssinian, (4) Bengal, and (5) Siberian. The accompanying post also
reports 125,000 cats treated/examined and 135,000 vaccinated since the start of
2023, but neither number is identified as the ranking denominator and therefore
neither is copied into the ranking CSV.[^1]

The evidence is useful as a Moscow veterinary-registry cross-check: both new
outside-baseline labels, Abyssinian and Bengal, also occur in the Avito order.
It cannot establish positions 6–50 nationally.

### Owner survey across 37 large cities

An Ингосстрах/Financial University release reports five ordered cat breeds with
percentages: Scottish Fold 18.3%, British 11.7%, Maine Coon 10.3%, Sphynx 8.3%
and Bengal 6.2%. It additionally says Russian Blue (1.6%), Kurilian Bobtail
(1.1%) and an unspecified “Egyptian breed” close the ranking, but does not give
their exact ordering or the last percentage.[^2] The survey respondent count,
breed-question denominator and questionnaire are absent. Consequently the
five leaders are retained as an urban-owner-survey proxy, while the three tail
mentions are counted only in the source inventory.

### Insurance portfolios

Росгосстрах/Пульс reports that 70.77% of insured cats have no breed and gives
British Shorthair 4.6%, Scottish Fold 4.5%, Maine Coon 3.66% and Sphynx 2%.
It separately names Chartreux, Mekong Bobtail and long-haired Selkirk Rex as the
rarest insured cat breeds.[^3] The release gives no policy count, does not give
the rare-breed counts, and, despite its “top-5” title, names only four leading
cat labels. This is insufficient for an ordered dataset.

ВСК claim analytics reports one breed-specific cat morbidity result (Exotic
Shorthair, over 50%) without the number of insured cats or claims.[^4] It is a
health-risk observation, not a prevalence series.

### Animal-ID identification registry

Animal-ID's public landing page reports 405,843 registered animals in its
all-species national database and describes collection of identified-animal
records across Russia.[^5] The public search accepts an exact chip number. The
site map, search page and public documentation expose no breed aggregate,
download, report endpoint or public API. Its license terms say anonymized usage
statistics *may* be published, but that is permission/capability rather than a
published table.[^6] The registry is therefore an important data-request target,
not a reproducible source for the current task.

### Classified demand and other broad platforms

The accessible reproduction of Avito's January 2018 national analysis provides
a complete top-ten order. Only the first three shares are reproduced as demand
shares (36.9%, 22.6%, 8.6%); later rows are kept rank-only rather than mixing in
advertised prices.[^7] Search demand, listing supply and completed ownership are
different units.

Pet911 reports 168,000 Russian lost/found advertisements in 2024 and a 60% cat
share but no breed breakdown.[^8] The СберСтрахование/Rambler&Co survey has a
clear 7,594-person national sample but publishes only coat-length groups (56%
short-haired, 42% long-haired, 2% hairless), not breeds.[^9]

## Normalization and VBO decisions

- Exact VBO mappings are used only where the source label identifies one
  current catalog concept.
- Scottish, Sphynx and broad “British” labels stay unresolved when the source
  does not distinguish catalog concepts or varieties.
- The catalog currently contains overlapping `Don Sphynx` and `Donskoy`
  concepts, so the Russian label `донской сфинкс` is deliberately unresolved.
- The ВетАС joint first place is retained as one unresolved row; it is not split
  into two invented ranks.
- `outside_population_top5` compares source labels with the five household
  labels in the 2023 census. It does not imply a new population rank.

## Coverage and implication

Across the three ordered proxy series, the normalized CSV contains 20 rows and
seven distinct outside-baseline source labels: Bengal, Canadian Sphynx, Don
Sphynx, Abyssinian, Neva Masquerade, Persian and generic Sphynx. The joint
British/Scottish label is not counted as outside. After defensible catalog
normalization, the exact outside-baseline concepts are Abyssinian, Bengal,
Canadian Sphynx, Neva Masquerade and Persian. No source independently supplies
ten such breeds with a denominator, so these data cannot support product ranks
6–50.

The practical next request should be a breed-by-species aggregate export from
Animal-ID or ВетАС with snapshot date, geography, number of unique active
animal records, missing/unknown-breed count and breed-label dictionary. Until
then, these sources are corroborating proxy evidence only.

## Sources

[^1]: Комитет ветеринарии города Москвы / «Моя Ветклиника», [official ВетАС infographic and accompanying post](https://t.me/moyavetklinika/1175), 8 August 2023.
[^2]: Ингосстрах / Финансовый университет, [“Кошки против собак: «Ингосстрах» узнал, каких животных предпочитают россияне”](https://www.vedomosti.ru/press_releases/2023/12/06/koshki-protiv-sobak-ingosstrah-uznal-kakih-zhivotnih-predpochitayut-rossiyane-erid-ldtck3gng), 6 December 2023.
[^3]: Росгосстрах / Пульс, [“Британский тренд: … ТОП-5 застрахованных пород собак и кошек”](https://www.rgs.ru/about/news/britanskiy-trend-v-rosgosstrakhe-nazvali-top-5-zastrakhovannykh-porod-sobak-i-koshek), 13 November 2023.
[^4]: ВСК, [“Все как у людей: домашние питомцы страдают от отита и цистита…”](https://www.vedomosti.ru/press_releases/2024/04/10/vse-kak-u-lyudei-domashnie-pitomtsi-stradayut-ot-otita-i-tsistita-samtsi-boleyut-chasche--analitika-vsk), 10 April 2024.
[^5]: Animal-ID, [national identified-animal database landing page](https://www.animal-id.ru/), accessed 9 September 2026.
[^6]: Animal-ID, [license terms, section 10.1](https://www.animal-id.ru/conditions/), accessed 9 September 2026.
[^7]: Fishki, [secondary reproduction of Avito's national January 2018 top ten](https://fishki.net/2533390-samye-populjarnye-porody-koshek-v-rossii-po-versii-avito.html), 10 March 2018.
[^8]: Pet911, [“Кошки или собаки: кто теряется чаще?”](https://pet911.ru/post/lost_found_pets_russia), 2025.
[^9]: СберСтрахование / Rambler&Co, [national 7,594-person cat-owner survey](https://www.vedomosti.ru/press_releases/2022/08/08/dve-treti-rossiyan-derzhat-doma-koshek), 8 August 2022.
