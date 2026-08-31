package com.palixander.scalesync.ui.reference

import androidx.annotation.StringRes
import com.palixander.scalesync.R
import com.palixander.scalesync.core.BodyMetric

enum class ReferenceMetricGroup(
    @param:StringRes val titleRes: Int?,
) {
    WEIGHT(null),
    MAIN(R.string.reference_group_main),
    BODY_COMPOSITION(R.string.reference_group_body_composition),
    MUSCLES_AND_BONES(R.string.reference_group_muscles_and_bones),
    METABOLISM(R.string.reference_group_metabolism),
}

enum class ReferenceUnitKind {
    KILOGRAM,
    OHM,
    BMI,
    PERCENT,
    LEVEL,
    KCAL_PER_DAY,
    YEAR,
}

enum class ReferenceSourceKind(
    @param:StringRes val actionLabelRes: Int,
) {
    RATING(R.string.reference_open_rating_source),
    DESCRIPTION(R.string.reference_open_description_source),
}

data class ReferenceHelpCopy(
    @param:StringRes val meaningRes: Int,
    @param:StringRes val calculationRes: Int,
    @param:StringRes val dependenciesRes: Int,
    @param:StringRes val limitationsRes: Int,
    @param:StringRes val sourceNameRes: Int,
    val sourceKind: ReferenceSourceKind,
    val sourceUrl: String,
    @param:StringRes val secondaryCitationRes: Int? = null,
    val showBiaDisclaimer: Boolean = false,
    val showBiaContraindications: Boolean = false,
)

data class ReferenceMetricDefinition(
    val metric: BodyMetric,
    val group: ReferenceMetricGroup,
    @param:StringRes val nameRes: Int,
    @param:StringRes val visibleUnitRes: Int,
    val unitKind: ReferenceUnitKind,
    val decimalPlaces: Int,
    val help: ReferenceHelpCopy,
)

private const val XIAOMI_LEGACY_URL =
    "https://github.com/dckiller51/bodymiscale/blob/7553db70f44dd84f9a5ae0792445c46b30452332/custom_components/bodymiscale/body_scales.py"
private const val FMI_URL = "https://pubmed.ncbi.nlm.nih.gov/12080449/"
private const val OMRON_URL =
    "https://omronhealthcare.com/storage/pdfs/body-composition-monitor-and-scale-with-bluetooth-bcm500-im-en_5022647-0d.pdf"
private const val TANITA_AGE_URL =
    "https://tanita.eu/media/pdf/products-tanita/bcm-consumer/rd-953/Manuals/RD-953%20Instruction%20Manual%20%28EN%2C%20DE%2C%20FR%2C%20NL%29%202023.pdf"
private const val IMPEDANCE_URL = "https://www.inbody.com/en/technology/contents/bia"
private const val WATER_MASS_URL = "https://tanita.eu/blog/What-Do-Different-BIA-Results-Mean"
private const val PROTEIN_MASS_URL =
    "https://www.sciencedirect.com/science/article/pii/S0002916522034232"

