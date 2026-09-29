import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("androidx.baselineprofile")
}

// Release signing (B05): the upload key lives outside the repository and reaches Gradle
// only through the environment. All four values present -> the "upload" signing config
// is created and wired to release; anything less -> release builds stay unsigned, which
// is the standing validation contract. There is deliberately no fallback to the debug
// key: a release artifact is signed with the upload key or it is not signed at all.
// Secrets never appear in this file, in repository properties, or in build output.
val uploadCredentialNames = listOf(
    "MEMORIPPLE_UPLOAD_STORE_FILE",
    "MEMORIPPLE_UPLOAD_STORE_PASSWORD",
    "MEMORIPPLE_UPLOAD_KEY_ALIAS",
    "MEMORIPPLE_UPLOAD_KEY_PASSWORD",
)
val uploadCredentials = uploadCredentialNames.associateWith { System.getenv(it) }
val uploadCredentialsComplete = uploadCredentials.values.all { !it.isNullOrBlank() }
if (!uploadCredentialsComplete && uploadCredentials.values.any { !it.isNullOrBlank() }) {
    // Named by key, never by value: enough to fix the environment, nothing to leak.
    val missing = uploadCredentials.filterValues { it.isNullOrBlank() }.keys
    logger.warn(
        "Release signing credentials are incomplete (missing: ${missing.joinToString()}); " +
            "release builds stay unsigned.",
    )
}

