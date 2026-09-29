package io.github.cragcoffee.memoripple

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectBoundaryPolicyTest {
    @Test
    fun applicationIdentityAndSdkContractAreExplicit() {
        val buildFile = projectFile("app/build.gradle.kts").readText()

        assertTrue(buildFile.contains("namespace = \"io.github.cragcoffee.memoripple\""))
        assertTrue(buildFile.contains("applicationId = \"io.github.cragcoffee.memoripple\""))
        assertTrue(buildFile.contains("compileSdk = 36"))
        assertTrue(buildFile.contains("targetSdk = 36"))
        assertTrue(buildFile.contains("minSdk = 29"))
        assertTrue(buildFile.contains("compose = true"))
        assertTrue(buildFile.contains("androidx.room:room-runtime"))
    }

    @Test
    fun sourceManifestCarriesExactlyTheDecidedPlatformPermissionsAndPrivateServices() {
        val manifest = projectFile("app/src/main/AndroidManifest.xml").readText()

        // Six permissions, each a recorded human decision: the fifth
        // (FOREGROUND_SERVICE_MEDIA_PLAYBACK, 2026-09) exists solely so a reading keeps
        // playing with the screen asleep, and the sixth (FOREGROUND_SERVICE_SHORT_SERVICE,
        // 2026-09, release audit B02) is the per-type permission Android 14+ demands for
        // the overlay's declared shortService — without it startForeground throws and the
        // overlay dies silently. Widening this list is a product decision.
        val expectedPermissions = listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",   // Phase 5: the metered-network confirmation before a multi-GB model download (docs/AI_MODEL_MANAGEMENT.md §5)
            "android.permission.SYSTEM_ALERT_WINDOW",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_SHORT_SERVICE",
            "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
            "android.permission.POST_NOTIFICATIONS",
        )
        val declaredPermissions = Regex("""<uses-permission android:name="([^"]+)"""")
            .findAll(manifest)
            .map { it.groupValues[1] }
            .toList()
        // The exact source/platform set, not just the count: nothing missing, nothing extra.
        assertEquals(expectedPermissions.sorted(), declaredPermissions.sorted())
        assertEquals(expectedPermissions.size, declaredPermissions.size)
        // Two private services: the overlay's short-lived one, and the speech playback
        // guard that stands only while a reading is in flight.
        assertEquals(2, Regex("<service").findAll(manifest).count())
        assertTrue(manifest.contains("android:foregroundServiceType=\"shortService\""))
        assertTrue(manifest.contains("android:foregroundServiceType=\"mediaPlayback\""))
        // The launcher activity alone is exported; both services stay private.
        assertEquals(1, Regex("android:exported=\"true\"").findAll(manifest).count())
        assertEquals(2, Regex("android:exported=\"false\"").findAll(manifest).count())
        assertFalse(manifest.contains("FOREGROUND_SERVICE_SPECIAL_USE"))
        assertTrue(manifest.contains("android.intent.action.TTS_SERVICE"))
    }

    @Test
    fun mergedManifestAddsOnlyTheExpectedPackageLocalSignaturePermission() {
        val expectedPlatformPermissions = setOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",   // Phase 5: the metered-network confirmation before a multi-GB model download (docs/AI_MODEL_MANAGEMENT.md §5)
            "android.permission.SYSTEM_ALERT_WINDOW",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_SHORT_SERVICE",
            "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
            "android.permission.POST_NOTIFICATIONS",
        )
        val expectedPackageLocalPermission =
            "io.github.cragcoffee.memoripple.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        val mergedManifest = sequenceOf(
            "app/build/intermediates/merged_manifests/release/" +
                "processReleaseManifest/AndroidManifest.xml",
            "app/build/intermediates/merged_manifests/debug/" +
                "processDebugManifest/AndroidManifest.xml",
        ).mapNotNull { path ->
            runCatching { projectFile(path) }.getOrNull()
        }.firstOrNull()?.readText()
            ?: error("No merged app manifest found")

        val mergedPermissions = Regex("""<uses-permission android:name="([^"]+)"""")
            .findAll(mergedManifest)
            .map { it.groupValues[1] }
            .toSet()
        val mergedPlatformPermissions = mergedPermissions.filterTo(mutableSetOf()) {
            it.startsWith("android.permission.")
        }
        val mergedPackageLocalPermissions = mergedPermissions - mergedPlatformPermissions

        assertEquals(expectedPlatformPermissions, mergedPlatformPermissions)
        assertEquals(setOf(expectedPackageLocalPermission), mergedPackageLocalPermissions)
        assertEquals(expectedPlatformPermissions.size + 1, mergedPermissions.size)

        val packageLocalDeclaration = Regex(
            """<permission\s+[^>]*android:name="$expectedPackageLocalPermission"[^>]*/>""",
        ).find(mergedManifest)?.value
        assertTrue(packageLocalDeclaration != null)
        assertTrue(packageLocalDeclaration!!.contains("android:protectionLevel=\"signature\""))
    }

    @Test
    fun theLauncherMarkUsesAdaptiveAndThemedResources() {
        val manifest = projectFile("app/src/main/AndroidManifest.xml").readText()
        val adaptiveIcon = projectFile(
            "app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml",
        ).readText()
        val themedIcon = projectFile(
            "app/src/main/res/mipmap-anydpi-v33/ic_launcher.xml",
        ).readText()
        val foreground = projectFile(
            "app/src/main/res/drawable/ic_launcher_foreground.xml",
        ).readText()

        assertTrue(manifest.contains("android:icon=\"@mipmap/ic_launcher\""))
        assertTrue(manifest.contains("android:roundIcon=\"@mipmap/ic_launcher\""))
        assertTrue(adaptiveIcon.contains("@color/brand_icon_background"))
        assertTrue(adaptiveIcon.contains("@drawable/ic_launcher_foreground"))
        assertTrue(themedIcon.contains("@drawable/ic_launcher_monochrome"))
        // The open-source snapshot ships a neutral placeholder mark (see BRANDING.md); the
        // foreground only has to draw something.
        assertTrue(Regex("<path").findAll(foreground).count() >= 1)
        assertFalse(
            projectFile("app/src/main/res/drawable")
                .resolve("ic_launcher_placeholder.xml")
                .exists(),
        )
    }

    @Test
    fun quietRippleHasDedicatedNotificationAndPlatformSplashResources() {
        val overlayService = projectFile(
            "app/src/main/java/io/github/cragcoffee/memoripple/overlay/OverlayCommentService.kt",
        ).readText()
        val splashTheme = projectFile("app/src/main/res/values-v31/themes.xml").readText()
        val nightSplashTheme =
            projectFile("app/src/main/res/values-night-v31/themes.xml").readText()

        assertTrue(overlayService.contains("setSmallIcon(R.drawable.ic_notification_overlay)"))
        assertFalse(overlayService.contains("ic_launcher"))
        // The launch window opens on the app's own color with no flashed logo: its icon slot
        // holds the transparent drawable, in both the light and the night-v31 theme (night
        // outranks version among qualifiers, so the dark path needs its own declaration).
        for (theme in listOf(splashTheme, nightSplashTheme)) {
            assertTrue(theme.contains("android:windowSplashScreenAnimatedIcon"))
            assertTrue(theme.contains("@drawable/splash_icon_none"))
            assertTrue(theme.contains("android:windowSplashScreenBackground"))
            assertFalse(theme.contains("@mipmap/ic_launcher"))
        }
    }

    @Test
    fun overlayForegroundServiceHasOnlyUserInitiatedActivityPath() {
        val productionFiles = projectFile("app/src/main/java").walkTopDown()
            .filter(File::isFile)
            .toList()
        val foregroundStartFiles = productionFiles.filter { file ->
            file.readText().contains("startForegroundService(")
        }

        // The speech guard's start lives with the application, fired only on a reading's
        // own start transition — speech always begins from the foreground.
        assertEquals(
            listOf("MemoRippleApplication.kt", "OverlayPlaybackModels.kt"),
            foregroundStartFiles.map(File::getName).sorted(),
        )
        val editor = projectFile(
            "app/src/main/java/io/github/cragcoffee/memoripple/ui/memos/MemoEditorScreen.kt",
        ).readText()
        assertTrue(editor.contains("playback_mode_overlay"))
        assertTrue(editor.contains("onStartOverlay"))
    }

    @Test
    fun navigationHasMemoCalendarAndChatAsTheOnlyBottomDestinations() {
        val app = projectFile(
            "app/src/main/java/io/github/cragcoffee/memoripple/ui/MemoRippleApp.kt",
        ).readText()

        assertTrue(app.contains("startDestination = Routes.MEMOS"))
        // Count call sites only; the composable's own declaration must not inflate the total.
        val bottomDestinationCount = Regex("QuietNavigationItem\\(").findAll(app).count() -
            Regex("fun QuietNavigationItem\\(").findAll(app).count()
        // メモ / カレンダー / チャット (HANDOFF §16.18): the end state, reached once the チャット tab
        // exists as a search and command workspace (no model needed).
        assertEquals(3, bottomDestinationCount)
        assertEquals(
            setOf("nav_memos", "nav_calendar", "nav_chat"),
            Regex("\"(nav_[a-z0-9_]+)\"")
                .findAll(app)
                .map { it.groupValues[1] }
                .toSet(),
        )
        assertTrue(app.contains("selected = currentRoute == Routes.CALENDAR"))
        assertTrue(app.contains("selected = currentRoute == Routes.CHAT"))
        // The old diary page survives as an ordinary screen (日記一覧) for compatibility.
        assertTrue(app.contains("Routes.DIARY"))
        assertTrue(app.contains("Routes.SETTINGS"))
    }

    @Test
    fun driveAuthorizationUsesOnlyTheAppDataScopeAndCurrentApi() {
        val source = projectFile(
            "app/src/main/java/io/github/cragcoffee/memoripple/drive/DriveAuthorizationGateway.kt",
        ).readText()

        assertTrue(source.contains("AuthorizationClient"))
        assertTrue(source.contains("AuthorizationRequest.builder()"))
        assertTrue(source.contains("Scope(Scopes.DRIVE_APPFOLDER)"))
        assertFalse(source.contains("requestOfflineAccess"))
        assertFalse(source.contains("GoogleSignInOptions"))
        assertFalse(source.contains("GoogleSignInClient"))
        assertFalse(source.contains("GoogleApiClient"))
        assertFalse(source.contains("GoogleAuthUtil"))
    }

    @Test
    fun driveRestTransportUsesV3AppDataAndResumableUpload() {
        val source = projectFile(
            "app/src/main/java/io/github/cragcoffee/memoripple/drive/DriveApi.kt",
        ).readText()

        assertTrue(source.contains("/drive/v3/files"))
        assertTrue(source.contains("spaces=appDataFolder"))
        assertTrue(source.contains("\\\"parents\\\":[\\\"appDataFolder\\\"]"))
        assertEquals(4, Regex("uploadType=resumable").findAll(source).count())
        assertTrue(source.contains("method = \"PUT\""))
    }

    @Test
    fun productionTreeDoesNotContainPreviousProductOrExternalServiceTerms() {
        val productionText = projectFile("app/src/main")
            .walkTopDown()
            .filter(File::isFile)
            .joinToString("\n") { it.readText() }
        val forbiddenTerms = listOf(
            "CragComment",
            "NicoComment",
            "Niconico",
            "dアニメ",
            "nicocommentoverlay",
            "NotificationListener",
            "MediaSession",
            "AccessibilityService",
            "UsageStatsManager",
            "MediaProjection",
            "QUERY_ALL_PACKAGES",
            "youtube",
            "danime",
        )

        forbiddenTerms.forEach { term ->
            assertFalse("Production tree contains forbidden term: $term", productionText.contains(term))
        }
    }

    @Test
    fun publicationPlaceholdersCannotLeakIntoProductionAssetsOrSource() {
        val productionText = projectFile("app/src/main")
            .walkTopDown()
            .filter(File::isFile)
            .joinToString("\n") { file ->
                if (file.extension in setOf("png", "webp")) "" else file.readText()
            }

        listOf("CONTACT_TBD", "URL_TBD", "DATE_TBD").forEach { placeholder ->
            assertFalse("Production contains release placeholder: $placeholder", productionText.contains(placeholder))
        }
    }

    @Test
    fun offlineLicenseCatalogAndReviewedReleaseInventoryAreCommitted() {
        val inventory = projectFile(
            "app/src/main/assets/oss/release-runtime-components.txt",
        ).readLines().filterNot { it.startsWith("#") || it.isBlank() }
        val catalog = projectFile("app/src/main/assets/oss/oss_licenses.json").readText()

        assertEquals(202, inventory.size)
        assertTrue(catalog.contains("\"componentCount\": 202"))
        assertTrue(catalog.contains("\"generatedFrom\": \":app:releaseRuntimeClasspath\""))
        assertTrue(projectFile("tools/generate_oss_assets.py").isFile)
    }

    private fun projectFile(relativePath: String): File {
        return sequenceOf(File(relativePath), File("../$relativePath"))
            .firstOrNull(File::exists)
            ?: error("Project file not found: $relativePath")
    }
}

