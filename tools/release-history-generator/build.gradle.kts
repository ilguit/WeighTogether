plugins {
    kotlin("jvm") version "2.3.21"
    `java-gradle-plugin`
}

group = "com.palixander.weightogether.tools"
version = "1.0.0"

kotlin {
    jvmToolchain(17)
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation("org.snakeyaml:snakeyaml-engine:2.10")
    compileOnly("com.android.tools.build:gradle:8.13.2")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(gradleTestKit())
}

gradlePlugin {
    plugins.create("releaseHistory") {
        id = "com.palixander.weightogether.release-history"
        implementationClass = "com.palixander.weightogether.releasehistory.ReleaseHistoryPlugin"
    }
}

tasks.test {
    useJUnitPlatform()
}
