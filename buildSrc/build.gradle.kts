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
            id = "com.palixander.scalesync.room-schema-guard"
            implementationClass = "com.palixander.scalesync.gradle.RoomSchemaGuardPlugin"
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
