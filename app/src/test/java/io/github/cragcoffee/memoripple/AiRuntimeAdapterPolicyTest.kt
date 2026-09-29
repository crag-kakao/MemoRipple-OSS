package io.github.cragcoffee.memoripple

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** RED 31–35 + policy: layering of the runtime adapter (docs/LOCAL_LLM_RUNTIME.md). */
class AiRuntimeAdapterPolicyTest {
    private fun dir(rel: String): File = sequenceOf(File(rel), File("app/$rel")).first { it.isDirectory }
    private fun sources(d: File): Map<String, String> = d.walkTopDown().filter { it.isFile && it.extension == "kt" }.associate { it.path to it.readText() }

    @Test
    fun theAiDomainKnowsNoEngineVendorOrAndroid() {
        val all = sources(dir("src/main/java/io/github/cragcoffee/memoripple/domain/ai"))
        assertTrue(all.isNotEmpty())
        all.forEach { (name, text) ->
            listOf("llama", "gguf", "GGUF", "Qwen", "qwen", "Ministral", "ministral", "Gemma", "JNI", "System.loadLibrary", "external fun", "import android.", "androidx.", "PowerManager", "android.content.Context", "llmbench").forEach { forbidden ->
                assertFalse("$name mentions $forbidden", text.contains(forbidden))
            }
        }
    }

    @Test
    fun theRuntimeAdapterKnowsNoDaoEntityRoomUiOrRoute() {
        val all = sources(dir("src/main/java/io/github/cragcoffee/memoripple/data/ai"))
        assertTrue(all.isNotEmpty())
        all.forEach { (name, text) ->
            listOf("Dao", "Entity", "androidx.room", "androidx.compose", "io.github.cragcoffee.memoripple.ui.", "Routes.", "NavController", "navigate(", "AppDatabase").forEach { forbidden ->
                assertFalse("$name mentions $forbidden", text.contains(forbidden))
            }
        }
        // the engine-facing facts (architecture, thinking switch) live here; the download facts moved to the Phase 5 catalog in domain/ai/models
        val joined = all.values.joinToString("\n")
        assertTrue(joined.contains("\"qwen3\"") && joined.contains("\"mistral3\""))
    }

    @Test
    fun noModelFileIsBundledAndTheProductLibraryIsTheOnlyNativeAddition() {
        val appDir = sequenceOf(File("."), File("app")).first { File(it, "build.gradle.kts").exists() && File(it, "src/main/AndroidManifest.xml").exists() }
        assertTrue(File(appDir, "src/main").walkTopDown().none { it.extension == "gguf" })
        assertFalse(File(appDir, "src/main/jniLibs").exists())
        val cmake = File(appDir, "src/main/cpp/CMakeLists.txt").readText()
        assertTrue(cmake.contains("memoripple_llm"))
        assertTrue("the pinned submodule is the one source of llama.cpp", cmake.contains("llmbench/src/main/cpp/llama.cpp"))
        val apk = File(appDir, "build/outputs/apk/debug/app-debug.apk")
        org.junit.Assume.assumeTrue("no :app APK built yet", apk.exists())
        java.util.zip.ZipFile(apk).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.toList()
            assertTrue("no GGUF in the APK", entries.none { it.endsWith(".gguf") })
            assertTrue("no bench library", entries.none { it.startsWith("lib/") && it.contains("llmbench") })
            val natives = entries.filter { it.startsWith("lib/") }.map { it.substringAfterLast('/') }.toSet()
            assertTrue("$natives", natives.none { it.contains("llama") || it.contains("ggml") || it.contains("llmbench") })
            val cpp = File(appDir, "src/main/cpp/memoripple_llm.cpp")
            if (apk.lastModified() > cpp.lastModified()) assertTrue("the product bridge is packaged: $natives", "libmemoripple_llm.so" in natives)
        }
    }

    @Test
    fun theChatUiKnowsNoRuntimeEngineOrModelFileAndThereIsNoAiRoomTable() {
        val chat = dir("src/main/java/io/github/cragcoffee/memoripple/ui/chat").listFiles { f -> f.extension == "kt" }!!.joinToString("\n") { it.readText() }
        listOf("domain.ai.runtime.LocalModelRuntime", "data.ai", "llama", "gguf", "ModelProfiles", "NativeInferenceEngine").forEach { assertFalse("chat mentions $it", chat.contains(it)) }
        val db = dir("src/main/java/io/github/cragcoffee/memoripple/data").listFiles { f -> f.name == "AppDatabase.kt" }!!.single().readText()
        assertTrue(db.contains("version = 29"))   // Phase 8: chat history tables (human-approved 2026-09-20); no AI runtime table
        listOf("ai_sessions", "ai_messages", "ai_drafts", "ai_results", "AiEntity", "ModelEntity").forEach { assertFalse(db.contains(it)) }
    }
}
