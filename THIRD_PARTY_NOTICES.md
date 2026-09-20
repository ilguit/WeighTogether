# Third-party data notices

## Wikipedia cat breed adult weight ranges

ScaleSync includes adult weight-range facts from fixed revisions of French,
English, and German Wikipedia for British Shorthair, Scottish Fold, Siamese,
Maine Coon, and Siberian cats. Authors are the respective Wikipedia
contributors. Source revision links are embedded in `weight_references.json`.
Wikipedia text is licensed under Creative Commons Attribution-ShareAlike 4.0:
https://creativecommons.org/licenses/by-sa/4.0/

## Dog breed adult weight references

ScaleSync includes adult weight facts for ranks 21–30 of the Eurasia 2024
research baseline. Official conformation values and directly derived values
come from Fédération Cynologique Internationale standards 183 (Miniature
Schnauzer), 253 (Pug), and 345 (Jack Russell Terrier). FCI 345 states an ideal
height-to-weight equivalence; ScaleSync applies that rule exactly to the
standard's 25–30 cm ideal height range. FCI 367 is retained only as provenance
for the explicit absence of a numeric Miniature American Shepherd weight.
Source links, applicability, page references, and limitations are embedded in
`breed_references.json`. The FCI standards remain copyright of their
respective origin countries and the FCI.

Where the applicable FCI standard publishes no weight, ScaleSync uses adult
weight facts from the English Wikipedia articles “Dachshund”, “Samoyed dog”,
“Akita (dog breed)”, and “Cardigan Welsh Corgi”,
the French Wikipedia article “American Staffordshire Terrier”, the Russian
Wikipedia articles “Восточноевропейская овчарка” and “Среднеазиатская
овчарка”, and the Italian Wikipedia article “Border Collie”. The Central Asian
Shepherd fact is attributed to the fixed Russian Wikipedia revision
`oldid=154838607` embedded in `breed_references.json`.
Authors are the respective Wikipedia contributors. Wikipedia text is licensed
under Creative Commons Attribution-ShareAlike 4.0:
https://creativecommons.org/licenses/by-sa/4.0/. The Akita fallback preserves
the broad sex-specific infobox intervals and discloses the article's
Japanese/American naming ambiguity. The American Staffordshire Terrier,
Cardigan, and East European Shepherd fallbacks preserve the published
sex-specific ranges. The Central Asian Shepherd range remains a separate
combined-sex secondary reference beside the official sex-specific minima.

The Miniature American Shepherd combined adult range is converted from the
American Kennel Club Breed Weight Chart's published 20–40 lb range. It is
identified as a professional secondary reference: neither FCI Standard No. 367
nor Wikipedia publishes a numeric adult weight, and no sex split is inferred.

The American Staffordshire Terrier sex-specific ranges from Svenska
Terrierklubben and observational means from Andersson et al. are retained as
inactive provenance only. FCI Standard No. 286 publishes preferred height by
sex but no numeric weight, so ScaleSync labels the Wikipedia value as an
approximate secondary fallback rather than an official standard.

## Dog breed adult weight references, ranks 31–50

ScaleSync includes adult weight facts for ranks 31–50 of the Eurasia 2024
research baseline. Official conformation values come from Fédération
Cynologique Internationale standards 3, 73, 86, 99, 147, 149, 200, 215 and
218. Standards that publish no numeric weight, or only a relative statement,
are retained as provenance for explicit data gaps. Source links, applicability,
page references, statistic semantics and limitations are embedded in
`breed_references.json`. The FCI standards remain copyright of their respective
origin countries and the FCI.

Where the applicable standard does not provide a usable numeric weight,
ScaleSync uses facts from the English Wikipedia articles for Basenji (fixed
revision `oldid=1351259637`), West Highland White Terrier, Bernese Mountain
Dog, Norwich Terrier, Chinese Crested Dog, Thai Ridgeback, Dachshund, Miniature
Bull Terrier, Kerry Blue Terrier and Rough Collie. Authors are the respective
Wikipedia contributors. Wikipedia text is
licensed under Creative Commons Attribution-ShareAlike 4.0:
https://creativecommons.org/licenses/by-sa/4.0/. Only numeric facts and their
source limitations are represented; article prose is not redistributed or
converted into growth curves. Conflicting values remain disclosed rather than
being merged or averaged.

The American Akita ranges come from the English Wikipedia article “Akita (dog
breed)” at fixed revision
https://en.wikipedia.org/w/index.php?title=Akita_(dog_breed)&oldid=1368872159;
its contributor history is available at
https://en.wikipedia.org/w/index.php?title=Akita_(dog_breed)&action=history.
The Rottweiler ranges come from the English Wikipedia article “Rottweiler” at
fixed revision
https://en.wikipedia.org/w/index.php?title=Rottweiler&oldid=1368889170; its
contributor history is available at
https://en.wikipedia.org/w/index.php?title=Rottweiler&action=history. The
combined-sex Shetland Sheepdog (Sheltie) range comes from the Russian Wikipedia
article “Шелти” at fixed revision
https://ru.wikipedia.org/w/index.php?title=%D0%A8%D0%B5%D0%BB%D1%82%D0%B8&oldid=151572016;
its contributor history is available at
https://ru.wikipedia.org/w/index.php?title=%D0%A8%D0%B5%D0%BB%D1%82%D0%B8&action=history.
Authors are the contributors listed in those histories. These Wikipedia facts
are used under Creative Commons Attribution-ShareAlike 4.0:
https://creativecommons.org/licenses/by-sa/4.0/. The same fixed revision URLs
are recorded in the `breed_references.json` source registry.

