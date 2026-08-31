plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    id("org.jetbrains.kotlin.kapt") version "2.3.21" apply false
}

tasks.register("verifyBreedCatalogSnapshot") {
    group = "verification"
    description = "Regenerates and verifies the offline breed catalog snapshot."
    dependsOn(gradle.includedBuild("breed-catalog").task(":verifySnapshot"))
}

tasks.register("verifyWeightReferenceSnapshot") {
    group = "verification"
    description = "Regenerates and verifies the evidence-based weight-reference snapshot."
    dependsOn(gradle.includedBuild("weight-references").task(":verifySnapshot"))
}
