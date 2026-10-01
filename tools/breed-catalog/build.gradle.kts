plugins {
    application
    java
}

group = "com.palixander.weightogether.tools"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

application {
    mainClass = "com.palixander.weightogether.breedcatalog.Main"
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
}

val generatedSnapshot = layout.buildDirectory.file("verification/breed_catalog.json")
val vendoredSource = layout.projectDirectory.file("vbo-2026-04-15.obo.gz")
val russianOverrides = layout.projectDirectory.file("russian-overrides.tsv")
val vboExclusions = layout.projectDirectory.file("vbo-exclusions.tsv")

val regenerateSnapshot by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Regenerates the bundled breed catalog from the vendored immutable VBO source."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = application.mainClass
    inputs.file(vendoredSource)
    inputs.file(russianOverrides)
    inputs.file(vboExclusions)
    outputs.file(generatedSnapshot)
    args(
        "--source", vendoredSource.asFile.absolutePath,
        "--source-version", "2026-04-15",
        "--source-url", "https://purl.obolibrary.org/obo/vbo/releases/2026-04-15/vbo.obo",
        "--source-sha256", "4ada4d18dcc2ea421f1ee630dfee29042ae2a45a34b0928419475377ee3304bd",
        "--snapshot-date", "2026-08-30",
        "--overrides", russianOverrides.asFile.absolutePath,
        "--exclusions", vboExclusions.asFile.absolutePath,
        "--output", generatedSnapshot.get().asFile.absolutePath,
    )
}

tasks.register("verifySnapshot") {
    group = "verification"
    description = "Fails when the tracked breed catalog differs from deterministic generator output."
    dependsOn(regenerateSnapshot, tasks.test)
    inputs.file(generatedSnapshot)
    val trackedSnapshot = layout.projectDirectory.file("../../core/src/main/resources/breed_catalog.json")
    inputs.file(trackedSnapshot)
    doLast {
        val generated = generatedSnapshot.get().asFile.readBytes()
        val tracked = trackedSnapshot.asFile.readBytes()
        check(generated.contentEquals(tracked)) {
            "Tracked breed_catalog.json is stale; regenerate it with tools/breed-catalog (generated ${generated.size} bytes, tracked ${tracked.size} bytes)."
        }
    }
}
