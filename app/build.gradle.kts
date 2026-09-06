import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

// Credentials live in .env.local, which is gitignored. Swapping Supabase
// projects, or regions, is one edit to that file and a rebuild.
val localEnv = Properties().apply {
    val f = rootProject.file(".env.local")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun env(key: String): String = (localEnv.getProperty(key) ?: "").trim()

android {
    namespace = "com.museroom.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.museroom.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 33
        versionName = "5.9.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SUPABASE_URL", "\"${env("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${env("SUPABASE_ANON_KEY")}\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${env("GOOGLE_WEB_CLIENT_ID")}\"")
        // Turns a song title into an exact YouTube Music link, which is what
        // makes following somebody automatic instead of a search and a tap.
        buildConfigField("String", "YOUTUBE_API_KEY", "\"${env("YOUTUBE_API_KEY")}\"")
    }

    signingConfigs {
        // Public builds are signed with a real key rather than the shared debug
        // one. The password lives in .env.local, which never enters the repo.
        create("release") {
            val ks = rootProject.file("keystore/museroom-release.jks")
            if (ks.exists() && env("RELEASE_STORE_PASSWORD").isNotBlank()) {
                storeFile = ks
                storePassword = env("RELEASE_STORE_PASSWORD")
                keyAlias = env("RELEASE_KEY_ALIAS").ifBlank { "museroom" }
                keyPassword = env("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // Java 21 because the extraction library is compiled to it. From AGP 9 the
    // Android plugin brings Kotlin itself, so this lives here rather than in a
    // separate plugin's block.
    kotlin {
        jvmToolchain(21)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.okhttp)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.innertubex)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.google.id)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
