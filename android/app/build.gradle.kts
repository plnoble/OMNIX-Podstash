import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun repoVersion(): Pair<String, Int> {
    val versionFile = rootProject.projectDir.resolve("../VERSION")
    val jsonFile = rootProject.projectDir.resolve("../version.json")
    var name = "0.1.0"
    var code = 1
    if (versionFile.exists()) {
        name = versionFile.readText().trim().ifEmpty { name }
    }
    if (jsonFile.exists()) {
        val text = jsonFile.readText()
        Regex(""""versionCode"\s*:\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { code = it }
        Regex(""""version"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1)?.let { name = it }
    }
    return name to code
}

val (verName, verCode) = repoVersion()

android {
    namespace = "com.omnix.podstash"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.omnix.podstash"
        minSdk = 26
        targetSdk = 35
        versionCode = verCode
        versionName = verName
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/omnix-upload.p12")
            storePassword = "omnix-podstash"
            keyAlias = "omnix"
            keyPassword = "omnix-podstash"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
