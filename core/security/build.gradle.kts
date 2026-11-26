import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
}

// Optional developer pre-fill (BYOK fallback). Empty in CI / release builds.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val devMapboxToken: String = localProps.getProperty("MAPBOX_PUBLIC_TOKEN") ?: ""
val devOcmKey: String = localProps.getProperty("OCM_API_KEY") ?: ""

android {
    namespace = "io.github.lyzio.ajnav.core.security"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        buildConfigField("String", "MAPBOX_PUBLIC_TOKEN", "\"$devMapboxToken\"")
        buildConfigField("String", "OCM_API_KEY", "\"$devOcmKey\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

kapt { correctErrorTypes = true }

dependencies {
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
}
