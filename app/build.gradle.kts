import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Google OAuth "TVs and Limited Input devices" client credentials. These identify
// the *app* to Google (not the user) and are the same for every install; for a
// sideloaded personal app they are not true secrets, but we still keep them out of
// git by reading from local.properties (which is git-ignored). Empty defaults let
// the project build and unit-test with no creds — see docs/drive-setup.md.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun creds(key: String): String = (localProps.getProperty(key) ?: "").let { "\"$it\"" }

android {
    namespace = "com.diffuse"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.diffuse"
        minSdk = 33
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-phase4"

        buildConfigField("String", "DRIVE_CLIENT_ID", creds("DRIVE_CLIENT_ID"))
        buildConfigField("String", "DRIVE_CLIENT_SECRET", creds("DRIVE_CLIENT_SECRET"))
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":lightui"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Phase 3 — Google Drive upload
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.core)
    implementation(libs.androidx.security.crypto)

    // Phase 4 — scheduled background backup
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
