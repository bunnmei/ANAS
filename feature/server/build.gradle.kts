// Ktor の HTTP サーバー。Android に依存しないので testApplication で丸ごとテストできる
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    api(project(":core:model"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.kotlinx.json)
    // 拡張機能の代わりに外部サイトへアクセスする（/x/<id>/api/fetch）。DNS の結果を検査できるので OkHttp を使う
    implementation(libs.okhttp)
    // ファイル検索の索引。SQLite を同梱するので、Android の版によらず FTS5（trigram）が使える。JVM のテストでも同じものが動く
    implementation(libs.androidx.sqlite.bundled)
    testImplementation(libs.junit)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
