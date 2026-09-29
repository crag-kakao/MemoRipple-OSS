package io.github.cragcoffee.memoripple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Local LLM Phase 5 (docs/AI_MODEL_MANAGEMENT.md): models live only under the no-backup root and
 * never in the APK; the only persisted preference is the selected model id; no Room table, no new
 * permission, no WorkManager; the settings screen knows no engine, file format or path; the
 * developer path never reaches a release UI; the AI pipeline and the confirmed-write boundary
 * are untouched.
 */
class AiModelPolicyTest {
    private fun dir(path: String): File = File(path).let { if (it.isDirectory) it else File("app/$path") }
    private fun file(path: String): File = File(path).let { if (it.isFile) it else File("app/$path") }
    private fun sources(path: String): Map<String, String> = dir(path).walkTopDown().filter { it.extension == "kt" }.associate { it.name to it.readText() }

    @Test
    fun theSelectedModelIdIsTheOnlyModelPreferenceAndItIsAnIdNotAPath() {
        val settings = file("src/main/java/io/github/cragcoffee/memoripple/data/SettingsRepository.kt").readText()
        assertTrue(settings.contains("ai_selected_model_id"))
        val aiKeys = Regex("\"(ai_[a-z_]+)\"").findAll(settings).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("ai_selected_model_id"), aiKeys)
        listOf("gguf", "sha256", "model_path", "model_url", "developer").forEach { assertFalse("settings key mentions $it", settings.lowercase().contains(it)) }
        val data = sources("src/main/java/io/github/cragcoffee/memoripple/data").values.joinToString("\n")
        assertFalse("no DataStore for downloads / model metadata", Regex("preferencesDataStore\\(\\s*name = \"(ai|model|download)").containsMatchIn(data))
    }

    @Test
    fun noModelDownloadOrAiSettingsTableRoom24AndBackup18Stand() {
        val db = file("src/main/java/io/github/cragcoffee/memoripple/data/AppDatabase.kt").readText()
        assertTrue(db.contains("version = 29"))   // Phase 8: chat history tables; no model / download table
        listOf("models", "downloads", "ai_settings", "ModelEntity", "DownloadEntity").forEach { assertFalse("AppDatabase mentions $it", db.contains(it)) }
        assertTrue(file("src/main/java/io/github/cragcoffee/memoripple/backup/BackupDtos.kt").readText().contains("BACKUP_FORMAT_VERSION = 23"))
    }

    @Test
    fun modelsPartsAndMetadataAreOutsideEveryBackupAndNeverInTheApk() {
        val rules = file("src/main/res/xml/backup_rules.xml").readText()
        assertTrue(rules.contains("<exclude domain=\"file\" path=\".\" />") && rules.contains("<exclude domain=\"root\" path=\".\" />"))
        val manifest = file("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        val store = sources("src/main/java/io/github/cragcoffee/memoripple/data/ai/models").getValue("FileModelStore.kt")
        assertTrue("the store roots at the no-backup directory it is given, under models/", store.contains("ModelFileResolver.MODELS_DIR") || store.contains("\"models\""))
        assertFalse(store.contains("getExternalFilesDir") || store.contains("filesDir") || store.contains("cacheDir"))
        val app = file("src/main/java/io/github/cragcoffee/memoripple/MemoRippleApplication.kt").readText()
        assertTrue("the application hands the store noBackupFilesDir", Regex("FileModelStore\\(\\s*noBackupFilesDir").containsMatchIn(app))
        val bundled = dir("src/main").walkTopDown().filter { it.isFile && it.extension.lowercase() == "gguf" }.toList()
        assertTrue("no GGUF in the source tree: $bundled", bundled.isEmpty())
    }

    @Test
    fun noNewPermissionNoWorkManagerNoForegroundDownloadService() {
        val manifest = file("src/main/AndroidManifest.xml").readText()
        val permissions = Regex("uses-permission android:name=\"android\\.permission\\.([A-Z_]+)\"").findAll(manifest).map { it.groupValues[1] }.toSet()
        assertEquals(
            // ACCESS_NETWORK_STATE (normal, install-time): the metered-network confirmation cannot be answered without it (S20 smoke, 2026-09-20)
            setOf("INTERNET", "ACCESS_NETWORK_STATE", "SYSTEM_ALERT_WINDOW", "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_SHORT_SERVICE", "POST_NOTIFICATIONS", "FOREGROUND_SERVICE_MEDIA_PLAYBACK"),
            permissions,
        )
        assertFalse(manifest.contains("dataSync"))
        val gradle = file("build.gradle.kts").readText()
        listOf("androidx.work", "okhttp", "ktor", "retrofit").forEach { assertFalse("new dependency $it", gradle.contains(it)) }
    }

    @Test
    fun theSettingsScreenKnowsNoEngineFileFormatPathOrDeveloperInjection() {
        val screen = sources("src/main/java/io/github/cragcoffee/memoripple/ui/settings").getValue("AiModelsScreen.kt")
        listOf(
            "gguf", "GGUF", "llama", "JNI", "loadLibrary", "HttpURLConnection", "java.io.File", "File(", "noBackupFilesDir", "DeveloperPath", "developer.properties",
            "Dao", "Entity", "androidx.room", "Routes.", "navigate(", "NavController", "LocalModelRuntime", "ModelProfiles", "sha256", "expectedSha256",
        ).forEach { assertFalse("AiModelsScreen mentions $it", screen.contains(it)) }
        listOf("ModelManager", "InstallState", "DownloadRequest").forEach { assertTrue("AiModelsScreen uses $it", screen.contains(it)) }
        // no ranking words on the screen either
        listOf("おすすめ", "No.1", "最強", "推奨").forEach { assertFalse("ranking word $it", screen.contains(it)) }
        // the developer path stays a debug-only concern of the selection
        val assembly = sources("src/main/java/io/github/cragcoffee/memoripple/data/ai").getValue("AiAssembly.kt")
        assertTrue(assembly.contains("isDebugBuild"))
        val ui = sources("src/main/java/io/github/cragcoffee/memoripple/ui").values.joinToString("\n")
        assertFalse("no UI names the developer file", ui.contains("developer.properties") || ui.contains("DeveloperPath"))
    }

    @Test
    fun theModelDomainImportsNoPlatformAndTheManagerNeverDownloadsOrSelectsByItself() {
        val domain = sources("src/main/java/io/github/cragcoffee/memoripple/domain/ai/models")
        assertTrue(domain.keys.containsAll(listOf("CatalogEntry.kt", "ModelManager.kt")))
        domain.forEach { (name, text) ->
            listOf("import android", "androidx.", "io.github.cragcoffee.memoripple.data.", "HttpURLConnection", "MessageDigest", "Dao", "Entity", "DocumentAccess").forEach {
                assertFalse("$name mentions $it", text.contains(it))
            }
        }
        val manager = domain.getValue("ModelManager.kt")
        val initBlock = manager.substringAfter("init {", "").substringBefore("\n    }")
        assertFalse("init starts nothing", initBlock.contains("requestDownload") || initBlock.contains("select(") || initBlock.contains("launch"))
        val refresh = manager.substringAfter("suspend fun refresh(").substringBefore("\n    }")
        assertFalse("refresh downloads nothing and selects nothing", refresh.contains("downloader.") || refresh.contains("selection.set("))
        // the runtime is never loaded by the manager: only unloaded
        assertFalse(manager.contains(".load("))
        assertTrue(manager.contains("unloadRuntime"))
    }

    @Test
    fun thePipelineAndTheConfirmBoundaryAreUntouched() {
        val main = dir("src/main/java/io/github/cragcoffee/memoripple").walkTopDown().filter { it.extension == "kt" }.toList()
        val confirmCallers = main.filter { it.name != "ExecutionPolicy.kt" && it.readText().contains(".confirm(") }.map { it.name }
        assertEquals(listOf("AiOrchestrator.kt"), confirmCallers)
        val executors = main.filter { it.readText().let { t -> t.contains("CommandExecutor(") || t.contains(": CommandExecutor") } }.map { it.name }.toSet()
        assertEquals(setOf("CommandExecutor.kt", "AiOrchestrator.kt", "AiAssembly.kt"), executors)
        val models = sources("src/main/java/io/github/cragcoffee/memoripple/domain/ai/models").values.joinToString("\n") +
            sources("src/main/java/io/github/cragcoffee/memoripple/data/ai/models").values.joinToString("\n")
        listOf("IntentProposal", "domain.ai.Resolver", "CommandExecutor", "StructuredIntentGenerator", "AiOrchestrator", "PendingWrite").forEach { assertFalse("model management touches $it", models.contains(it)) }
    }
}
