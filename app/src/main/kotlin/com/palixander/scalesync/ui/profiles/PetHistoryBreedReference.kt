package com.palixander.scalesync.ui.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.palixander.scalesync.core.breedreference.BreedReferenceSex
import com.palixander.scalesync.core.breedreference.BreedReferenceSourceKind
import com.palixander.scalesync.core.breedreference.BreedReferenceStatisticKind
import com.palixander.scalesync.domain.Pet
import com.palixander.scalesync.domain.reference.BreedWeightAgeScope
import com.palixander.scalesync.domain.reference.BreedWeightReference
import com.palixander.scalesync.domain.reference.BreedWeightReferenceDetail
import com.palixander.scalesync.domain.reference.BreedWeightReferenceResolution
import com.palixander.scalesync.domain.reference.BreedWeightReferenceResolver
import com.palixander.scalesync.domain.reference.BreedWeightReferenceUnavailableReason
import com.palixander.scalesync.domain.reference.BreedWeightSourceMetadata
import com.palixander.scalesync.domain.reference.BreedWeightValue
import com.palixander.scalesync.ui.components.HuaweiSurface
import com.palixander.scalesync.ui.reference.ReferenceSourceLauncher
import com.palixander.scalesync.ui.theme.HuaweiDimensions
import java.text.NumberFormat
import java.time.Clock
import java.time.LocalDate
import java.util.Locale

internal object PetBreedReferenceTestTags {
    const val Card = "pet-breed-reference-card"
    const val Edit = "pet-breed-reference-edit"
    const val Details = "pet-breed-reference-details"
    const val OpenSource = "pet-breed-reference-open-source"
    const val SourceError = "pet-breed-reference-source-error"
}

sealed interface PetHistoryBreedReference {
    data object Hidden : PetHistoryBreedReference

    data class Unavailable(
        val reason: BreedWeightReferenceUnavailableReason,
        val message: String,
        val showEditAction: Boolean,
    ) : PetHistoryBreedReference

    data class Available(
        val breedName: String,
        val ageLabel: String,
        val valueLabels: List<String>,
        val sourceKindLabel: String,
        val sexLabel: String,
        val partialDateDisclosure: String?,
        val accessibilityLabel: String,
        val source: PetHistoryBreedSource,
        val details: List<PetHistoryBreedReferenceDetail>,
    ) : PetHistoryBreedReference
}

data class PetHistoryBreedSource(
    val title: String,
    val url: String,
    val year: Int?,
    val kindLabel: String,
    val geography: String?,
    val method: String?,
    val pageOrTable: String?,
    val sampleLabel: String?,
    val limitations: List<String>,
)

data class PetHistoryBreedReferenceDetail(
    val ageLabel: String,
    val sexLabel: String,
    val valueLabel: String,
    val sourceTitle: String?,
    val documentedGap: String?,
    val youngerThanSelectedAge: Boolean,
    val sampleLabel: String?,
    val limitations: List<String>,
)

