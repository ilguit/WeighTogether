# ScaleSync

ScaleSync records people, pets, and their weight measurements while preserving the degree of certainty in profile data.

## Language

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
