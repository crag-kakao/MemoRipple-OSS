pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MemoRipple"
include(":app")
include(":baselineprofile")

// Local LLM Phase 0 (docs/LLM_PHASE0.md): the evaluation harness is a separate,
// non-shipping application module. It joins the build only when asked for
// explicitly (-PllmBench=true), so an ordinary sync, test, assemble or bundle never
// configures llama.cpp, never compiles native code, and never sees the bench app.
if (providers.gradleProperty("llmBench").orNull == "true") {
    include(":llmbench")
}