android {
    namespace = "io.github.cragcoffee.memoripple"
    compileSdk = 36
    // Local LLM runtime (docs/LOCAL_LLM_RUNTIME.md): the pinned llama.cpp submodule compiled into
    // libmemoripple_llm.so. arm64-v8a only, CPU only. No model file is ever bundled.
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "io.github.cragcoffee.memoripple"
        minSdk = 29
        targetSdk = 36

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_PLATFORM=android-29",
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DBUILD_SHARED_LIBS=OFF",
                    "-DLLAMA_BUILD_COMMON=ON",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_TOOLS=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DLLAMA_BUILD_APP=OFF",
                    "-DLLAMA_OPENSSL=OFF",
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_OPENMP=OFF",
                    "-DGGML_LLAMAFILE=OFF",
                    "-DGGML_CPU_KLEIDIAI=OFF",
                    // the S20 (and every ARMv8.2 phone) has dotprod + fp16; LlamaCppEngine refuses older CPUs
                    "-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16",
                    "-DGGML_CPU_REPACK=ON",
                )
                cppFlags += listOf("-O3")
                cFlags += listOf("-O3")
            }
        }
        versionCode = 5
        versionName = "1.1.0"

        testInstrumentationRunner = "io.github.cragcoffee.memoripple.MemoRippleTestRunner"
    }

    if (uploadCredentialsComplete) {
        signingConfigs.create("upload") {
            storeFile = file(uploadCredentials.getValue("MEMORIPPLE_UPLOAD_STORE_FILE")!!)
            storePassword = uploadCredentials.getValue("MEMORIPPLE_UPLOAD_STORE_PASSWORD")
            keyAlias = uploadCredentials.getValue("MEMORIPPLE_UPLOAD_KEY_ALIAS")
            keyPassword = uploadCredentials.getValue("MEMORIPPLE_UPLOAD_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            // R8 on: without shrinking, the release DEX carried every unused icon and Play
            // Services class — a ~70MB artifact for a 30k-line app. The libraries in use
            // (Room, Compose, GMS, kotlinx-serialization) all ship their own consumer keep
            // rules; project-specific additions belong in proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Upload key or nothing: with the full credential set the release is signed
            // for Play upload; without it the artifact stays unsigned, and the debug key
            // is never a substitute.
            if (uploadCredentialsComplete) {
                signingConfig = signingConfigs.getByName("upload")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.activity:activity-compose:1.12.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.6")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    // Installs the committed baseline profile at first run, so the journeys the
    // :baselineprofile module recorded start ahead-of-time compiled.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    baselineProfile(project(":baselineprofile"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    // Seed-colour Material3 scheme generation (テーマカラー/パレットスタイル). The platform offers
    // only wallpaper-based dynamicColorScheme(); no AOSP/Compose API builds a scheme from a chosen
    // seed and variant, so the Material Color Utilities port is required.
    implementation("com.materialkolor:material-kolor:4.0.5")
    implementation("com.google.android.gms:play-services-auth:21.6.0")

    implementation(platform("androidx.compose:compose-bom:2025.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    testImplementation("junit:junit:4.13.2")
    // Phase 2 (docs/AI_RESOURCE_CONTROLLER.md): the resource controller's keep-warm / idle-unload
    // tests advance a virtual clock (runTest / advanceTimeBy) instead of sleeping through real
    // timeouts — the standard test scheduler for the coroutines already on the classpath (1.9.0).
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

val releaseRuntimeInventoryFile = file(
    "src/main/assets/oss/release-runtime-components.txt",
)

fun resolvedReleaseRuntimeInventory(): String {
    val result = configurations.getByName("releaseRuntimeClasspath")
        .incoming.resolutionResult
    val directComponentIds = result.root.dependencies
        .filterIsInstance<ResolvedDependencyResult>()
        .map { it.selected.id.displayName }
        .toSet()
    val lines = result.allComponents.mapNotNull { component ->
        val id = component.id as? ModuleComponentIdentifier ?: return@mapNotNull null
        val kind = if (id.displayName in directComponentIds) "DIRECT" else "TRANSITIVE"
        "$kind|${id.group}:${id.module}:${id.version}"
    }.sortedWith(
        compareBy<String> { it.substringAfter('|') }
            .thenBy { it.substringBefore('|') },
    )
    return buildString {
        appendLine("# Generated from :app:releaseRuntimeClasspath. Do not edit manually.")
        lines.forEach(::appendLine)
    }
}

tasks.register("updateReleaseOssInventory") {
    group = "verification"
    description = "Explicitly updates the reviewed release runtime OSS component inventory."
    doLast {
        releaseRuntimeInventoryFile.parentFile.mkdirs()
        releaseRuntimeInventoryFile.writeText(resolvedReleaseRuntimeInventory())
    }
}

tasks.register("verifyReleaseSigning") {
    group = "verification"
    description = "Fails unless the release build is wired to the upload signing key."
    // Captured as booleans at configuration time: the task never touches, prints, or
    // stores a credential value or the keystore path.
    val complete = uploadCredentialsComplete
    val missingNames = uploadCredentialNames.filter { System.getenv(it).isNullOrBlank() }
    val storeFilePresent = uploadCredentials["MEMORIPPLE_UPLOAD_STORE_FILE"]
        ?.takeIf { it.isNotBlank() }?.let { file(it).exists() } ?: false
    doLast {
        check(complete) {
            "Release signing credentials are incomplete (missing: " +
                "${missingNames.joinToString()}). Export the full " +
                "MEMORIPPLE_UPLOAD_* set in the environment; release builds are " +
                "unsigned until then."
        }
        check(storeFilePresent) {
            "The upload keystore file named by MEMORIPPLE_UPLOAD_STORE_FILE does not " +
                "exist. Check the environment; the path is not echoed here."
        }
        println("Release signing is configured with the upload key.")
    }
}

tasks.register("verifyReleaseOssInventory") {
    group = "verification"
    description = "Fails when release runtime dependencies drift from the reviewed OSS inventory."
    inputs.file(releaseRuntimeInventoryFile)
    doLast {
        check(releaseRuntimeInventoryFile.readText() == resolvedReleaseRuntimeInventory()) {
            "Release runtime dependencies changed. Run :app:updateReleaseOssInventory, " +
                "regenerate OSS assets, and review license provenance before committing."
        }
    }
}
