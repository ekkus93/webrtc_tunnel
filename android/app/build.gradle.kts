import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

val androidVersionProperties =
    Properties().apply {
        val source = rootProject.file("version.properties")
        if (!source.isFile) {
            throw GradleException("Missing android/version.properties")
        }
        source.inputStream().use(::load)
    }

fun requiredVersionProperty(name: String): String =
    androidVersionProperties.getProperty(name)?.trim()?.takeIf(String::isNotEmpty)
        ?: throw GradleException("Missing Android version property: $name")

val appVersionCode =
    requiredVersionProperty("versionCode").toIntOrNull()?.takeIf { it > 0 }
        ?: throw GradleException("versionCode must be a positive integer")
val appVersionName = requiredVersionProperty("versionName")
val productionRelease = providers.gradleProperty("productionRelease").map(String::toBoolean).orElse(false)

fun requiredReleaseEnvironment(name: String): String =
    providers.environmentVariable(name).orNull?.takeIf(String::isNotBlank)
        ?: throw GradleException("Missing required production signing input: $name")

fun normalizedSha256(value: String): String {
    val normalized = value.replace(Regex("[:\\s]"), "").lowercase()
    if (!normalized.matches(Regex("[0-9a-f]{64}"))) {
        throw GradleException("Production certificate fingerprint must contain exactly 64 hexadecimal digits")
    }
    return normalized
}

val productionKeystoreFile =
    if (productionRelease.get()) {
        file(requiredReleaseEnvironment("ANDROID_RELEASE_KEYSTORE_PATH"))
    } else {
        null
    }
val productionStorePassword =
    if (productionRelease.get()) requiredReleaseEnvironment("ANDROID_RELEASE_STORE_PASSWORD") else null
val productionKeyAlias =
    if (productionRelease.get()) requiredReleaseEnvironment("ANDROID_RELEASE_KEY_ALIAS") else null
val productionKeyPassword =
    if (productionRelease.get()) requiredReleaseEnvironment("ANDROID_RELEASE_KEY_PASSWORD") else null
val productionCertificateSha256 =
    if (productionRelease.get()) {
        normalizedSha256(
            providers.gradleProperty("releaseCertificateSha256").orNull
                ?: throw GradleException("Missing -PreleaseCertificateSha256 for a production release build"),
        )
    } else {
        null
    }