/**
 * The チャット feature is a client of the Document boundary and nothing else: no DAO, no Room
 * entity, no route string, no calendar UI helper — its documents come from DocumentAccess and
 * open through the app's navigator (docs/CHAT_FOUNDATION.md).
 */
class ChatBoundaryPolicyTest {
    private val chatDir = java.io.File("src/main/java/io/github/cragcoffee/memoripple/ui/chat")

    private fun sources(): Map<String, String> {
        org.junit.Assert.assertTrue("chat feature exists", chatDir.isDirectory)
        val files = chatDir.listFiles { f -> f.extension == "kt" }!!.associate { it.name to it.readText() }
        org.junit.Assert.assertTrue(files.isNotEmpty())
        return files
    }

    @org.junit.Test
    fun chatImportsNoDaoEntityOrRoomAndSpellsNoRoute() {
        sources().forEach { (name, text) ->
            listOf(
                "import io.github.cragcoffee.memoripple.data.", "Dao", "Entity", "androidx.room",
                "editor/", "outliner/", "journal/", "memo-templates", "Routes.", "NavController", "navigate(",
                "io.github.cragcoffee.memoripple.ui.calendar",
            ).forEach { forbidden -> org.junit.Assert.assertTrue("$name mentions $forbidden", !text.contains(forbidden)) }
        }
    }

