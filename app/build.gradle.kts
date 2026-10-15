import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "studio.eidolon.camera"
    compileSdk = 37
    defaultConfig {
        applicationId = "studio.eidolon.camera"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-alpha.1"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }
    buildFeatures { compose = true }
    val signingFile = rootProject.file(".signing/release.properties")
    if (signingFile.exists()) {
        val signing = Properties().apply { signingFile.inputStream().use { load(it) } }
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(requireNotNull(signing.getProperty("storeFile")))
                storePassword = requireNotNull(signing.getProperty("storePassword"))
                keyAlias = requireNotNull(signing.getProperty("keyAlias"))
                keyPassword = requireNotNull(signing.getProperty("keyPassword"))
                storeType = "PKCS12"
            }
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            if (signingFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
