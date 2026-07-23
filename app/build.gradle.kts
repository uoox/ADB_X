plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "top.cbug.adbx"
    compileSdk = 36

    defaultConfig {
        applicationId = "top.cbug.adbx"
        minSdk = 30
        targetSdk = 36
        versionCode = 3
        versionName = "1.2.0"
    }

    signingConfigs {
        create("release") {
            val propsFile = rootProject.file("keystore.properties")
            if (propsFile.exists()) {
                val lines = propsFile.readLines()
                val map = lines.filter { it.contains("=") }.associate {
                    val (k, v) = it.split("=", limit = 2)
                    k.trim() to v.trim()
                }
                storeFile = rootProject.file(map.getOrDefault("storeFile", "adb_x.jks"))
                storePassword = map.getOrDefault("storePassword", "")
                keyAlias = map.getOrDefault("keyAlias", "")
                keyPassword = map.getOrDefault("keyPassword", "")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    buildFeatures {
        viewBinding = true
    }

    // The modern Xposed entry point lives in META-INF/xposed/*. Merge those
    // resource files (instead of letting the packager pick a single one) so
    // java_init.list, scope.list and module.prop all survive into the APK.
    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }
}

dependencies {
    // libxposed (modern Xposed API). Provided by the framework at runtime,
    // never bundled into the APK — hence compileOnly.
    compileOnly("io.github.libxposed:api:102.0.0")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.preference:preference-ktx:1.2.1")

    testImplementation("junit:junit:4.13.2")
}


tasks.withType<Test> {
    useJUnit()
}