The snapshot records explicit 0–24 month growth-data gaps for every added
breed. Generic dog-size curves are not relabelled as breed observations and no
values are digitised, interpolated or inferred from height.

## German Shepherd Dog adult weight reference

ScaleSync includes the sex-specific adult weight ranges from Fédération
Cynologique Internationale Standard No. 166 for the German Shepherd Dog:
30–40 kg for males and 22–32 kg for females. These are official conformation
standard ranges, not clinical healthy-weight percentiles or observational
population intervals. The source link, page reference, applicability and
limitations are embedded in `breed_references.json`. The FCI standard remains
copyright of its country of origin and the FCI.

The popularity metadata retains the exact double-coat row from the two Eurasia
2024 all-breed show tables (32 plus 31 registrations, rank 61). The separately
listed long-and-harsh-outer-coat row has 25 plus 28 registrations and rank 70;
the two varieties are not silently combined into a synthetic event rank.

## Liverpool canine growth-standard supporting data

Salt C, Morris PJ, German AJ, Wilson D, Lund EM, Cole TJ, and Butterwick RF
(2017), “Growth standard charts for monitoring bodyweight in dogs of different
sizes,” PLOS ONE 12(9): e0182064, DOI 10.1371/journal.pone.0182064. The corrected
data-availability statement is DOI 10.1371/journal.pone.0314711 (2024). Supporting
data: DOI 10.17638/datacat.liverpool.ac.uk/377, licensed CC BY 4.0.

ScaleSync records the upstream archive SHA-256
`a494c1c1bc6841ab3840d5757f34a20892e025a8f9da7e9cbb3b9d6086b07ff7`.
The archive contains raw observations, not fitted centile tables or models;
ScaleSync therefore does not redistribute or infer numerical dog curves from it.

## Liverpool Domestic Shorthair kitten supporting data

Salt C, German AJ, and Butterwick RF (2022), “Growth standard charts for
monitoring bodyweight in intact domestic shorthair kittens from the USA,” PLOS
ONE 17(12): e0277531, DOI 10.1371/journal.pone.0277531. Supporting data: DOI
10.17638/datacat.liverpool.ac.uk/1456, licensed CC BY 3.0.

ScaleSync records the upstream CSV SHA-256
`76be97fd71d5139fb648e58c69db58945c221df33f1b7f15fc12e244db90e094`.
The CSV contains raw observations, not fitted centile tables or models;
ScaleSync therefore does not redistribute or infer numerical kitten curves.

## Mugnier breed-specific kitten birth-weight observations

Mugnier A, Gaillard V, and Chastant S. (2023), “Association between Birth
Weight and Mortality over the Two First Months after Birth in Feline Species:
Definition of Breed-Specific Thresholds,” Animals 13(11):1822, DOI
10.3390/ani13111822, licensed CC BY 4.0.

ScaleSync reproduces the Maine Coon and Siberian pure-breed birth-weight mean,
standard deviation, and sample size from Table 1. The values combine both sexes
and are exposed only at the exact observation age; they are not medians,
percentiles, interpolated curves, or clinical standards. ScaleSync records the
upstream article XML SHA-256
`fa4bfd1fa294935a715e72f4e736068cb3b05d95c0548e09e2f9a75e5f2d581d`.

## Wikipedia dog breed adult weight ranges

ScaleSync includes adult weight-range facts from the Golden Retriever,
Australian Shepherd, and Pomeranian articles on English Wikipedia. Authors are
the respective Wikipedia contributors. Source links and access-year metadata
are embedded in `breed_references.json`. Wikipedia text is licensed under
Creative Commons Attribution-ShareAlike 4.0:
https://creativecommons.org/licenses/by-sa/4.0/

Only the numeric ranges and their source limitations are represented. ScaleSync
does not reproduce article prose or convert the ranges into growth curves.

## Breed-specific canine neonatal observations

ScaleSync reproduces exact breed-level neonatal summary statistics from the
following research sources, identified by DOI or stable article URL in
`breed_references.json`:

- Mila et al. (2020), “A model of puppy growth during the first three weeks”;
- Komarova et al. (2020), “Comparative characteristics of reproductive
  qualities of service dog breeds”;
- Mugnier et al. (2019), “Birth weight as a risk factor for neonatal mortality:
  Breed-specific approach to identify at-risk puppies”;
- Groppetti et al. (2017), “A National Census of Birth Weight in Purebred Dogs
  in Italy”;
- Alves et al. (2020), “A model of puppy growth during the first three weeks.”

The snapshot stores only directly reported birth or day-30 values, sample
metadata, and limitations. Relative changes, plotted-only later measurements,
pooled growth models, and inferred curves are not redistributed.
