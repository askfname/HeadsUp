import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val signingPropertiesFile = rootProject.file("keystore.properties")
val signingProperties = Properties().apply {
    if (signingPropertiesFile.isFile) {
        signingPropertiesFile.inputStream().use { load(it) }
    }
}
fun signingValue(name: String): String? =
    providers.gradleProperty(name).orNull ?: System.getenv(name) ?: signingProperties.getProperty(name)

val storeFilePath = signingValue("HEADSUP_STORE_FILE")
val releaseStoreFile = storeFilePath?.let { rootProject.file(it) } ?: rootProject.file("../headsup.jks")
val releaseStorePassword = signingValue("HEADSUP_STORE_PASSWORD")
val releaseKeyAlias = signingValue("HEADSUP_KEY_ALIAS")
val releaseKeyPassword = signingValue("HEADSUP_KEY_PASSWORD")
val releaseSigningConfigured = releaseStoreFile.isFile &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

if (releaseSigningConfigured) {
    android.signingConfigs.create("release") {
        storeFile = releaseStoreFile
        storePassword = releaseStorePassword
        keyAlias = releaseKeyAlias
        keyPassword = releaseKeyPassword
    }
}

android {
    namespace = "com.playlab.headsup"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.playlab.headsup"
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "2.1"
    }

    buildTypes {
        release {
            // R8 混淆+裁剪：去未用代码（含 icons-extended 冗余）与资源，降包体积与内存
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

val validateReleaseSigning = tasks.register("validateReleaseSigning") {
    doLast {
        check(releaseSigningConfigured) {
            "Release signing is not configured. Set HEADSUP_STORE_PASSWORD, HEADSUP_KEY_ALIAS, and HEADSUP_KEY_PASSWORD in the ignored keystore.properties file or Gradle/environment properties."
        }
    }
}
tasks.configureEach {
    if (name == "packageRelease" || name == "bundleRelease") {
        dependsOn(validateReleaseSigning)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
}
