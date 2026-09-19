# Issue #83 research package: dog ranks 16–20

This package records source-faithful adult standards and available 0–24-month observations for the five requested Eurasia 2024 rank rows. It deliberately does not build product growth curves: sparse points remain sparse, standards are not population distributions, and missing values remain null.

## Mapping and popularity baseline

| Rank | Eurasia registrations | Event label (RU) | VBO concept | Canonical name |
|---:|---:|---|---|---|
| 16 | 120 | басенджи | `VBO:0200120` | Basenji |
| 17 | 117 | немецкий той-шпиц (померанский) | `VBO:0200599` | German Spitz, Pomeranian |
| 18 | 111 | родезийский риджбек | `VBO:0201135` | Rhodesian Ridgeback |
| 19 | 103 | среднеазиатская овчарка | `VBO:0200321` | Central Asian Shepherd Dog |
| 20 | 102 | цвергпинчер | `VBO:0200893` | Miniature Pinscher |

Counts and rank are preserved from the official two-day Eurasia 2024 tables through the existing normalized repository study. They measure show registrations, not Russian household prevalence.

## Adult findings

- Basenji: FCI ideal values are 11 kg/43 cm for males and 9.5 kg/40 cm for females.
- German Toy Spitz/Pomeranian: FCI height is 21 ± 3 cm and its weight statement is non-numeric. Following the source precedence, Wikipedia supplies a secondary all-sex adult fallback of 1.36–3.17 kg; it is kept on a separate row so it is not presented as an FCI value or a sex-specific range.
- Rhodesian Ridgeback: FCI gives 36.5 kg and 63–69 cm for males; 32 kg and 61–66 cm for females.
- Central Asian Shepherd Dog: FCI minima are 50 kg/70 cm for males and 40 kg/65 cm for females. No maxima are stated. The same standard says full maturity is reached at three years, so 24 months cannot automatically be treated as mature.
- Miniature Pinscher: FCI gives 4–6 kg and 25–30 cm for both sexes.

These values are encoded with their semantics (`ideal`, `standard_point`, `range`, or `minimum`) in `adult-standards.csv`; a point is not silently converted to a range.

## Juvenile evidence

Two breeds have exact, breed-specific published observations that can be transcribed without chart digitisation or modelling:

- Pomeranian: a French peer-reviewed cohort reports birth-weight quartiles of 130/150/166 g and birth-to-day-2 relative-change quartiles of −7.2/2.7/8.1% (n=117). An independent Italian census reports 114/124/150 g at birth (n=11 across three litters). These are neonatal observations, not a 24-month reference.
- Rhodesian Ridgeback: the Italian census reports birth-weight quartiles of 358.5/390/420 g (n=76 across seven litters). A longitudinal neonatal study followed one litter of 10 puppies through day 21 and publishes a birth range of 360–550 g; exact later observations are graphical, so they were not digitised.

No suitable numeric juvenile cohort was found for Basenji, Central Asian Shepherd Dog, or Miniature Pinscher. No commercial breed chart, crowd-submitted pet chart, extrapolation from adult standards, or interpolation between ages was used. Every missing interval and missing adult bound is listed in `gaps.csv`.

## Files and interpretation

- `adult-standards.csv`: one row per breed/sex applicability, including rank, registrations, VBO mapping, exact FCI semantics, page, and caveats.
- `age-observations.csv`: only directly published numeric observations; ages are closed day intervals and units are explicit.
- `gaps.csv`: explicit absent evidence and the decision taken for each gap.
- `sources.csv`: URLs, years, pages, source types, samples, methods, and limitations.
- `validate.py`: package-level structural and source-semantics regression checks.

The age observations are evidence inputs, not ready-made clinical centiles. Combining cohorts, turning quartiles into min/max ranges, or estimating missing ages would require a separately specified modelling and validation step.
