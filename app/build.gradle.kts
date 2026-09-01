plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
    id("com.palixander.scalesync.release-history")
    id("com.palixander.scalesync.room-schema-guard")
}

android {
    namespace = "com.palixander.scalesync"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.palixander.scalesync"
        minSdk = 26
        targetSdk = 36
        versionCode = 120
        versionName = "0.2"
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
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
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    androidTestImplementation("androidx.test:runner:1.5.0")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    androidTestImplementation("androidx.work:work-testing:2.10.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.11.4")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.11.4")
}
