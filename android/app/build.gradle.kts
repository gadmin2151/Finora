import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val signingPath = providers.environmentVariable("FINORA_KEYSTORE")

android {
    namespace = "work.gadmin.finora"
    compileSdk { version = release(37) { minorApiLevel = 0 } }
    defaultConfig {
        applicationId = "work.gadmin.finora"
        minSdk = 26
        targetSdk = 37
        versionCode = 8
        versionName = "1.3.0"
        testInstrumentationRunner =
            if (providers.gradleProperty("finoraTestBuildType").orNull == "release")
                "work.gadmin.finora.ReleaseSmokeInstrumentation"
            else "androidx.test.runner.AndroidJUnitRunner"
    }
    if (signingPath.isPresent) {
        signingConfigs.create("release") {
            storeFile = file(signingPath.get())
            storePassword = providers.environmentVariable("FINORA_STORE_PASSWORD").get()
            keyAlias = "finora"
            keyPassword = providers.environmentVariable("FINORA_KEY_PASSWORD").get()
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (signingPath.isPresent) signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    testBuildType = providers.gradleProperty("finoraTestBuildType").orElse("debug").get()
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.08.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
