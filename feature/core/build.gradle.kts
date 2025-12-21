plugins {
    id("java-library")
    alias(libs.plugins.jetbrains.kotlin.jvm)
}
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
}

dependencies {
    val ex_group = "org.jetbrains.kotlin"
    val ex_module = "kotlin-stdlib-jdk8"
    implementation(libs.ktor.serialization.kotlinx.json) {exclude(group = ex_group, ex_module)}
}