    @org.junit.Test
    fun chatSearchesCreatesAndOpensThroughTheDocumentBoundary() {
        val all = sources().values.joinToString("\n")
        // Chat UI redesign (2026-09-21): the chat no longer searches or creates on its own — every read and write goes through the
        // orchestrator (Resolver → DocumentAccess); the screen still speaks only in refs, summaries and kinds
        listOf("DocumentSummary", "DocumentRef", "DocumentKind").forEach {
            org.junit.Assert.assertTrue("chat uses $it", all.contains(it))
        }
        org.junit.Assert.assertFalse("the chat reaches no DocumentAccess directly", all.contains("DocumentAccess"))
        val app = java.io.File("src/main/java/io/github/cragcoffee/memoripple/ui/MemoRippleApp.kt").readText()
        org.junit.Assert.assertTrue(app.contains("Routes.CHAT"))
        org.junit.Assert.assertTrue("chat opens documents through the navigator", app.contains("ChatRoute(") && app.contains("documentNavigator::open"))
    }
}

/**
 * Local LLM Phase 0 (docs/LLM_PHASE0.md): the evaluation harness is a separate, non-shipping
 * application. It joins the build only under -PllmBench=true, :app never depends on it, and
 * no llama.cpp / ggml / bench code or native library reaches the product tree or its APK.
 */
