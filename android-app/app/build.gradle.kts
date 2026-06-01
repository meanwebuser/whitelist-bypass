import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}


fun loadBrandDotEnv(file: File): Map<String, String> {
    if (!file.isFile) return emptyMap()
    return file.readLines().mapNotNull { raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) return@mapNotNull null
        val key = line.substringBefore("=").trim()
        val value = line.substringAfter("=").trim()
        key to value
    }.toMap()
}

fun brandEnvValue(dotEnv: Map<String, String>, vararg names: String): String? {
    for (name in names) {
        val fromProcess = System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }
        if (fromProcess != null) return fromProcess
        val fromFile = dotEnv[name]?.trim()?.takeIf { it.isNotEmpty() }
        if (fromFile != null) return fromFile
    }
    return null
}

val brandDotEnv = listOf(
    rootProject.file(".env"),
    rootProject.file("../.env")
).firstOrNull { it.isFile }?.let(::loadBrandDotEnv) ?: emptyMap()

val appBrandName = brandEnvValue(brandDotEnv, "APP_BRAND", "BRANDING", "BRAND_NAME", "APP_NAME") ?: "whitelistbypass"
val allowEmptyMobileSecrets = brandEnvValue(brandDotEnv, "ALLOW_EMPTY_MOBILE_SECRETS") == "1"
val allowNoAutoUpdate = brandEnvValue(brandDotEnv, "ALLOW_NO_AUTOUPDATE") == "1"
fun requiredMobileSecret(name: String, vararg aliases: String): String {
    val value = brandEnvValue(brandDotEnv, name, *aliases)
    if (!value.isNullOrBlank()) return value
    if (allowEmptyMobileSecrets) return ""
    throw GradleException("Missing required Android secret $name. Put it in android-app/.env or export it. Set ALLOW_EMPTY_MOBILE_SECRETS=1 only for intentionally empty/dev builds.")
}
fun requiredAndroidConfig(name: String, vararg aliases: String): String {
    val value = brandEnvValue(brandDotEnv, name, *aliases)
    if (!value.isNullOrBlank()) return value
    if (allowNoAutoUpdate) return ""
    throw GradleException("Missing required Android config $name. Put it in android-app/.env or export it. Set ALLOW_NO_AUTOUPDATE=1 only for intentionally disabled auto-updates.")
}
val wtbusKeyB64 = requiredMobileSecret("WTBUS_KEY_B64")
val wtbusKeyId = brandEnvValue(brandDotEnv, "WTBUS_KEY_ID") ?: "k1"
val vkBotToken = requiredMobileSecret("VK_BOT_TOKEN")
val vkBotPeerId = requiredMobileSecret("VK_BOT_PEER_ID")
val vkTelemetryPeerId = brandEnvValue(brandDotEnv, "VK_TELEMETRY_PEER_ID") ?: ""
val androidUpdateUrl = requiredAndroidConfig("ANDROID_UPDATE_URL", "UPDATE_URL", "APP_UPDATE_URL")

val versionMajor = 0
val versionMinor = 3
val versionPatch = 18
val versionBuild = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 0

android {
    namespace = "bypass.whitelist"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "bypass.whitelist"
        minSdk = 23
        targetSdk = 36
        versionCode = 1_000_000 * versionMajor + 1_000 * versionMinor + versionPatch + versionBuild
        versionName = "$versionMajor.$versionMinor.$versionPatch"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resValue("string", "app_name", appBrandName)
        manifestPlaceholders["appLabel"] = appBrandName
        manifestPlaceholders["tileLabel"] = appBrandName
        buildConfigField("String", "WTBUS_KEY_B64", "\"$wtbusKeyB64\"")
        buildConfigField("String", "WTBUS_KEY_ID", "\"$wtbusKeyId\"")
        buildConfigField("String", "VK_BOT_TOKEN", "\"$vkBotToken\"")
        buildConfigField("String", "VK_BOT_PEER_ID", "\"$vkBotPeerId\"")
        buildConfigField("String", "VK_TELEMETRY_PEER_ID", "\"$vkTelemetryPeerId\"")
        buildConfigField("String", "ANDROID_UPDATE_URL", "\"$androidUpdateUrl\"")
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("../debug.keystore")
            storePassword = "android"
            keyAlias = "debug"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
