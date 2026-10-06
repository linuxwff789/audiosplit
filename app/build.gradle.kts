plugins {
    id("com.android.application")
}

val gitSha = System.getenv("GIT_SHA")?.take(7) ?: "local"

android {
    namespace = "com.linuxwff789.audiosplit"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.linuxwff789.audiosplit"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-git.$gitSha"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    lint {
        abortOnError = false
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}
