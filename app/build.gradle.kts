plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kyoto.osmandtriprpc"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kyoto.osmandtriprpc"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        buildConfig = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Vendored from net.osmand:android-aidl-lib:master-snapshot (official OsmAnd AIDL API).
    implementation(files("libs/osmand-aidl-lib.jar"))
    testImplementation("junit:junit:4.13.2")
}