class PetHistoryBreedReferencePresenter(
    private val resolver: BreedWeightReferenceResolver = BreedWeightReferenceResolver(),
    private val clock: Clock = Clock.systemDefaultZone(),
    private val locale: Locale = Locale.getDefault(),
) {
    fun present(pet: Pet): PetHistoryBreedReference = when (
        val resolution = resolver.resolve(pet.species, pet.breedId, pet.sex, pet.birthDate, LocalDate.now(clock))
    ) {
        is BreedWeightReferenceResolution.Available -> resolution.reference.toPresentation()
        is BreedWeightReferenceResolution.Unavailable -> resolution.reason.toPresentation()
    }

    private fun BreedWeightReference.toPresentation(): PetHistoryBreedReference.Available {
        val age = ageScope.label()
        val values = values.map { it.label(locale) }
        val kind = source.kind.label()
        val partialDisclosure = ageDisclosure.possibleAgeDays?.takeIf { ageDisclosure.partial }?.let { possible ->
            "Дата рождения указана не полностью. Возможный возраст: ${possible.first.ageLabel()}–${possible.last.ageLabel()}. " +
                "Показан ориентир ${age.prepositionForm()}."
        }
        val sourcePresentation = source.presentation(sampleSize, sampleUnit, limitations)
        return PetHistoryBreedReference.Available(
            breedName = breedRussianName,
            ageLabel = age,
            valueLabels = values,
            sourceKindLabel = kind,
            sexLabel = sex.label(),
            partialDateDisclosure = partialDisclosure,
            accessibilityLabel = buildString {
                append("Ориентиры породы $breedRussianName. Для возраста: $age. ")
                append(values.joinToString(". "))
                append(". Тип источника: $kind. Не является медицинской нормой.")
            },
            source = sourcePresentation,
            details = details.map { it.presentation() },
        )
    }

    private fun BreedWeightReferenceUnavailableReason.toPresentation(): PetHistoryBreedReference = when (this) {
        BreedWeightReferenceUnavailableReason.UnsupportedSpecies -> PetHistoryBreedReference.Hidden
        BreedWeightReferenceUnavailableReason.OtherBreed -> unavailable("Для другой породы ориентиров пока нет.", false)
        is BreedWeightReferenceUnavailableReason.RemovedOrUnsupportedBreed -> unavailable("Для выбранной породы ориентиры сейчас недоступны.", false)
        BreedWeightReferenceUnavailableReason.MissingSex -> unavailable("Укажите пол питомца, чтобы показать ориентиры породы.", true)
        BreedWeightReferenceUnavailableReason.InvalidBirthDate -> unavailable("Исправьте дату рождения, чтобы показать ориентир для возраста.", true)
        is BreedWeightReferenceUnavailableReason.SnapshotUnavailable -> unavailable("Ориентиры породы временно недоступны.", false)
        BreedWeightReferenceUnavailableReason.NoApplicableValue -> unavailable("Для выбранной породы нет применимого ориентира веса.", false)
        is BreedWeightReferenceUnavailableReason.DocumentedGap -> unavailable("Для выбранного возраста опубликованные данные отсутствуют.", false)
    }

    private fun BreedWeightReferenceUnavailableReason.unavailable(message: String, edit: Boolean) =
        PetHistoryBreedReference.Unavailable(this, message, edit)

    private fun BreedWeightReferenceDetail.presentation() = PetHistoryBreedReferenceDetail(
        ageLabel = ageScope.label(),
        sexLabel = sex.label(),
        valueLabel = value?.label(locale) ?: "Числовое значение не опубликовано",
        sourceTitle = source?.title,
        documentedGap = documentedGap,
        youngerThanSelectedAge = youngerThanSelectedAge,
        sampleLabel = sampleSize?.let { "$it ${sampleUnit.orEmpty()}".trim() },
        limitations = limitations,
    )
}

