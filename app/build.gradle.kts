plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.xzdw.dylt"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.xzdw.dylt"
        minSdk = 35
        targetSdk = 37
        versionCode = 18
        versionName = "1.7.4"

        vectorDrawables.useSupportLibrary = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/versions/**"
        )
    }

    buildTypes {
        debug {
            applicationIdSuffix = ""
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3:1.5.0-alpha24")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    // Canonical smoothed-corner geometry shared by View/Canvas and Compose.
    implementation("androidx.graphics:graphics-shapes:1.1.0")

    // Liquid glass rendering core + capsule geometry, borrowed from the
    // AndroidLiquidGlass example project (Backdrop catalog, Apache-2.0).
    implementation("io.github.kyant0:backdrop:2.0.1")
    implementation("io.github.kyant0:shapes:1.2.1")

    // Navigation 3 owns the secondary-page back stack and predictive-back transition.
    implementation("androidx.navigation3:navigation3-runtime:1.1.4")
    implementation("androidx.navigationevent:navigationevent-compose:1.1.2")

    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-squircle-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-navigation3-ui-android:0.9.3")

    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")

    // Official libxposed module service. The AAR contributes the exported
    // XposedProvider that the framework calls to hand the module its service
    // binder, which is the framework-reported activation signal the manager UI
    // reads (see framework/ModuleActivation.kt).
    implementation("io.github.libxposed:service:102.0.0")

    // The libxposed module API is provided by the framework at runtime and is
    // only needed to compile the modern entry (META-INF/xposed/java_init.list).
    // Bundling it would duplicate classes the framework also injects.
    compileOnly("io.github.libxposed:api:102.0.0")
}
