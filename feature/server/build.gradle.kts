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

    implementation(project(":feature:core"))

    // *0を参照
    val ex_group = "org.jetbrains.kotlin"
    val ex_module = "kotlin-stdlib-jdk8"

    implementation(libs.ktor.server.core) {exclude(group = ex_group, ex_module)}
    //エンジン 色々ある nettyが一番普及してるらしい
    implementation(libs.ktor.server.netty) {exclude(group = ex_group, ex_module)}
    //HTML を Kotlin DSL で生成できる *1を参照
    implementation(libs.ktor.server.html.builder) {exclude(group = ex_group, ex_module)}
    // JSON シリアライザ ex: kotlinx.serialization ,gsonを使う場合に必要なよう
    implementation(libs.ktor.server.content.negotiation) {exclude(group = ex_group, ex_module)}
    implementation(libs.ktor.server.cors) {exclude(group = ex_group, ex_module)}
    implementation(libs.ktor.serialization.kotlinx.json) {exclude(group = ex_group, ex_module)}
}

// *0
// exclude(group = ex_group, ex_module)
// 重複依存 が発生しないようにするためらしい

// *1
// call.respondHtml {
//     head { title("My Server") }
//     body {
//         h1 { +"Hello" }
//     }
// }