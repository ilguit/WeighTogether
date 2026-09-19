# Dog weight evidence package: ranks 11–15

Research date: 2026-09-19. Scope: the five rows ranked 11–15 in the
two-day all-breed registration tables for «Евразия 2024».

## Result

`breeds.csv` preserves the exact popularity order, counts and VBO mapping.
The counts are **show registrations**, despite the source page's term
"registrations"; they are not Russian ownership counts or annual pedigree
registrations. The tie at 133 deliberately preserves source order.

Adult evidence follows the requested precedence. FCI standards supply numeric
weight only for Papillon. Cardigan's current FCI standard says weight must be
proportional, while Golden Retriever and Australian Shepherd standards omit
numeric weight; the latter two therefore use Wikipedia only as the permitted
fallback. The East European Shepherd is not recognized by FCI, so the official
RKF standard is primary and its missing numeric weight remains a gap. No
Wikipedia number was promoted over that explicit official omission.

Age-specific evidence is intentionally sparse. A peer-reviewed prospective
study supplies a breed-specific Golden Retriever birth range (32 puppies), but
its later growth model pools breeds and is not converted into Golden values. A
peer-reviewed comparative study supplies East European Shepherd birth means
and a 30-day combined mean. No defensible breed-specific 0–24-month observations
were found for Papillon, Cardigan Welsh Corgi or Australian Shepherd.
Commercial growth calculators and owner anecdotes were excluded. Nothing was
interpolated, digitized from a chart or borrowed from a related breed/size
class.

## Files and interpretation

- `breeds.csv`: rank, event count, exact concept mapping and metric caveat.
- `adult-reference.csv`: sex-aware adult values, provenance and partial status.
- `age-observations.csv`: only directly reported age/weight observations.
- `gaps.csv`: machine-readable missing fields/windows and a `do_not_infer` flag.
- `sources.csv`: URL, year, page/section, type, sample/method and limitations.
- `validate.py`: structural and cross-file integrity checks.

These data are research inputs, not clinical thresholds. Conformation standards
describe breed type and cannot by themselves establish healthy percentile
bands. In particular, a shared secondary adult envelope must not be presented
as a sex-specific norm merely because it is repeated on both sex rows.
