import java.util.Properties
import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val developerConfig = Properties().apply {
    val config = rootProject.file("jetmeal.local.properties")
    if (config.exists()) config.inputStream().use { load(it) }
}
val configuredClientKey = developerConfig.getProperty("supabase.publishableKey", "")
if (configuredClientKey.isNotBlank() && !configuredClientKey.startsWith("sb_publishable_")) {
    val payload = runCatching {
        String(Base64.getUrlDecoder().decode(configuredClientKey.split('.')[1]))
    }.getOrDefault("")
    require(Regex("\"role\"\\s*:\\s*\"anon\"").containsMatchIn(payload)) {
        "Supabase configuration accepts only publishable keys or legacy anon keys. Privileged keys must never be packaged."
    }
}
fun clientConfig(name: String): String = "\"" + developerConfig.getProperty(name, "")
    .replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.kxsxlxv.jetmeal"
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId = "com.kxsxlxv.jetmeal"
        minSdk = 35
        targetSdk = 37
        versionCode = 2
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SUPABASE_URL", clientConfig("supabase.url"))
        buildConfigField("String", "SUPABASE_KEY", clientConfig("supabase.publishableKey"))
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
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
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.compose)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.ktor.okhttp)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