android {
    namespace = "com.phillipchin.webrtctunnel"
    compileSdk = 35

    signingConfigs {
        if (productionRelease.get()) {
            create("productionRelease") {
                storeFile = productionKeystoreFile
                storePassword = productionStorePassword
                keyAlias = productionKeyAlias
                keyPassword = productionKeyPassword
                storeType = "PKCS12"
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    defaultConfig {
        applicationId = "com.phillipchin.webrtctunnel"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "com.phillipchin.webrtctunnel.TestTunnelRunner"
    }

    buildTypes {
        getByName("release") {
            isDebuggable = false
            if (productionRelease.get()) {
                signingConfig = signingConfigs.getByName("productionRelease")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // FIX6 P2-003 (Q7): Android lint's CheckResult detector flags an ignored Kotlin/suspend
        // Result. Promote it from warning to a build-failing error so a discarded authoritative
        // @CheckResult mutation result fails CI. The detector's own behavior on an ignored call
        // is proven permanently by CheckResultEnforcementFixtureTest (FIX8 P2-001-A), which runs
        // the real detector against a standalone fixture (a TestLintTask run is independent of
        // this module's build config, so it observes the detector's own default Warning severity
        // rather than this override).
        error += "CheckResult"
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
        // Robolectric's ContentResolver file-Uri output-stream shadow reflects into
        // java.io.FileDescriptor#fd, which JDK17's module system blocks by default.
        unitTests.all {
            it.jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED")
        }
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
    testImplementation(libs.lint.tests)
    testImplementation(libs.lint.checks)

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
        val hasCargoNdk =
            try {
                val result =
                    exec {
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

// `-PskipRustBuild=true` lets local Kotlin-only workflows (unit tests, lint) run
// without cargo-ndk / a Rust rebuild. Packaging still verifies native libs exist
// (see requireRustJniLibs) so an APK is never produced without them.
val skipRustBuild = (project.findProperty("skipRustBuild") as String?)?.toBoolean() == true

fun requiredJniLibs(): List<File> {
    val libsDir = file("src/main/jniLibs")
    return listOf(
        file("${libsDir.path}/arm64-v8a/libp2p_mobile.so"),
        file("${libsDir.path}/x86_64/libp2p_mobile.so"),
    )
}

tasks.register("verifyRustJniLibs") {
    group = "verification"
    description = "Builds and ensures required Rust JNI libraries exist before packaging."
    dependsOn("buildRustAndroid")
    doLast {
        requiredJniLibs().forEach { lib ->
            if (!lib.exists()) {
                throw GradleException("Missing JNI library: ${lib.path}")
            }
        }
    }
}

tasks.register("requireRustJniLibs") {
    group = "verification"
    description = "Fails packaging if native libraries are missing (no build; used with -PskipRustBuild)."
    doLast {
        requiredJniLibs().forEach { lib ->
            if (!lib.exists()) {
                throw GradleException(
                    "Missing JNI library: ${lib.path}. Build it with ./gradlew buildRustAndroid " +
                        "or omit -PskipRustBuild.",
                )
            }
        }
    }
}

val validateProductionReleaseSigning =
    tasks.register("validateProductionReleaseSigning") {
        group = "verification"
        description = "Validates the production PKCS#12 key, alias, passwords, permissions, and pinned certificate."
        doLast {
            if (!productionRelease.get()) {
                throw GradleException("validateProductionReleaseSigning requires -PproductionRelease=true")
            }
            val keystoreFile = productionKeystoreFile ?: throw GradleException("Production keystore is unavailable")
            if (!keystoreFile.isFile) {
                throw GradleException("Production keystore is missing or not a regular file")
            }
            try {
                val permissions = Files.getPosixFilePermissions(keystoreFile.toPath())
                val unsafe =
                    permissions.any {
                        it in
                            setOf(
                                PosixFilePermission.GROUP_READ,
                                PosixFilePermission.GROUP_WRITE,
                                PosixFilePermission.GROUP_EXECUTE,
                                PosixFilePermission.OTHERS_READ,
                                PosixFilePermission.OTHERS_WRITE,
                                PosixFilePermission.OTHERS_EXECUTE,
                            )
                    }
                if (unsafe) {
                    throw GradleException("Production keystore permissions grant group/other access")
                }
            } catch (_: UnsupportedOperationException) {
                // Non-POSIX local filesystems cannot expose these bits. Production CI runs on Linux
                // and separately creates/chmods the temporary key file with mode 0600.
            }

            val storePasswordChars = productionStorePassword?.toCharArray() ?: charArrayOf()
            val keyPasswordChars = productionKeyPassword?.toCharArray() ?: charArrayOf()
            try {
                val keyStore = KeyStore.getInstance("PKCS12")
                try {
                    keystoreFile.inputStream().use { keyStore.load(it, storePasswordChars) }
                    val alias = productionKeyAlias ?: throw GradleException("Production key alias is unavailable")
                    if (!keyStore.isKeyEntry(alias)) {
                        throw GradleException("Production signing alias is not a private-key entry")
                    }
                    val key = keyStore.getKey(alias, keyPasswordChars)
                    if (key !is PrivateKey) {
                        throw GradleException("Production signing alias does not resolve to a private key")
                    }
                    val certificate =
                        keyStore.getCertificate(alias)
                            ?: throw GradleException("Production signing certificate is unavailable")
                    val actualFingerprint =
                        MessageDigest.getInstance("SHA-256")
                            .digest(certificate.encoded)
                            .joinToString(separator = "") { byte -> "%02x".format(byte) }
                    if (actualFingerprint != productionCertificateSha256) {
                        throw GradleException("Production signing certificate does not match the pinned fingerprint")
                    }
                } catch (error: GradleException) {
                    throw error
                } catch (_: Exception) {
                    throw GradleException("Production release keystore validation failed")
                }
            } finally {
                storePasswordChars.fill('\u0000')
                keyPasswordChars.fill('\u0000')
            }
        }
    }

tasks.named("preBuild") {
    // Skipped for local lint/unit-test cycles via -PskipRustBuild=true; on by default.
    if (!skipRustBuild) {
        dependsOn("verifyRustJniLibs")
    }
}

if (productionRelease.get()) {
    tasks.named("preReleaseBuild") {
        dependsOn(validateProductionReleaseSigning)
    }
}

// Packaging always verifies native libraries are present, even with -PskipRustBuild,
// so an APK/AAB is never assembled without them.
tasks.configureEach {
    if (name == "packageDebug" || name == "packageRelease") {
        dependsOn("requireRustJniLibs")
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("detekt.yml"))
}

// Run detekt *with type resolution* as part of `check`. The umbrella tasks cover
// production (detektMain), unit tests (detektTest) and instrumentation tests
// (detektDebugAndroidTest) across all variants, enabling the rules that need type
// resolution (InjectDispatcher, UseOrEmpty, ...) that the plain `detekt` task
// cannot evaluate.
tasks.named("check") {
    dependsOn("detektMain", "detektTest", "detektDebugAndroidTest")
}
