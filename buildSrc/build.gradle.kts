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
            id = "com.example.huaweimisync.room-schema-guard"
            implementationClass = "com.example.huaweimisync.gradle.RoomSchemaGuardPlugin"
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
