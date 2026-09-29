// Local LLM Phase 0 evaluation harness (docs/LLM_PHASE0.md).
//
// A separate application (its own applicationId), never a dependency of :app, never in
// the release AAB, included in the build only with -PllmBench=true (settings.gradle.kts).
// It exists to measure candidate models on the S20; nothing here ships.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.cragcoffee.memoripple.llmbench"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "io.github.cragcoffee.memoripple.llmbench"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-phase0"

        ndk {
            // arm64 only: the S20 (Exynos 990, A77/A55) and the arm64 emulator image.
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_PLATFORM=android-29",
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release",
                    // llama.cpp: static ggml/llama folded into one libllmbench.so; no
                    // tools, examples, tests, server, curl, OpenSSL, common.
                    "-DBUILD_SHARED_LIBS=OFF",
                    // llama-common is built for one thing: its Jinja chat-template renderer
                    // (common_chat_templates_apply with enable_thinking=false). The legacy
                    // llama_chat_apply_template knows no Gemma 4 turn format.
                    "-DLLAMA_BUILD_COMMON=ON",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_TOOLS=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DLLAMA_BUILD_APP=OFF",
                    "-DLLAMA_OPENSSL=OFF",
                    // CPU baseline (plan §4): generic arm64 with runtime feature detection,
                    // no OpenMP, no llamafile kernels, no KleidiAI. Fastest-path options are
                    // a later, separately measured decision.
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_OPENMP=OFF",
                    "-DGGML_LLAMAFILE=OFF",
                    "-DGGML_CPU_KLEIDIAI=OFF",
                    // Compile-time arm64 feature set (docs/LLM_PHASE0.md §4): dotprod + fp16, which every
                    // target here has (SC-51A asimddp; the arm64 emulator on Apple silicon). No i8mm / SVE:
                    // the S20 lacks them. Without this line ggml is built baseline armv8-a — the first
                    // Gemma runs were taken that way (§10) and are marked so.
                    "-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16",
                    // A/B switch (docs/LLM_PHASE0.md §10.2): -PllmRepack=false builds without ggml's runtime
                    // weight repacking (the ≈ 1.45 GB extra native buffer seen with Gemma). Default ON.
                    "-DGGML_CPU_REPACK=" + (if (project.findProperty("llmRepack") == "false") "OFF" else "ON"),
                )
                cppFlags += listOf("-O3")
                cFlags += listOf("-O3")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Non-debuggable build for the S20 numbers (ART/JIT and hidden-API checks differ in a
            // debuggable process). Signed with the debug key: this app is never distributed.
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            // Measurements are meaningless with a debuggable, unoptimised native build only
            // if the native side were affected; it is not (CMAKE_BUILD_TYPE=Release above).
            // The Kotlin side is thin. Still, the S20 runs are done on the release build.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        jniLibs {
            useLegacyPackaging = false
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
    implementation("androidx.activity:activity-ktx:1.12.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
