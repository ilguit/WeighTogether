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
    mainClass = "com.palixander.weightogether.releasenotes.Main"
}

dependencies {
    implementation("org.snakeyaml:snakeyaml-engine:2.10")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
}