@Composable
internal fun PetHistoryBreedReferenceCard(
    reference: PetHistoryBreedReference,
    onEdit: () -> Unit,
    sourceLauncher: ReferenceSourceLauncher,
) {
    if (reference is PetHistoryBreedReference.Hidden) return
    var expanded by remember(reference) { mutableStateOf(false) }
    var sourceError by remember(reference) { mutableStateOf(false) }
    HuaweiSurface(Modifier.fillMaxWidth().testTag(PetBreedReferenceTestTags.Card)) {
        Column(verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing)) {
            Text("Ориентиры породы", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            when (reference) {
                PetHistoryBreedReference.Hidden -> Unit
                is PetHistoryBreedReference.Unavailable -> {
                    Text(reference.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (reference.showEditAction) TextButton(
                        onClick = onEdit,
                        modifier = Modifier.heightIn(min = HuaweiDimensions.TouchTarget).testTag(PetBreedReferenceTestTags.Edit),
                    ) { Text("Изменить профиль") }
                }
                is PetHistoryBreedReference.Available -> {
                    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = reference.accessibilityLabel }) {
                        Text("Для возраста: ${reference.ageLabel}")
                        reference.valueLabels.forEach { value -> Text(value) }
                        Text("Тип: ${reference.sourceKindLabel}")
                        reference.partialDateDisclosure?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    TextButton(onClick = { expanded = !expanded }) {
                        Text("Источник и ограничения ${if (expanded) "▴" else "▾"}")
                    }
                    if (expanded) Column(Modifier.fillMaxWidth().testTag(PetBreedReferenceTestTags.Details)) {
                        SourceDetails(reference.source)
                        Text("Пол: ${reference.sexLabel}")
                        Text("Возрастная область: ${reference.ageLabel}")
                        reference.valueLabels.forEach { value -> Text("Значение: $value") }
                        reference.details.forEach { detail ->
                            Text(if (detail.youngerThanSelectedAge) "Более младшие данные: ${detail.ageLabel}" else "Дополнительные данные: ${detail.ageLabel}")
                            Text("${detail.sexLabel}: ${detail.valueLabel}")
                            detail.sourceTitle?.let { Text("Источник: $it") }
                            detail.documentedGap?.let { Text("Документированный пропуск: $it") }
                            detail.sampleLabel?.let { Text("Выборка: $it") }
                            detail.limitations.forEach { Text("Ограничение: $it") }
                        }
                        TextButton(
                            onClick = { sourceError = !sourceLauncher.open(reference.source.url) },
                            modifier = Modifier.heightIn(min = HuaweiDimensions.TouchTarget).testTag(PetBreedReferenceTestTags.OpenSource),
                        ) { Text("Открыть источник") }
                        if (sourceError) Text(
                            "Не удалось открыть источник.",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag(PetBreedReferenceTestTags.SourceError),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceDetails(source: PetHistoryBreedSource) {
    Text("Организация или публикация: ${source.title}")
    source.year?.let { Text("Год: $it") }
    Text("Тип утверждения: ${source.kindLabel}")
    source.sampleLabel?.let { Text("Выборка: $it") }
    source.geography?.let { Text("География: $it") }
    source.method?.let { Text("Метод: $it") }
    source.pageOrTable?.let { Text("Страница или таблица: $it") }
    source.limitations.forEach { Text("Ограничение: $it") }
}

private fun BreedWeightSourceMetadata.presentation(sampleSize: Int?, sampleUnit: String?, limitations: List<String>) =
    PetHistoryBreedSource(title, url, year, kind.label(), geography, method, pageOrTable,
        sampleSize?.let { "$it ${sampleUnit.orEmpty()}".trim() }, limitations)

private fun BreedWeightAgeScope.label() = when (this) {
    BreedWeightAgeScope.Adult -> "взрослой собаки"
    is BreedWeightAgeScope.Age -> label
}

private fun String.prepositionForm() = if (startsWith("для ")) this else "для $this"

private fun Long.ageLabel(): String {
    val months = this / 30
    return if (months > 0) "$months мес." else "$this дн."
}

private fun BreedWeightValue.label(locale: Locale): String {
    val number = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2 }
    val statisticLabel = statistic.label()
    return when (this) {
        is BreedWeightValue.Interval -> "$statisticLabel: ${number.format(lower)}–${number.format(upper)} кг" +
            (center?.let { "; центр: ${number.format(it)} кг" } ?: "")
        is BreedWeightValue.Single -> "$statisticLabel: ${number.format(value)} кг" +
            (spread?.let { " ± ${number.format(it)} кг" } ?: "")
    }
}

private fun BreedReferenceStatisticKind.label() = when (this) {
    BreedReferenceStatisticKind.RANGE -> "Диапазон"
    BreedReferenceStatisticKind.QUANTILES -> "Квантили"
    BreedReferenceStatisticKind.MEAN -> "Среднее"
    BreedReferenceStatisticKind.MEDIAN -> "Медиана"
    BreedReferenceStatisticKind.APPROXIMATE_AVERAGE -> "Приблизительное среднее"
    BreedReferenceStatisticKind.MEAN_SD -> "Среднее и стандартное отклонение"
    BreedReferenceStatisticKind.DOCUMENTED_GAP -> "Документированный пропуск"
}

private fun BreedReferenceSourceKind.label() = when (this) {
    BreedReferenceSourceKind.INTERNATIONAL_STANDARD -> "официальный международный стандарт"
    BreedReferenceSourceKind.NATIONAL_STANDARD -> "официальный национальный стандарт"
    BreedReferenceSourceKind.BREED_CLUB -> "породная организация"
    BreedReferenceSourceKind.PROFESSIONAL_REFERENCE -> "профессиональный справочник"
    BreedReferenceSourceKind.OBSERVATIONAL -> "наблюдаемая выборка"
    BreedReferenceSourceKind.MODELLED -> "модельные данные"
}

private fun BreedReferenceSex.label() = when (this) {
    BreedReferenceSex.MALE -> "Самец"
    BreedReferenceSex.FEMALE -> "Самка"
    BreedReferenceSex.COMBINED -> "Для обоих полов"
}
