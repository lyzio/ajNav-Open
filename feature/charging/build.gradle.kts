import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Charging stops (design §4.4, §4.6 B–C): Open Charge Map, price chain, offline cache,
// elevation and the charging planner. No Mapbox types, so the planner is unit tested on the JVM.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
}

android {
    namespace = "io.github.lyzio.ajnav.feature.charging"
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
    api(project(":feature:vehicles"))
    implementation(project(":core:security"))
    implementation(project(":feature:traffic-se"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)

    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)

    implementation(libs.ktor.client.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
