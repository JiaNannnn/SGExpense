plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.sgexpense"
    compileSdk = 35
    // The SDK's build-tools 34.0.0 is a Linux build (no .exe); use the Windows 36.1.0 install.
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.sgexpense"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // JaCoCo coverage for JVM unit tests → createDebugUnitTestCoverageReport (read by Sonar).
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so a release APK can be sideloaded without extra setup.
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
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// The project lives under a non-ASCII folder (E:\记账). Gradle's test worker can't load classes from
// such paths on Windows, so copy those classpath entries to an ASCII temp folder before tests run.
tasks.withType<Test>().configureEach {
    val asciiRoot = File(System.getProperty("java.io.tmpdir"), "sgexpense-test-cp")
    doFirst {
        fun File.nonAscii() = path.any { it.code > 127 }
        if (classpath.any { it.nonAscii() }) {
            asciiRoot.deleteRecursively()
            var i = 0
            classpath = files(classpath.map { f ->
                if (!f.nonAscii() || !f.exists()) f
                else File(asciiRoot, "${i++}-${f.name}").also { dest ->
                    if (f.isDirectory) f.copyRecursively(dest, overwrite = true) else f.copyTo(dest, overwrite = true)
                }
            })
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // On-device OCR (bundled Latin model, works offline)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Legacy .xls reader (UOB exports)
    implementation("net.sourceforge.jexcelapi:jxl:2.6.12") {
        exclude(group = "log4j")
    }

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.room:room-testing:$room")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
