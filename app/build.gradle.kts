plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.liuml.apptimelimiter"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.liuml.apptimelimiter"
        minSdk = 27
        targetSdk = 35
        versionCode = 52
        versionName = "0.11.13"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("legacyMigration") {
            dimension = "distribution"
            versionCode = 52
            versionName = "0.11.13"
            buildConfigField("boolean", "MODERN_XPOSED_ENABLED", "false")
            buildConfigField("boolean", "LEGACY_MIGRATION_EXPORT_ENABLED", "true")
        }
        create("modern") {
            dimension = "distribution"
            versionCode = 53
            versionName = "0.11.14"
            buildConfigField("boolean", "MODERN_XPOSED_ENABLED", "true")
            buildConfigField("boolean", "LEGACY_MIGRATION_EXPORT_ENABLED", "false")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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

    buildFeatures {
        aidl = true
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    // The app switches locales at runtime. Keep every supported language in each bundle install.
    bundle {
        language {
            enableSplit = false
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.biometric:biometric:1.1.0")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("io.github.libxposed:service:102.0.0")
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    compileOnly(project(":xposed-stubs"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}

tasks.register<Copy>("stageTransitionDebugApks") {
    dependsOn("assembleLegacyMigrationDebug", "assembleModernDebug")
    into(layout.buildDirectory.dir("outputs/apk/transition"))
    from(layout.buildDirectory.file("outputs/apk/legacyMigration/debug/app-legacyMigration-debug.apk")) {
        rename { "app-time-limiter-v0.11.13-migration.apk" }
    }
    from(layout.buildDirectory.file("outputs/apk/modern/debug/app-modern-debug.apk")) {
        rename { "app-time-limiter-v0.11.14-modern.apk" }
    }
}
