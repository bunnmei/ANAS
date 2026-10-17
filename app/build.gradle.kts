import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "space.webkombinat.anas"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "space.webkombinat.anas"
        minSdk = 28
        targetSdk = 37
        // リリースのたびに 1 以上増やす
        versionCode = 1
        // CI (GitHub Actions) ではタグ名 (v1.2.0 → 1.2.0) を使う
        versionName = System.getenv("VERSION_NAME") ?: "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // CI では Secrets から復元したキーストアを環境変数で渡す
        System.getenv("ANDROID_KEYSTORE_PATH")?.let { path ->
            create("release") {
                storeFile = file(path)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            // packageScope で Kotlin 標準ライブラリだけを最適化すると、Ktor が継承するクラスまで
            // package-private にされて IllegalAccessError で落ちるので、アプリ全体を最適化する
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
            excludes += "META-INF/*.kotlin_module"
        }
    }
}

/**
 * web/ の TypeScript（NAS の Web UI と拡張用 SDK）を npm でビルドして、APK の assets/web に入れる。
 * Node.js が必要（CI では actions/setup-node で入れる）。
 */
abstract class WebBuildTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val webDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun build() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val web = webDir.get().asFile
        if (!web.resolve("node_modules").exists()) {
            exec.exec { workingDir = web; commandLine("npm", "ci", "--no-audit", "--no-fund") }
        }
        exec.exec { workingDir = web; commandLine("node", "build.mjs", out.resolve("web").absolutePath) }
    }
}

val webBuild = tasks.register<WebBuildTask>("webBuild") {
    val web = rootProject.layout.projectDirectory.dir("web")
    webDir.set(web)
    sources.from(
        web.dir("src"), web.dir("static"), web.file("build.mjs"), web.file("package.json"),
        web.file("tsconfig.json"),
        rootProject.layout.projectDirectory.dir("spec"),
    )
    outputDir.set(layout.buildDirectory.dir("generated/webAssets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(webBuild, WebBuildTask::outputDir)
    }
}

dependencies {
    implementation(project(":core:storage"))
    implementation(project(":feature:server"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.slf4j.nop)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
