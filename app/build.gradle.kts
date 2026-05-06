plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseStoreFilePath = providers.gradleProperty("RELEASE_STORE_FILE")
    .orElse(providers.environmentVariable("RELEASE_STORE_FILE"))
val releaseStorePassword = providers.gradleProperty("RELEASE_STORE_PASSWORD")
    .orElse(providers.environmentVariable("RELEASE_STORE_PASSWORD"))
val releaseKeyAlias = providers.gradleProperty("RELEASE_KEY_ALIAS")
    .orElse(providers.environmentVariable("RELEASE_KEY_ALIAS"))
val releaseKeyPassword = providers.gradleProperty("RELEASE_KEY_PASSWORD")
    .orElse(providers.environmentVariable("RELEASE_KEY_PASSWORD"))

android {
    namespace = "dev.igor.fytcustomservice"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.igor.fytcustomservice"
        minSdk = 29
        targetSdk = 33
        versionCode = 3
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += listOf("en")
    }

    signingConfigs {
        create("release") {
            val resolvedStoreFile = releaseStoreFilePath.orNull
            if (!resolvedStoreFile.isNullOrBlank()) {
                storeFile = file(resolvedStoreFile)
            }
            storePassword = releaseStorePassword.orNull
            keyAlias = releaseKeyAlias.orNull
            keyPassword = releaseKeyPassword.orNull
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    applicationVariants.all {
        if (buildType.name == "debug" || buildType.name == "release") {
            outputs.all {
                @Suppress("UNCHECKED_CAST")
                (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                    "FYTCustomService.apk"
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
