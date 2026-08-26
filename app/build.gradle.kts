plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
    id("com.example.huaweimisync.release-history")
}

android {
    namespace = "com.example.huaweimisync"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.huaweimisync"
        minSdk = 26
        targetSdk = 36
        versionCode = 25
        versionName = "0.1.21"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        manifestPlaceholders["HUAWEI_APP_ID"] = providers.gradleProperty("HUAWEI_APP_ID").orElse("0").get()
        buildConfigField(
            "String",
            "HUAWEI_APP_ID",
            "\"${providers.gradleProperty("HUAWEI_APP_ID").orElse("0").get()}\"",
        )
    }

    flavorDimensions += "huaweiAccess"
    productFlavors {
        create("personal") {
            dimension = "huaweiAccess"
            applicationIdSuffix = ".personal"
            versionNameSuffix = "-personal"
            buildConfigField("boolean", "HUAWEI_EXTENDED_ENABLED", "false")
        }
        create("huaweiEnterprise") {
            dimension = "huaweiAccess"
            versionNameSuffix = "-huawei-experimental"
            buildConfigField("boolean", "HUAWEI_EXTENDED_ENABLED", "true")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
}

kapt {
    correctErrorTypes = true
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.12.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.compose.runtime:runtime:1.11.4")
    implementation("androidx.compose.foundation:foundation:1.11.4")
    implementation("androidx.compose.ui:ui:1.11.4")
    implementation("androidx.compose.ui:ui-tooling-preview:1.11.4")
    implementation("androidx.compose.material3:material3:1.4.0")
    debugImplementation("androidx.compose.ui:ui-tooling:1.11.4")

    implementation("com.patrykandpatrick.vico:compose:3.2.1")
    implementation("com.patrykandpatrick.vico:compose-m3:3.2.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    kapt("androidx.room:room-compiler:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.health.connect:connect-client:1.1.0")

    // Extended on-device Huawei API. A real AppGallery app id and approved weight scope are
    // still required before calls can succeed on a phone.
    "huaweiEnterpriseImplementation"("com.huawei.hihealth:hihealthkit:6.7.0.300")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.5.0")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    androidTestImplementation("androidx.work:work-testing:2.10.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.11.4")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.11.4")
}

val verifyRoomSchemaVersion by tasks.registering {
    group = "verification"
    description = "Verifies that AppDatabase and the latest committed Room schema use the same version."

    val databaseSource = layout.projectDirectory.file(
        "src/main/kotlin/com/example/huaweimisync/data/AppDatabase.kt",
    )
    val schemaDirectory = layout.projectDirectory.dir(
        "schemas/com.example.huaweimisync.data.AppDatabase",
    )

    inputs.file(databaseSource)
    inputs.dir(schemaDirectory)

    doLast {
        val source = databaseSource.asFile.readText()
        val databaseAnnotation = Regex(
            pattern = """@Database\s*\((.*?)\)\s*abstract\s+class\s+AppDatabase""",
            option = RegexOption.DOT_MATCHES_ALL,
        ).find(source) ?: throw GradleException(
            "Cannot determine the Room version: AppDatabase @Database annotation was not found.",
        )
        val declaredVersion = Regex("""\bversion\s*=\s*(\d+)""")
            .find(databaseAnnotation.groupValues[1])
            ?.groupValues
            ?.get(1)
            ?.toInt()
            ?: throw GradleException(
                "Cannot determine the Room version: @Database must declare a numeric version.",
            )

        val schemaVersions = schemaDirectory.asFile
            .listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "json" }
            .mapNotNull { it.nameWithoutExtension.toIntOrNull() }
        val highestSchemaVersion = schemaVersions.maxOrNull()
            ?: throw GradleException(
                "Cannot verify the Room version: no numeric schema snapshots were found in " +
                    "${schemaDirectory.asFile}.",
            )

        if (declaredVersion < highestSchemaVersion) {
            throw GradleException(
                "AppDatabase version $declaredVersion is below the highest committed Room schema " +
                    "version $highestSchemaVersion. Restore or advance the @Database version.",
            )
        }
        if (declaredVersion != highestSchemaVersion) {
            throw GradleException(
                "AppDatabase version $declaredVersion does not match the highest committed Room " +
                    "schema version $highestSchemaVersion. Commit the matching schema snapshot.",
            )
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(verifyRoomSchemaVersion)
}

tasks.named("check").configure {
    dependsOn(verifyRoomSchemaVersion)
}
