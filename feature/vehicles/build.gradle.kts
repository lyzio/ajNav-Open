import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Car profiles (design §4.3 C). Pure Kotlin so the community can add cars without Android code.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(project(":core:model"))
    testImplementation(libs.junit)
}
