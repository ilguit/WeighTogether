plugins {
    application
    java
}

java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }
application { mainClass = "com.palixander.scalesync.weightreferences.Main" }
repositories { mavenCentral() }
dependencies {
    implementation("com.google.code.gson:gson:2.13.2")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}
tasks.test { useJUnitPlatform() }

val generatedSnapshot = layout.buildDirectory.file("verification/weight_references.json")
val sourceDocument = layout.projectDirectory.file("weight_references.source.json")
val fittedCatCurves = layout.projectDirectory.file("../../docs/research/97/bccg-curves.csv")

val regenerateSnapshot by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Normalizes the audited weight-reference source document."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = application.mainClass
    inputs.files(sourceDocument, fittedCatCurves)
    outputs.file(generatedSnapshot)
    args("--snapshot", sourceDocument.asFile.absolutePath, fittedCatCurves.asFile.absolutePath, generatedSnapshot.get().asFile.absolutePath)
}

tasks.register("verifySnapshot") {
    group = "verification"
    dependsOn(regenerateSnapshot, tasks.test)
    val trackedSnapshot = layout.projectDirectory.file("../../core/src/main/resources/weight_references.json")
    inputs.files(generatedSnapshot, trackedSnapshot)
    doLast {
        check(generatedSnapshot.get().asFile.readBytes().contentEquals(trackedSnapshot.asFile.readBytes())) {
            "Tracked weight_references.json is stale; regenerate it with tools/weight-references."
        }
    }
}
