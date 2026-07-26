import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Google OAuth "TVs and Limited Input devices" client credentials. These identify
// the *app* to Google (not the user) and are the same for every install; for the
// device flow this is a public client, so the "secret" is not truly confidential
// (see docs/releasing.md). We still keep it out of git: local dev reads it from
// local.properties (git-ignored); CI release builds read it from the environment
// (GitHub Actions secrets). Empty defaults let the project build and unit-test with
// no creds — see docs/drive-setup.md.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// Env var wins (CI), then local.properties (dev), then "" (no-cred build/test).
fun creds(key: String): String =
    (System.getenv(key) ?: localProps.getProperty(key) ?: "").let { "\"$it\"" }

// Release signing. CI provides the keystore + passwords via the environment
// (the .jks is base64-decoded into app/ by the release workflow); local release
// builds read the same values from a git-ignored keystore.properties at the repo
// root. When neither is present, no release signingConfig is created and
// assembleRelease produces an unsigned APK (fine for CI dry-runs / local checks).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signing(key: String): String? = System.getenv(key) ?: keystoreProps.getProperty(key)
val hasReleaseSigning = signing("KEYSTORE_FILE") != null && signing("KEYSTORE_PASSWORD") != null

android {
    namespace = "com.diffuse"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.diffuse"
        minSdk = 33
        targetSdk = 34
        // CI stamps these from the release tag (e.g. tag v0.1.0 -> VERSION_NAME=0.1.0,
        // run number -> VERSION_CODE). Local builds fall back to the defaults.
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "0.1.0"

        buildConfigField("String", "DRIVE_CLIENT_ID", creds("DRIVE_CLIENT_ID"))
        buildConfigField("String", "DRIVE_CLIENT_SECRET", creds("DRIVE_CLIENT_SECRET"))
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(signing("KEYSTORE_FILE")!!)
                storePassword = signing("KEYSTORE_PASSWORD")
                keyAlias = signing("KEY_ALIAS") ?: "diffuse"
                keyPassword = signing("KEY_PASSWORD") ?: signing("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
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
