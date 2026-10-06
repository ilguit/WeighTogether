import com.palixander.weightogether.gradle.RuStoreSigning

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
    id("com.palixander.weightogether.release-history")
    id("com.palixander.weightogether.room-schema-guard")
}

val rustoreSigning = RuStoreSigning.read(
    enabled = providers.gradleProperty("rustoreSigning").orNull,
    environment = RuStoreSigning.environmentNames.mapNotNull { name ->
        providers.environmentVariable(name).orNull?.let { name to it }
    }.toMap(),
    checkout = rootDir,
)

android {
    namespace = "com.palixander.weightogether"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.palixander.weightogether"
        minSdk = 26
        targetSdk = 36
        versionCode = 274
        versionName = "0.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

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
        resources.merges += "META-INF/services/kotlinx.coroutines.CoroutineExceptionHandler"
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }

    signingConfigs {
        rustoreSigning?.let { credentials ->
            create("rustore") {
                storeFile = credentials.storeFile
                storePassword = credentials.storePassword
                keyAlias = credentials.keyAlias
                keyPassword = credentials.keyPassword
            }
        }
    }

    buildTypes {
        release {
            if (rustoreSigning != null) {
                signingConfig = signingConfigs.getByName("rustore")
            }
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    sourceSets.getByName("test").java.srcDir("src/sharedTest/kotlin")
    sourceSets.getByName("androidTest").java.srcDir("src/sharedTest/kotlin")
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
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("androidx.activity:activity-compose:1.12.3")
    implementation("androidx.appcompat:appcompat:1.7.1")
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

    testImplementation("androidx.compose.ui:ui-test-junit4:1.11.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    // SchemaBundle is a runtime-only dependency of room-testing; expose it to the regression test.
    androidTestImplementation("androidx.room:room-migration:2.8.4")
    // Room 2.8.4 schema serializers require the 1.8.1 GeneratedSerializer default method.
    // Instrumentation shares the app runtime; an androidTest-only dependency is downgraded
    // by AGP's consistent resolution. Keep the compatible app runtime limited to debug.
    debugImplementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.1")
    androidTestImplementation("androidx.work:work-testing:2.10.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.11.4")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.11.4")
    // The app classloader must discover the exception collector used by Compose instrumentation.
    debugImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
