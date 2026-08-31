package com.palixander.scalesync.domain.reference

import com.palixander.scalesync.core.reference.ReferenceBasis
import com.palixander.scalesync.core.reference.WeightReferenceSnapshot
import com.palixander.scalesync.domain.BreedId
import com.palixander.scalesync.domain.PetSex
import com.palixander.scalesync.domain.PetSpecies
import com.palixander.scalesync.domain.PartialBirthDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class PetWeightReferenceResolverTest {
    private val resolver = PetWeightReferenceResolver()
    private val referenceDate = LocalDate.of(2025, 1, 15)

    @Test
    fun `exact dog date resolves explicit category without approximation`() {
        val result = resolveDog(
            birthDate = PartialBirthDate.Day(referenceDate.minusDays(100)),
            dogAdultWeight = DogAdultWeight.Category(DogAdultWeightCategory.III),
        ).available()

        assertEquals("dog-male-III", result.profileId)
        assertEquals(ReferenceBasis.WEIGHT_CATEGORY, result.basis)
        assertEquals(100L..100L, result.ageDays)
        assertFalse(result.approximate)
        assertEquals(result.bounds.medianLowerKg, result.bounds.medianUpperKg, 0.0)
    }

    @Test
    fun `interpolation does not by itself make an exact birth date approximate`() {
        val snapshot = WeightReferenceSnapshot.bundled()
        val profile = snapshot.profiles.single { it.id == "dog-male-I" }
        val nonPointAge = (profile.points[0].ageDays + 1).toLong()

        val result = resolveDog(
            birthDate = PartialBirthDate.Day(referenceDate.minusDays(nonPointAge)),
            dogAdultWeight = DogAdultWeight.ExpectedWeightKg(6.49),
        ).available()

        assertFalse(result.approximate)
        assertEquals("dog-male-I", result.profileId)
        assertEquals(snapshot.interpolate(profile.id, nonPointAge.toInt())!!.medianKg, result.bounds.medianLowerKg, 1e-12)
    }

    @Test
    fun `dog reference carries the last published point through declared scope only`() {
        val snapshot = WeightReferenceSnapshot.bundled()
        val last = snapshot.profiles.single { it.id == "dog-male-I" }.points.last()

        for (ageDays in 728L..730L) {
            val result = resolveDog(
                birthDate = PartialBirthDate.Day(referenceDate.minusDays(ageDays)),
                dogAdultWeight = DogAdultWeight.Category(DogAdultWeightCategory.I),
            ).available()

            assertEquals(ageDays..ageDays, result.ageDays)
            assertEquals(last.lowerKg, result.bounds.lowerKg, 1e-12)
            assertEquals(last.medianKg, result.bounds.medianLowerKg, 1e-12)
            assertEquals(last.medianKg, result.bounds.medianUpperKg, 1e-12)
            assertEquals(last.upperKg, result.bounds.upperKg, 1e-12)
        }

        val outside = resolveDog(
            birthDate = PartialBirthDate.Day(referenceDate.minusDays(731)),
            dogAdultWeight = DogAdultWeight.Category(DogAdultWeightCategory.I),
        ).unavailable().reason as WeightReferenceUnavailableReason.AgeOutOfRange
        assertEquals(730, outside.supportedMaximumDays)
        assertEquals(731, outside.actualMaximumDays)
    }

    @Test
    fun `partial dog birth date may end exactly at declared upper boundary`() {
        val boundaryDate = LocalDate.of(2025, 1, 31)
        val result = resolver.resolve(
            PetSpecies.DOG,
            PetSex.MALE,
            null,
            PartialBirthDate.Month(YearMonth.of(2023, 2)),
            boundaryDate,
            DogAdultWeight.Category(DogAdultWeightCategory.I),
        ).available()

        assertEquals(703L..730L, result.ageDays)
        assertTrue(result.approximate)

        val crossing = resolveDog(
            birthDate = PartialBirthDate.Month(YearMonth.of(2023, 1)),
            dogAdultWeight = DogAdultWeight.Category(DogAdultWeightCategory.I),
        ).unavailable().reason as WeightReferenceUnavailableReason.AgeOutOfRange
        assertEquals(715, crossing.actualMinimumDays)
        assertEquals(745, crossing.actualMaximumDays)
        assertEquals(730, crossing.supportedMaximumDays)
    }

    @Test
    fun `partial date evaluates both age endpoints and aggregates conservative bounds`() {
        val birthDate = PartialBirthDate.Month(YearMonth.of(2024, 8))
        val snapshot = WeightReferenceSnapshot.bundled()
        val profile = snapshot.profiles.single { it.id == "dog-male-IV" }
        val younger = snapshot.interpolate(profile.id, 137)!!
        val older = snapshot.interpolate(profile.id, 167)!!

        val result = resolveDog(
            birthDate = birthDate,
            dogAdultWeight = DogAdultWeight.Category(DogAdultWeightCategory.IV),
        ).available()

        assertTrue(result.approximate)
        assertEquals(137L..167L, result.ageDays)
        assertEquals(minOf(younger.lowerKg, older.lowerKg), result.bounds.lowerKg, 1e-12)
        assertEquals(minOf(younger.medianKg, older.medianKg), result.bounds.medianLowerKg, 1e-12)
        assertEquals(maxOf(younger.medianKg, older.medianKg), result.bounds.medianUpperKg, 1e-12)
        assertEquals(maxOf(younger.upperKg, older.upperKg), result.bounds.upperKg, 1e-12)
    }

    @Test
    fun `partial date includes internal extrema instead of only interval endpoints`() {
        val date = LocalDate.of(2024, 8, 8)
        val snapshot = WeightReferenceSnapshot.bundled()
        val profile = snapshot.profiles.single { it.id == "dog-female-I" }
        val expected = profile.points.filter { it.ageDays in 161..189 }

        val result = resolver.resolve(
            PetSpecies.DOG,
            PetSex.FEMALE,
            null,
            PartialBirthDate.Month(YearMonth.of(2024, 2)),
            date,
            DogAdultWeight.Category(DogAdultWeightCategory.I),
        ).available()

        assertEquals(161L..189L, result.ageDays)
        assertEquals(expected.minOf { it.lowerKg }, result.bounds.lowerKg, 1e-12)
        assertEquals(expected.minOf { it.medianKg }, result.bounds.medianLowerKg, 1e-12)
        assertEquals(expected.maxOf { it.medianKg }, result.bounds.medianUpperKg, 1e-12)
        assertEquals(expected.maxOf { it.upperKg }, result.bounds.upperKg, 1e-12)
    }

    @Test
    fun `DSH sparse intervals are data gaps while weekly intervals interpolate`() {
        val breed = BreedId("VBO:0100119")
        for (sex in listOf(PetSex.FEMALE, PetSex.MALE)) {
            assertReason<WeightReferenceUnavailableReason.ReferenceDataGap>(
                resolver.resolve(
                    PetSpecies.CAT,
                    sex,
                    breed,
                    PartialBirthDate.Day(referenceDate.minusDays(230)),
                    referenceDate,
                    intactStatus = IntactStatus.CONFIRMED_INTACT,
                ),
            )
            assertTrue(
                resolver.resolve(
                    PetSpecies.CAT,
                    sex,
                    breed,
                    PartialBirthDate.Day(referenceDate.minusDays(165)),
                    referenceDate,
                    intactStatus = IntactStatus.CONFIRMED_INTACT,
                ) is PetWeightReferenceResolution.Available,
            )
        }
    }

    @Test
    fun `future birth date is typed invalid data instead of throwing`() {
        assertReason<WeightReferenceUnavailableReason.InvalidBirthDate>(
            resolveDog(
                birthDate = PartialBirthDate.Day(referenceDate.plusDays(1)),
                dogAdultWeight = DogAdultWeight.Category(DogAdultWeightCategory.I),
            ),
        )
    }

    @Test
    fun `expected dog weights map deterministically at category boundaries`() {
        val cases = listOf(
            6.4999 to "I",
            6.5 to "II",
            8.9999 to "II",
            9.0 to "III",
            15.0 to "IV",
            30.0 to "V",
        )
        cases.forEach { (weight, category) ->
            val result = resolveDog(
                birthDate = PartialBirthDate.Day(referenceDate.minusDays(100)),
                dogAdultWeight = DogAdultWeight.ExpectedWeightKg(weight),
            ).available()
            assertEquals("dog-male-$category", result.profileId)
        }
    }

    @Test
    fun `known dog breed without profile falls back to weight category and reports actual basis`() {
        val result = resolveDog(
            breedId = BreedId("VBO:0000661"),
            birthDate = PartialBirthDate.Day(referenceDate.minusDays(100)),
            dogAdultWeight = DogAdultWeight.Category(DogAdultWeightCategory.II),
        ).available()

        assertEquals("dog-male-II", result.profileId)
        assertEquals(ReferenceBasis.WEIGHT_CATEGORY, result.basis)
    }

    @Test
    fun `DSH breed has priority and requires explicitly confirmed intact status`() {
        val breed = BreedId("VBO:0100119")
        val birthDate = PartialBirthDate.Day(referenceDate.minusDays(100))
        assertReason<WeightReferenceUnavailableReason.DshIntactStatusUnknown>(
            resolver.resolve(PetSpecies.CAT, PetSex.FEMALE, breed, birthDate, referenceDate),
        )
        assertReason<WeightReferenceUnavailableReason.DshNotIntact>(
            resolver.resolve(
                PetSpecies.CAT,
                PetSex.FEMALE,
                breed,
                birthDate,
                referenceDate,
                dogAdultWeight = DogAdultWeight.Category(DogAdultWeightCategory.I),
                intactStatus = IntactStatus.CONFIRMED_NOT_INTACT,
            ),
        )

        val result = resolver.resolve(
            PetSpecies.CAT,
            PetSex.FEMALE,
            breed,
            birthDate,
            referenceDate,
            intactStatus = IntactStatus.CONFIRMED_INTACT,
        ).available()
        assertEquals("cat-dsh-female", result.profileId)
        assertEquals(ReferenceBasis.BREED, result.basis)
    }

    @Test
    fun `missing inputs and unsupported species have distinct reasons`() {
        val date = PartialBirthDate.Day(referenceDate.minusDays(100))
        assertReason<WeightReferenceUnavailableReason.MissingSex>(
            resolver.resolve(PetSpecies.DOG, null, null, date, referenceDate),
        )
        assertReason<WeightReferenceUnavailableReason.MissingBirthDate>(
            resolver.resolve(PetSpecies.DOG, PetSex.MALE, null, null, referenceDate),
        )
        assertReason<WeightReferenceUnavailableReason.MissingDogAdultWeight>(
            resolveDog(birthDate = date),
        )
        assertReason<WeightReferenceUnavailableReason.MissingBreed>(
            resolver.resolve(PetSpecies.CAT, PetSex.MALE, null, date, referenceDate),
        )
        assertReason<WeightReferenceUnavailableReason.UnsupportedSpecies>(
            resolver.resolve(PetSpecies.UNSPECIFIED, PetSex.MALE, null, date, referenceDate),
        )
    }

    @Test
    fun `breed lookup failures species mismatch and unsupported cat breed are distinct`() {
        val date = PartialBirthDate.Day(referenceDate.minusDays(100))
        assertReason<WeightReferenceUnavailableReason.UnknownBreed>(
            resolveDog(BreedId("not-in-catalog"), date, DogAdultWeight.Category(DogAdultWeightCategory.I)),
        )
        assertReason<WeightReferenceUnavailableReason.BreedSpeciesMismatch>(
            resolveDog(BreedId("VBO:0100119"), date, DogAdultWeight.Category(DogAdultWeightCategory.I)),
        )
        assertReason<WeightReferenceUnavailableReason.UnsupportedBreed>(
            resolver.resolve(
                PetSpecies.CAT,
                PetSex.MALE,
                BreedId("VBO:0100000"),
                date,
                referenceDate,
                intactStatus = IntactStatus.CONFIRMED_INTACT,
            ),
        )
    }

    @Test
    fun `over forty kg and ages crossing either profile edge are typed unavailable`() {
        assertReason<WeightReferenceUnavailableReason.AdultWeightAboveSupportedMaximum>(
            resolveDog(
                birthDate = PartialBirthDate.Day(referenceDate.minusDays(100)),
                dogAdultWeight = DogAdultWeight.ExpectedWeightKg(40.01),
            ),
        )
        val tooYoung = resolveDog(
            birthDate = PartialBirthDate.Day(referenceDate.minusDays(83)),
            dogAdultWeight = DogAdultWeight.Category(DogAdultWeightCategory.I),
        ).unavailable().reason as WeightReferenceUnavailableReason.AgeOutOfRange
        assertEquals(83, tooYoung.actualMinimumDays)

        val partiallyTooOld = resolveDog(
            birthDate = PartialBirthDate.Month(YearMonth.of(2023, 1)),
            dogAdultWeight = DogAdultWeight.Category(DogAdultWeightCategory.I),
        ).unavailable().reason as WeightReferenceUnavailableReason.AgeOutOfRange
        assertTrue(partiallyTooOld.actualMaximumDays > partiallyTooOld.supportedMaximumDays)
    }

    private fun resolveDog(
        breedId: BreedId? = null,
        birthDate: PartialBirthDate?,
        dogAdultWeight: DogAdultWeight? = null,
    ) = resolver.resolve(
        PetSpecies.DOG,
        PetSex.MALE,
        breedId,
        birthDate,
        referenceDate,
        dogAdultWeight,
    )

    private fun PetWeightReferenceResolution.available() =
        (this as PetWeightReferenceResolution.Available).reference

    private fun PetWeightReferenceResolution.unavailable() =
        this as PetWeightReferenceResolution.Unavailable

    private inline fun <reified T : WeightReferenceUnavailableReason> assertReason(
        result: PetWeightReferenceResolution,
    ) {
        assertTrue(result.unavailable().reason is T)
    }
}
