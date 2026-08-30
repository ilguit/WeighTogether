plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("com.google.code.gson:gson:2.13.2")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}

tasks.named("check") {
    dependsOn(gradle.includedBuild("breed-catalog").task(":verifySnapshot"))
}
