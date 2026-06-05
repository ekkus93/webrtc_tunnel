plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.phillipchin.webrtctunnel"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.phillipchin.webrtctunnel"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
        testInstrumentationRunner = "com.phillipchin.webrtctunnel.TestTunnelRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.icons)
    implementation(libs.google.material)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

val cargoExecutable = if (System.getProperty("os.name").startsWith("Windows")) "cargo.exe" else "cargo"

tasks.register<Exec>("buildRustAndroid") {
    group = "build"
    description = "Builds p2p-mobile for Android ABIs and copies .so files into jniLibs."
    workingDir = rootDir.parentFile
    doFirst {
        val hasCargoNdk = try {
            val result = exec {
                commandLine(cargoExecutable, "ndk", "--version")
                isIgnoreExitValue = true
            }
            result.exitValue == 0
        } catch (_: Exception) {
            false
        }
        if (!hasCargoNdk) {
            throw GradleException("cargo-ndk is required. Install with: cargo install cargo-ndk")
        }
    }
    commandLine(
        cargoExecutable,
        "ndk",
        "-t", "arm64-v8a",
        "-t", "x86_64",
        "-o", "android/app/src/main/jniLibs",
        "build",
        "-p", "p2p-mobile",
        "--release",
    )
}

tasks.register("verifyRustJniLibs") {
    group = "verification"
    description = "Ensures required Rust JNI libraries exist before packaging."
    dependsOn("buildRustAndroid")
    doLast {
        val libsDir = file("src/main/jniLibs")
        val required = listOf(
            file("${libsDir.path}/arm64-v8a/libp2p_mobile.so"),
            file("${libsDir.path}/x86_64/libp2p_mobile.so"),
        )
        required.forEach { lib ->
            if (!lib.exists()) {
                throw GradleException("Missing JNI library: ${lib.path}")
            }
        }
    }
}

tasks.named("preBuild") {
    dependsOn("verifyRustJniLibs")
}
