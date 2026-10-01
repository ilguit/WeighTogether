plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

repositories {
    gradlePluginPortal()
}

gradlePlugin {
    plugins {
        create("roomSchemaGuard") {
            id = "com.palixander.weightogether.room-schema-guard"
            implementationClass = "com.palixander.weightogether.gradle.RoomSchemaGuardPlugin"
        }
    }
}

dependencies {
    testImplementation(gradleTestKit())
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}
