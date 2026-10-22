import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Pilot upload target (pilot/Code.gs deployed in your Google account). Lives in
// local.properties, which git ignores. Empty = no upload; "Share pilot data" still works.
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.resqgo.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.resqgo.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
        buildConfigField("String", "UPLOAD_URL", "\"${local.getProperty("RESQGO_UPLOAD_URL", "")}\"")
        buildConfigField("String", "UPLOAD_KEY", "\"${local.getProperty("RESQGO_UPLOAD_KEY", "")}\"")
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

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.03.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // Android's org.json is a stub in local unit tests
}