object ReferenceMetricCatalog {
    val metrics: List<ReferenceMetricDefinition> = listOf(
        metric(
            BodyMetric.WEIGHT, ReferenceMetricGroup.WEIGHT, R.string.reference_metric_weight,
            R.string.reference_unit_kg, ReferenceUnitKind.KILOGRAM, 2,
            R.string.reference_help_weight_meaning, R.string.reference_help_weight_calculation,
            R.string.reference_help_weight_dependencies, R.string.reference_help_weight_limitations,
            R.string.reference_source_xiaomi_legacy, ReferenceSourceKind.RATING, XIAOMI_LEGACY_URL,
        ),
        metric(
            BodyMetric.IMPEDANCE, ReferenceMetricGroup.MAIN, R.string.reference_metric_impedance,
            R.string.reference_unit_ohm, ReferenceUnitKind.OHM, 0,
            R.string.reference_help_impedance_meaning, R.string.reference_help_impedance_calculation,
            R.string.reference_help_impedance_dependencies, R.string.reference_help_impedance_limitations,
            R.string.reference_source_inbody_bia, ReferenceSourceKind.DESCRIPTION, IMPEDANCE_URL,
            showBiaContraindications = true,
        ),
        metric(
            BodyMetric.BMI, ReferenceMetricGroup.MAIN, R.string.reference_metric_bmi,
            R.string.reference_unit_bmi, ReferenceUnitKind.BMI, 1,
            R.string.reference_help_bmi_meaning, R.string.reference_help_bmi_calculation,
            R.string.reference_help_bmi_dependencies, R.string.reference_help_bmi_limitations,
            R.string.reference_source_xiaomi_legacy, ReferenceSourceKind.RATING, XIAOMI_LEGACY_URL,
        ),
        metric(
            BodyMetric.BODY_FAT_PERCENT, ReferenceMetricGroup.BODY_COMPOSITION, R.string.reference_metric_body_fat,
            R.string.reference_unit_percent, ReferenceUnitKind.PERCENT, 1,
            R.string.reference_help_body_fat_meaning, R.string.reference_help_body_fat_calculation,
            R.string.reference_help_body_fat_dependencies, R.string.reference_help_body_fat_limitations,
            R.string.reference_source_xiaomi_legacy, ReferenceSourceKind.RATING, XIAOMI_LEGACY_URL,
            bia = true,
        ),
        metric(
            BodyMetric.BODY_FAT_MASS, ReferenceMetricGroup.BODY_COMPOSITION, R.string.reference_metric_body_fat_mass,
            R.string.reference_unit_kg, ReferenceUnitKind.KILOGRAM, 2,
            R.string.reference_help_body_fat_mass_meaning, R.string.reference_help_body_fat_mass_calculation,
            R.string.reference_help_body_fat_mass_dependencies, R.string.reference_help_body_fat_mass_limitations,
            R.string.reference_source_schutz_fmi, ReferenceSourceKind.RATING, FMI_URL,
            bia = true,
        ),
        metric(
            BodyMetric.WATER_PERCENT, ReferenceMetricGroup.BODY_COMPOSITION, R.string.reference_metric_water,
            R.string.reference_unit_percent, ReferenceUnitKind.PERCENT, 1,
            R.string.reference_help_water_meaning, R.string.reference_help_water_calculation,
            R.string.reference_help_water_dependencies, R.string.reference_help_water_limitations,
            R.string.reference_source_xiaomi_legacy, ReferenceSourceKind.RATING, XIAOMI_LEGACY_URL,
            bia = true,
        ),
        metric(
            BodyMetric.WATER_MASS, ReferenceMetricGroup.BODY_COMPOSITION, R.string.reference_metric_water_mass,
            R.string.reference_unit_kg, ReferenceUnitKind.KILOGRAM, 2,
            R.string.reference_help_water_mass_meaning, R.string.reference_help_water_mass_calculation,
            R.string.reference_help_water_mass_dependencies, R.string.reference_help_water_mass_limitations,
            R.string.reference_source_tanita_results, ReferenceSourceKind.DESCRIPTION, WATER_MASS_URL,
            R.string.reference_secondary_chumlea, bia = true,
        ),
        metric(
            BodyMetric.PROTEIN_PERCENT, ReferenceMetricGroup.BODY_COMPOSITION, R.string.reference_metric_protein,
            R.string.reference_unit_percent, ReferenceUnitKind.PERCENT, 1,
            R.string.reference_help_protein_meaning, R.string.reference_help_protein_calculation,
            R.string.reference_help_protein_dependencies, R.string.reference_help_protein_limitations,
            R.string.reference_source_xiaomi_legacy, ReferenceSourceKind.RATING, XIAOMI_LEGACY_URL,
            bia = true,
        ),
        metric(
            BodyMetric.PROTEIN_MASS, ReferenceMetricGroup.BODY_COMPOSITION, R.string.reference_metric_protein_mass,
            R.string.reference_unit_kg, ReferenceUnitKind.KILOGRAM, 2,
            R.string.reference_help_protein_mass_meaning, R.string.reference_help_protein_mass_calculation,
            R.string.reference_help_protein_mass_dependencies, R.string.reference_help_protein_mass_limitations,
            R.string.reference_source_protein_model, ReferenceSourceKind.DESCRIPTION, PROTEIN_MASS_URL,
            bia = true,
        ),
        metric(
            BodyMetric.LEAN_BODY_MASS, ReferenceMetricGroup.BODY_COMPOSITION, R.string.reference_metric_lean_body_mass,
            R.string.reference_unit_kg, ReferenceUnitKind.KILOGRAM, 2,
            R.string.reference_help_lean_body_mass_meaning, R.string.reference_help_lean_body_mass_calculation,
            R.string.reference_help_lean_body_mass_dependencies, R.string.reference_help_lean_body_mass_limitations,
            R.string.reference_source_xiaomi_legacy, ReferenceSourceKind.DESCRIPTION, XIAOMI_LEGACY_URL,
            R.string.reference_secondary_espen, bia = true,
        ),
        metric(
            BodyMetric.MUSCLE_MASS, ReferenceMetricGroup.MUSCLES_AND_BONES, R.string.reference_metric_muscle_mass,
            R.string.reference_unit_kg, ReferenceUnitKind.KILOGRAM, 2,
            R.string.reference_help_muscle_mass_meaning, R.string.reference_help_muscle_mass_calculation,
            R.string.reference_help_muscle_mass_dependencies, R.string.reference_help_muscle_mass_limitations,
            R.string.reference_source_xiaomi_legacy, ReferenceSourceKind.RATING, XIAOMI_LEGACY_URL,
            bia = true,
        ),
        metric(
            BodyMetric.SKELETAL_MUSCLE_MASS, ReferenceMetricGroup.MUSCLES_AND_BONES, R.string.reference_metric_skeletal_muscle,
            R.string.reference_unit_kg, ReferenceUnitKind.KILOGRAM, 2,
            R.string.reference_help_skeletal_muscle_meaning, R.string.reference_help_skeletal_muscle_calculation,
            R.string.reference_help_skeletal_muscle_dependencies, R.string.reference_help_skeletal_muscle_limitations,
            R.string.reference_source_omron, ReferenceSourceKind.RATING, OMRON_URL,
            R.string.reference_secondary_janssen, bia = true,
        ),
        metric(
            BodyMetric.BONE_MASS, ReferenceMetricGroup.MUSCLES_AND_BONES, R.string.reference_metric_bone_mass,
            R.string.reference_unit_kg, ReferenceUnitKind.KILOGRAM, 2,
            R.string.reference_help_bone_mass_meaning, R.string.reference_help_bone_mass_calculation,
            R.string.reference_help_bone_mass_dependencies, R.string.reference_help_bone_mass_limitations,
            R.string.reference_source_xiaomi_legacy, ReferenceSourceKind.RATING, XIAOMI_LEGACY_URL,
            R.string.reference_secondary_niams, bia = true,
        ),
        metric(
            BodyMetric.VISCERAL_FAT, ReferenceMetricGroup.METABOLISM, R.string.reference_metric_visceral_fat,
            R.string.reference_unit_level, ReferenceUnitKind.LEVEL, 1,
            R.string.reference_help_visceral_fat_meaning, R.string.reference_help_visceral_fat_calculation,
            R.string.reference_help_visceral_fat_dependencies, R.string.reference_help_visceral_fat_limitations,
            R.string.reference_source_xiaomi_legacy, ReferenceSourceKind.RATING, XIAOMI_LEGACY_URL,
            bia = true,
        ),
        metric(
            BodyMetric.BASAL_METABOLIC_RATE, ReferenceMetricGroup.METABOLISM, R.string.reference_metric_bmr,
            R.string.reference_unit_kcal_day, ReferenceUnitKind.KCAL_PER_DAY, 0,
            R.string.reference_help_bmr_meaning, R.string.reference_help_bmr_calculation,
            R.string.reference_help_bmr_dependencies, R.string.reference_help_bmr_limitations,
            R.string.reference_source_xiaomi_legacy, ReferenceSourceKind.RATING, XIAOMI_LEGACY_URL,
            bia = true,
        ),
        metric(
            BodyMetric.METABOLIC_AGE, ReferenceMetricGroup.METABOLISM, R.string.reference_metric_metabolic_age,
            R.string.reference_unit_years, ReferenceUnitKind.YEAR, 0,
            R.string.reference_help_metabolic_age_meaning, R.string.reference_help_metabolic_age_calculation,
            R.string.reference_help_metabolic_age_dependencies, R.string.reference_help_metabolic_age_limitations,
            R.string.reference_source_tanita_age, ReferenceSourceKind.RATING, TANITA_AGE_URL,
            bia = true,
        ),
    )

