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

val versionMajor = 0
val versionMinor = 3
val versionPatch = 5
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
