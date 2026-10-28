plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.momiji.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.momiji.tv"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.3.0"
    }

    buildFeatures { buildConfig = true }
    sourceSets.getByName("main").java.srcDir("../../momiji-network-core/src/main/kotlin")
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.media3:media3-exoplayer:1.8.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.8.1")
    implementation("androidx.media3:media3-ui:1.8.1")
}