class LlmBenchIsolationPolicyTest {
    private fun projectFile(relativePath: String): java.io.File =
        sequenceOf(java.io.File(relativePath), java.io.File("../$relativePath")).firstOrNull(java.io.File::exists)
            ?: error("Project file not found: $relativePath")

    @org.junit.Test
    fun theBenchModuleIsIncludedOnlyBehindTheExplicitProperty() {
        val settings = projectFile("settings.gradle.kts").readText()
        val includes = Regex("""include\("(:[^"]+)"\)""").findAll(settings).map { it.groupValues[1] }.toList()
        org.junit.Assert.assertEquals(listOf(":app", ":baselineprofile", ":llmbench"), includes)
        // The only include of :llmbench sits inside the llmBench property guard.
        org.junit.Assert.assertTrue(
            Regex("""if \(providers\.gradleProperty\("llmBench"\)\.orNull == "true"\) \{\s*include\(":llmbench"\)\s*}""").containsMatchIn(settings),
        )
        org.junit.Assert.assertEquals(1, Regex("\":llmbench\"").findAll(settings).count())
    }

    @org.junit.Test
    fun theProductModuleNeverDependsOnTheBenchAndItsNativeCodeIsItsOwn() {
        // Since Phase 2 (2026-09-19) the product has its own native bridge (app/src/main/cpp, libmemoripple_llm);
        // the Phase 0 bench (:llmbench, LlamaBridge, the bench runner) stays out of it.
        val appBuild = projectFile("app/build.gradle.kts").readText()
        org.junit.Assert.assertFalse(appBuild.contains("llmbench"))
        val appMain = projectFile("app/src/main")
        org.junit.Assert.assertFalse(java.io.File(appMain, "jniLibs").exists())
        org.junit.Assert.assertTrue(java.io.File(appMain, "cpp/memoripple_llm.cpp").exists())
        appMain.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "xml" || it.extension == "cpp") }.forEach { f ->
            val text = f.readText()
            listOf("llmbench.LlamaBridge", "BenchRunner", "BenchActivity", "io.github.cragcoffee.memoripple.llmbench").forEach {
                org.junit.Assert.assertFalse("${f.name} mentions $it", text.contains(it))
            }
        }
        // exactly one place loads the native library: the engine adapter
        val loaders = appMain.walkTopDown().filter { it.isFile && it.extension == "kt" && it.readText().contains("System.loadLibrary") }.map { it.name }.toList()
        org.junit.Assert.assertEquals(listOf("LlamaCppEngine.kt"), loaders)
    }

    @org.junit.Test
    fun theBenchIsItsOwnApplicationAndTheOnlySubmoduleHome() {
        val benchBuild = projectFile("llmbench/build.gradle.kts").readText()
        org.junit.Assert.assertTrue(benchBuild.contains("id(\"com.android.application\")"))
        org.junit.Assert.assertTrue(benchBuild.contains("applicationId = \"io.github.cragcoffee.memoripple.llmbench\""))
        org.junit.Assert.assertFalse(benchBuild.contains("project(\":app\")"))
        org.junit.Assert.assertFalse(benchBuild.contains("androidx.compose"))
        val gitmodules = projectFile(".gitmodules").readText()
        val paths = Regex("""path = (\S+)""").findAll(gitmodules).map { it.groupValues[1] }.toList()
        org.junit.Assert.assertEquals(listOf("llmbench/src/main/cpp/llama.cpp"), paths)
        org.junit.Assert.assertTrue(gitmodules.contains("url = https://github.com/ggml-org/llama.cpp"))
    }

    @org.junit.Test
    fun aBuiltProductApkCarriesNoBenchOrLlamaLibrary() {
        // Only checkable after an :app assemble; an absent APK is not a failure of this policy.
        val apk = sequenceOf(
            "app/build/outputs/apk/release/app-release.apk",
            "app/build/outputs/apk/release/app-release-unsigned.apk",
            "app/build/outputs/apk/debug/app-debug.apk",
        ).map { projectFileOrNull(it) }.filterNotNull().toList()
        org.junit.Assume.assumeTrue("no :app APK built yet", apk.isNotEmpty())
        apk.forEach { file ->
            java.util.zip.ZipFile(file).use { zip ->
                val offenders = zip.entries().asSequence().map { it.name }
                    .filter { (it.startsWith("lib/") && (it.contains("llmbench") || it.contains("libllama") || it.contains("libggml"))) || it.endsWith(".gguf") }
                    .toList()
                org.junit.Assert.assertEquals("${file.name} carries $offenders", emptyList<String>(), offenders)
            }
        }
    }

    private fun projectFileOrNull(relativePath: String): java.io.File? =
        sequenceOf(java.io.File(relativePath), java.io.File("../$relativePath")).firstOrNull(java.io.File::exists)
}
