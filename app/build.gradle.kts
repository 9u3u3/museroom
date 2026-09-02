import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
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
    compileSdk = 35

    defaultConfig {
        applicationId = "com.museroom.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.5-listentoo"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SUPABASE_URL", "\"${env("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${env("SUPABASE_ANON_KEY")}\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${env("GOOGLE_WEB_CLIENT_ID")}\"")
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
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
