# ScaleSync

ScaleSync records people, pets, and their weight measurements while preserving the degree of certainty in profile data.

## Language

**Measurement origin**:
How a weight record was created: received from a scale, entered manually, or unknown for legacy records. Origin is independent of later edits.

**Entered manually (Введено вручную)**:
A weight supplied by the user for a selected person or pet, with its measurement date and time.
_Avoid_: Manually edited

**Manually edited (Изменено вручную)**:
A later change to a saved measurement. It does not change how the original record was created.

**Measurement date and time**:
When the recorded weight applies, which may precede the time the record was saved.

**Direct pet weight**:
A pet's weight entered as a standalone measurement, with no pair of source weighings.

**Pet**:
An animal whose weight measurements are kept separately from human accounts.

**Pet species**:
The broad biological kind of a pet: cat or dog. An unspecified value represents legacy data whose species is not known.

**Pet sex**:
The biological sex recorded for a pet: male or female. Absence means that the value is not known or was not provided.

**Breed ID**:
A stable identifier of a breed in a breed catalog. Absence means that no catalog breed has been selected.
_Avoid_: Breed name

**Partial birth date**:
A pet's birth date known to year, month, or exact day precision. The recorded precision is part of the value and must not be inferred as a more exact date.
_Avoid_: Approximate birth date

**Age interval**:
The range of possible completed ages on an explicitly chosen date, derived from a partial birth date.
_Avoid_: Estimated age, current age

**Breed weight reference**:
A published informational weight value for a particular breed, sex scope, and age scope. It describes the source population or breed standard and does not assess an individual pet's health.
_Avoid_: Normal weight, ideal weight, weight target

**Weight-category age reference**:
An empirical age-based weight reference selected through a dog's adult weight category. It is distinct from a breed weight reference.
_Avoid_: Breed standard, breed weight reference

**Typical weight range (Типичный диапазон веса)**:
A sex- and age-specific informational range from a documented reference population. It supports visual comparison and does not assess an individual pet's health.
_Avoid_: Normal weight, ideal weight, healthy weight, weight target

**Supported breed**:
A breed for which the current product dataset contains a complete set of usable breed-reference data.
_Avoid_: Catalog breed

**Other breed**:
The state in which no supported breed has been selected. It also represents an unknown or unlisted breed.
_Avoid_: Unsupported Breed ID

**Documented gap**:
A confirmed absence of a usable published value for a specific breed-reference scope. It is neither a zero value nor permission to substitute a weaker claim.
_Avoid_: Missing value, zero

**Reference provenance**:
The known origin and evidentiary context of a reference, including its source, claim and statistic kinds, population, geography, method, and limitations.
_Avoid_: Source link
