import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Swedish/Nordic open data (design §4.5): SMHI weather in v0.3, Trafikverket in v0.5.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
}

android {
    namespace = "io.github.lyzio.ajnav.feature.trafficse"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
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
    api(project(":core:model"))
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.ktor.client.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
