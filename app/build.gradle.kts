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
val projectUrl = providers.environmentVariable("SUPABASE_URL").orElse(developerConfig.getProperty("supabase.url", "")).get()
val configuredClientKey = providers.environmentVariable("SUPABASE_PUBLISHABLE_KEY")
    .orElse(developerConfig.getProperty("supabase.publishableKey", "")).get()
if (configuredClientKey.isNotBlank() && !configuredClientKey.startsWith("sb_publishable_")) {
    val payload = runCatching {
        String(Base64.getUrlDecoder().decode(configuredClientKey.split('.')[1]))
    }.getOrDefault("")
    require(Regex("\"role\"\\s*:\\s*\"anon\"").containsMatchIn(payload)) {
        "Supabase configuration accepts only publishable keys or legacy anon keys. Privileged keys must never be packaged."
    }
}
fun clientConfig(value: String): String = "\"" + value
    .replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val ciCode = providers.gradleProperty("ciVersionCode").orElse(providers.environmentVariable("JETMEAL_VERSION_CODE")).orNull
val ciName = providers.gradleProperty("ciVersionName").orElse(providers.environmentVariable("JETMEAL_VERSION_NAME")).orNull
require((ciCode == null) == (ciName == null)) { "Supply both CI versionCode and versionName." }
val appVersionCode = ciCode?.toIntOrNull()?.also {
    require(it in 4..2_100_000_000) { "CI versionCode must exceed local version 3 and fit Android's limit." }
} ?: if (ciCode == null) 3 else error("Invalid CI versionCode.")
val appVersionName = ciName?.also {
    require(Regex("1\\.1\\.[0-9]+").matches(it)) { "CI versionName must use 1.1.<build>." }
} ?: "1.1.0"

val signingValues = listOf("ANDROID_KEYSTORE_PATH", "ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD")
    .associateWith { providers.environmentVariable(it).orNull }
val hasReleaseSigning = signingValues.values.all { !it.isNullOrBlank() }
require(signingValues.values.all { it == null } || hasReleaseSigning) { "Release signing environment is incomplete." }
if (providers.gradleProperty("requireReleaseSigning").orNull == "true") {
    require(hasReleaseSigning) { "CI release requires all four release signing environment variables." }
    require(projectUrl.startsWith("https://") && configuredClientKey.isNotBlank()) {
        "CI release requires an HTTPS Supabase URL and a client-safe publishable/anon key."
    }
}

android {
    namespace = "com.kxsxlxv.jetmeal"
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId = "com.kxsxlxv.jetmeal"
        minSdk = 35
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SUPABASE_URL", clientConfig(projectUrl))
        buildConfigField("String", "SUPABASE_KEY", clientConfig(configuredClientKey))
    }

    signingConfigs {
        if (hasReleaseSigning) create("permanentRelease") {
            storeFile = file(requireNotNull(signingValues["ANDROID_KEYSTORE_PATH"]))
            require(storeFile!!.isFile) { "Release keystore file does not exist." }
            storePassword = signingValues["ANDROID_KEYSTORE_PASSWORD"]
            keyAlias = signingValues["ANDROID_KEY_ALIAS"]
            keyPassword = signingValues["ANDROID_KEY_PASSWORD"]
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("permanentRelease")
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