    val byMetric: Map<BodyMetric, ReferenceMetricDefinition> = metrics.associateBy { it.metric }

    init {
        check(metrics.size == BodyMetric.entries.size && byMetric.size == BodyMetric.entries.size) {
            "Reference metric catalog must contain every metric exactly once"
        }
    }
}

@Suppress("LongParameterList")
private fun metric(
    metric: BodyMetric,
    group: ReferenceMetricGroup,
    @StringRes nameRes: Int,
    @StringRes unitRes: Int,
    unitKind: ReferenceUnitKind,
    decimals: Int,
    @StringRes meaningRes: Int,
    @StringRes calculationRes: Int,
    @StringRes dependenciesRes: Int,
    @StringRes limitationsRes: Int,
    @StringRes sourceNameRes: Int,
    sourceKind: ReferenceSourceKind,
    sourceUrl: String,
    @StringRes secondaryCitationRes: Int? = null,
    bia: Boolean = false,
    showBiaContraindications: Boolean = bia,
) = ReferenceMetricDefinition(
    metric = metric,
    group = group,
    nameRes = nameRes,
    visibleUnitRes = unitRes,
    unitKind = unitKind,
    decimalPlaces = decimals,
    help = ReferenceHelpCopy(
        meaningRes = meaningRes,
        calculationRes = calculationRes,
        dependenciesRes = dependenciesRes,
        limitationsRes = limitationsRes,
        sourceNameRes = sourceNameRes,
        sourceKind = sourceKind,
        sourceUrl = sourceUrl,
        secondaryCitationRes = secondaryCitationRes,
        showBiaDisclaimer = bia,
        showBiaContraindications = showBiaContraindications,
    ),
)
