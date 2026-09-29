package io.github.cragcoffee.memoripple

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.room.InvalidationTracker
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import io.github.cragcoffee.memoripple.BuildConfig
import io.github.cragcoffee.memoripple.backup.BackupEngine
import io.github.cragcoffee.memoripple.backup.SafBackupFileStore
import io.github.cragcoffee.memoripple.data.AppDatabase
import io.github.cragcoffee.memoripple.data.DiaryRepository
import io.github.cragcoffee.memoripple.data.FutureDiaryCommentRepository
import io.github.cragcoffee.memoripple.data.MemoCommentRepository
import io.github.cragcoffee.memoripple.data.FolderRepository
import io.github.cragcoffee.memoripple.data.DiaryContentStore
import io.github.cragcoffee.memoripple.data.MemoContentStore
import io.github.cragcoffee.memoripple.data.OutlineStore
import io.github.cragcoffee.memoripple.data.MemoRepository
import io.github.cragcoffee.memoripple.data.SettingsRepository
import io.github.cragcoffee.memoripple.data.StagingSweeper
import io.github.cragcoffee.memoripple.data.NoteRepository
import io.github.cragcoffee.memoripple.data.TemplateRepository
import io.github.cragcoffee.memoripple.data.TagRepository
import io.github.cragcoffee.memoripple.data.documents.RepositoryDocumentAccess
import io.github.cragcoffee.memoripple.data.AttachmentBlobStore
import io.github.cragcoffee.memoripple.data.AttachmentRepository
import io.github.cragcoffee.memoripple.domain.diary.SystemTimeProvider
import io.github.cragcoffee.memoripple.drive.BackupEngineDriveContent
import io.github.cragcoffee.memoripple.drive.DriveBackupCoordinator
import io.github.cragcoffee.memoripple.drive.DriveBackupStateStore
import io.github.cragcoffee.memoripple.drive.DriveBackupTransport
import io.github.cragcoffee.memoripple.drive.DriveRestApi
import io.github.cragcoffee.memoripple.drive.GoogleDriveAuthorizationGateway
import io.github.cragcoffee.memoripple.overlay.OverlayPlaybackStateStore
import io.github.cragcoffee.memoripple.speech.AndroidTextToSpeechGateway
import io.github.cragcoffee.memoripple.domain.speech.SpeechDictionary
import io.github.cragcoffee.memoripple.domain.split.SplitWorkspaceSession
import io.github.cragcoffee.memoripple.speech.SpeechController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val Context.appSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app_settings",
)

private val Context.driveBackupStateDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "drive_backup_state",
)

internal val DRIVE_DIRTY_TABLES = arrayOf(
    "memos",
    "memo_comments",
    "diary_entries",
    "future_diary_comments",
    "tags",
    "memo_tag_cross_refs",
    "memo_photo_attachments",
    // A memo's text and photos in their order (Room 26): a block-only change is a backup change too.
    "memo_content_blocks",
    // An outline's lines with their lasting ids (Room 28).
    "outline_rows",
    "diary_photo_attachments",
    "diary_content_blocks",
    "notes",
    "note_chapters",
)

open class MemoRippleApplication : Application(), Application.ActivityLifecycleCallbacks {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var startedActivityCount = 0
    private val databaseObserver = object : InvalidationTracker.Observer(DRIVE_DIRTY_TABLES) {
        override fun onInvalidated(tables: Set<String>) {
            applicationScope.launch { driveBackupCoordinator.recordChange() }
        }
    }
    private val attachmentObserver = object : InvalidationTracker.Observer(
        arrayOf("memo_photo_attachments", "diary_photo_attachments"),
    ) {
        override fun onInvalidated(tables: Set<String>) {
            applicationScope.launch { attachmentRepository.garbageCollect() }
        applicationScope.launch { sweepLeftovers() }
        }
    }
    private val speechControllerDelegate = lazy(::createSpeechController)
    val speechController: SpeechController get() = speechControllerDelegate.value

    /** The user dictionary, held warm so the speech engine's synchronous path can read it. */
    private val speechDictionaryEntries by lazy {
        settingsRepository.speechDictionary.stateIn(
            applicationScope,
            SharingStarted.Eagerly,
            emptyList(),
        )
    }

    protected open fun createSpeechController(): SpeechController =
        SpeechController(
            AndroidTextToSpeechGateway(applicationContext),
            transformText = { text ->
                SpeechDictionary.apply(text, speechDictionaryEntries.value)
            },
        )
    val settingsRepository by lazy { SettingsRepository(appSettingsDataStore) }
    /** 分割表示 working session: in-memory only, carried across the swap navigation. */
    val splitWorkspaceSession = SplitWorkspaceSession()
    val backupFileStore by lazy { SafBackupFileStore(contentResolver, cacheDir) }
    val commentFontStore by lazy {
        io.github.cragcoffee.memoripple.data.CommentFontStore(filesDir)
    }
    val templateRepository by lazy { TemplateRepository(appSettingsDataStore) }
    /** Template folders (2026-09-22): the user's own organisation of templates, beside them; never the wall's folders. */
    val templateFolderRepository by lazy { io.github.cragcoffee.memoripple.data.TemplateFolderRepository(appSettingsDataStore) }
    /** 最近使ったテンプレート: ids and times beside the settings (docs/CHAT_UI_TEMPLATE_V2.md §14). */
    val recentTemplateRepository by lazy { io.github.cragcoffee.memoripple.data.RecentTemplateRepository(appSettingsDataStore) }
    val noteRepository by lazy { NoteRepository(database.noteDao(), timeProvider) }
    val database by lazy { AppDatabase.create(this) }
    /** A memo's blocks — the one writer of `memo_content_blocks` (docs/MEMO_CONTENT_BLOCKS.md). */
    val memoContentStore by lazy { MemoContentStore(database) }
    val diaryContentStore by lazy { DiaryContentStore(database, timeProvider::nowMillis) }
    val outlineStore by lazy { OutlineStore(database) }
    val memoRepository by lazy { MemoRepository(database.memoDao(), timeProvider, memoContentStore, outlineStore) }
    val folderRepository by lazy { FolderRepository(database, database.folderDao(), database.memoDao(), timeProvider) }
    /** Conversation history (docs/AI_CONVERSATION_HISTORY.md): transcripts and safe context, device-local, never backed up. */
    val chatHistoryRepository by lazy { io.github.cragcoffee.memoripple.data.ChatHistoryRepository(database, database.chatDao(), timeProvider, chatMemoSelectionStore) }
    val lastConversationStore by lazy { io.github.cragcoffee.memoripple.data.DataStoreLastConversationStore(settingsRepository) }
    val pinnedConversationStore by lazy { io.github.cragcoffee.memoripple.data.DataStorePinnedConversationStore(settingsRepository) }
    /** The chat's closed 「モデルが必要です」 hint (UI/UX review 2026-09-23): one flag, closed for good. */
    val chatHintStore by lazy { io.github.cragcoffee.memoripple.data.DataStoreChatHintStore(settingsRepository) }
    /** The ＋ picker's pinned templates (Review Batch 2): ids in the settings, never content. */
    val pinnedTemplateStore by lazy { io.github.cragcoffee.memoripple.data.DataStorePinnedTemplateStore(settingsRepository) }
    /** The chat home launcher (2026-09-23): the templates the user put on the home, and removing one of their own templates. */
    val homeShortcutStore by lazy { io.github.cragcoffee.memoripple.data.DataStoreHomeShortcutStore(settingsRepository) }
    val templateRemover by lazy { io.github.cragcoffee.memoripple.data.RepositoryTemplateRemover(templateRepository) }
    /** The chat's chosen folder and the wall's folders as its choices (2026-09-22). */
    val chatDestinationStore by lazy { io.github.cragcoffee.memoripple.data.DataStoreChatDestinationStore(settingsRepository) }
    val folderChoices by lazy { io.github.cragcoffee.memoripple.data.RepositoryFolderChoices(folderRepository) }
    /** 「メモを選択」 (docs/CHAT_MEMO_CONTEXT.md): the memos the chat may point at (read-only), and each conversation's choice (ids in a preference). */
    val memoChoices by lazy { io.github.cragcoffee.memoripple.data.RepositoryMemoChoices(memoRepository) }
    val chatMemoSelectionStore by lazy { io.github.cragcoffee.memoripple.data.DataStoreChatMemoSelectionStore(settingsRepository) }
    /** A conversation's template draft on its way to the editor (「この会話からテンプレートを作成」); memory only. */
    val templateDraftHandoff by lazy { io.github.cragcoffee.memoripple.ui.settings.TemplateDraftHandoff() }
    /** The bottom navigation's "tapped again" (docs/BOTTOM_NAV_RESELECT.md): a one-shot event, memory only, never replayed. */
    val tabReselectSignal by lazy { io.github.cragcoffee.memoripple.ui.TabReselectSignal() }
    val memoCommentRepository by lazy { MemoCommentRepository(database.memoCommentDao()) }
    val tagRepository by lazy { TagRepository(database.tagDao()) }
    /** The Document boundary (docs/DOCUMENT_BOUNDARY.md): the door Search and Chat get to memos, outlines and journals. */
    val documentAccess by lazy { RepositoryDocumentAccess(database, memoRepository, diaryRepository, tagRepository, timeProvider) }
    /** The one local-LLM runtime of the process (docs/LOCAL_LLM_RUNTIME.md); created on first use, unloaded on low memory. */
    val localModelRuntimeHolder by lazy {
        // Built behind the CPU feature check: the engine (and its native library) is never
        // constructed on a CPU the build cannot run on (docs/LOCAL_LLM_RUNTIME.md, CLAUDE §4).
        io.github.cragcoffee.memoripple.data.ai.LocalModelRuntimeHolder {
            io.github.cragcoffee.memoripple.data.ai.productLocalModelRuntime(noBackupFilesDir)
        }
    }
    /**
     * The one door from チャット to the AI path (docs/AI_CHAT_PREVIEW.md): text in, a result the
     * screen shows out; SEARCH / OPEN run, writes stop at their preview. Created on first use;
     * the runtime behind it only on the first ask.
     */
    private val aiOrchestratorDelegate = lazy { createAiOrchestrator() }
    val aiOrchestrator: io.github.cragcoffee.memoripple.domain.ai.AiOrchestrator by aiOrchestratorDelegate

    protected open fun createAiOrchestrator(): io.github.cragcoffee.memoripple.domain.ai.AiOrchestrator =
        io.github.cragcoffee.memoripple.data.ai.productAiOrchestrator(
            context = this,
            holder = localModelRuntimeHolder,
            store = modelStore,
            selectedModelId = { settingsRepository.selectedAiModelId.first() },
            documents = documentAccess,
            templates = io.github.cragcoffee.memoripple.data.TemplateLookupAdapter(templateRepository),
            time = timeProvider,
            scope = applicationScope,
            isDebugBuild = BuildConfig.DEBUG,
        )
    /** Model files under noBackupFilesDir/models (docs/AI_MODEL_MANAGEMENT.md); never backed up, never in the APK. */
    val modelStore by lazy { io.github.cragcoffee.memoripple.data.ai.models.FileModelStore(noBackupFilesDir) }
    /** Download / verify / select / delete of catalog models; starts nothing by itself. Created on first use, then reads the disk. */
    val modelManager: io.github.cragcoffee.memoripple.domain.ai.models.ModelManager by lazy {
        createModelManager().also { manager -> applicationScope.launch { manager.refresh() } }
    }

    protected open fun createModelManager(): io.github.cragcoffee.memoripple.domain.ai.models.ModelManager =
        io.github.cragcoffee.memoripple.domain.ai.models.ModelManager(
            catalog = io.github.cragcoffee.memoripple.data.ai.models.ModelCatalog.all,
            store = modelStore,
            downloader = io.github.cragcoffee.memoripple.data.ai.models.HttpModelDownloader(),
            guards = io.github.cragcoffee.memoripple.data.ai.models.AndroidDeviceGuards(this),
            selection = io.github.cragcoffee.memoripple.data.ai.models.DataStoreSelectedModelStore(settingsRepository),
            unloadRuntime = { if (aiOrchestratorDelegate.isInitialized()) aiOrchestrator.release() },
            scope = applicationScope,
        )
    val timeProvider by lazy { SystemTimeProvider() }
    val attachmentBlobStore by lazy { AttachmentBlobStore(filesDir) }
    val attachmentRepository by lazy {
        AttachmentRepository(
            database = database,
            dao = database.attachmentDao(),
            noteDao = database.noteDao(),
            contentResolver = contentResolver,
            blobStore = attachmentBlobStore,
            nowMillis = timeProvider::nowMillis,
            content = memoContentStore,
            diaryContent = diaryContentStore,
        )
    }
    val diaryRepository by lazy {
        DiaryRepository(database.diaryDao(), timeProvider, database.attachmentDao(), diaryContentStore)
    }
    val futureDiaryCommentRepository by lazy {
        FutureDiaryCommentRepository(
            database.futureDiaryCommentDao(),
            database.diaryDao(),
            timeProvider,
        )
    }
    val portableAutoExporter by lazy {
        io.github.cragcoffee.memoripple.portableexport.PortableAutoExporter(
            engine = portableExportEngine,
            settingsRepository = settingsRepository,
            contentResolver = contentResolver,
            nowMillis = timeProvider::nowMillis,
        )
    }

    val portableImportEngine by lazy {
        io.github.cragcoffee.memoripple.portableexport.PortableImportEngine(
            memoRepository = memoRepository,
            tagRepository = tagRepository,
            attachmentRepositoryFactory = { source ->
                AttachmentRepository(
                    database = database,
                    dao = database.attachmentDao(),
                    noteDao = database.noteDao(),
                    contentResolver = contentResolver,
                    blobStore = attachmentBlobStore,
                    nowMillis = timeProvider::nowMillis,
                    contentSource = source,
                    content = memoContentStore,
                    diaryContent = diaryContentStore,
                )
            },
            fileStore = backupFileStore,
            cacheDirectory = cacheDir,
            nowMillis = timeProvider::nowMillis,
            outlineStore = outlineStore,
        )
    }

    val portableExportEngine by lazy {
        io.github.cragcoffee.memoripple.portableexport.PortableExportEngine(
            database = database,
            blobStore = attachmentBlobStore,
            fileStore = backupFileStore,
            cacheDirectory = cacheDir,
        )
    }

    val backupEngine by lazy {
        BackupEngine(
            database = database,
            backupDao = database.backupDao(),
            settingsRepository = settingsRepository,
            diaryRepository = diaryRepository,
            futureDiaryCommentRepository = futureDiaryCommentRepository,
            timeProvider = timeProvider,
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE.toLong(),
            blobStore = attachmentBlobStore,
            attachmentRepository = attachmentRepository,
            templateRepository = templateRepository,
            templateFolderRepository = templateFolderRepository,
            cacheDirectory = cacheDir,
        )
    }
    val driveBackupStateStore by lazy { DriveBackupStateStore(driveBackupStateDataStore) }
    val overlayPlaybackStateStore by lazy { OverlayPlaybackStateStore() }
    val driveBackupCoordinator by lazy {
        DriveBackupCoordinator(
            stateStore = driveBackupStateStore,
            authorization = GoogleDriveAuthorizationGateway(applicationContext),
            transport = DriveBackupTransport(DriveRestApi(), cacheDir),
            content = BackupEngineDriveContent(backupEngine),
            scope = applicationScope,
        )
    }

    /**
     * What a process death may have left: cache staging older than a day, outliner view state of
     * memos that are gone, comment-font files the catalog no longer names. Nothing a user made.
     */
    private suspend fun sweepLeftovers() {
        runCatching { StagingSweeper(cacheDir).sweep(now = System.currentTimeMillis()) }
        runCatching { settingsRepository.pruneOutlinerViewState(database.memoDao().allIds().toSet()) }
        runCatching {
            settingsRepository.userCommentFontsForCleanup()?.let { commentFontStore.removeUnlisted(it) }
        }
    }

    /** Low memory unloads a loaded model; the runtime is never resident by design (docs/LOCAL_LLM_RUNTIME.md). */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val pressure = io.github.cragcoffee.memoripple.data.ai.LocalModelRuntimeHolder.pressureOf(level) ?: return
        // Loading a model raises the trim level by itself, so the orchestrator — which knows
        // whether an ask is in flight — decides; the holder only when nothing else owns the runtime.
        if (aiOrchestratorDelegate.isInitialized()) {
            android.util.Log.i("AiChat", "onTrimMemory level=$level → $pressure (orchestrator, runtime ${aiOrchestrator.runtimeState()})")
            applicationScope.launch { aiOrchestrator.onMemoryPressure(pressure) }
            return
        }
        val holder = localModelRuntimeHolder
        if (!holder.isCreated) return
        applicationScope.launch { holder.onMemoryPressure(pressure) }
    }

    override fun onCreate() {
        super.onCreate()
        watchSpeechForPlaybackService()
        driveBackupCoordinator
        database.invalidationTracker.addObserver(databaseObserver)
        database.invalidationTracker.addObserver(attachmentObserver)
        applicationScope.launch { attachmentRepository.garbageCollect() }
        applicationScope.launch {
            settingsRepository.settings.drop(1).collect {
                driveBackupCoordinator.recordChange()
            }
        }
        registerActivityLifecycleCallbacks(this)
    }

    /**
     * 読み上げの見張り: the moment a reading starts, the foreground service stands so the
     * voice survives the screen sleeping; the service watches the same state and leaves on
     * its own when the voice goes quiet. Started here — speech always begins from the
     * foreground — never restarted from the background.
     */
    private fun watchSpeechForPlaybackService() {
        applicationScope.launch {
            var wasReading = false
            speechController.state.collect { state ->
                val reading = state.status ==
                    io.github.cragcoffee.memoripple.speech.SpeechStatus.SPEAKING ||
                    state.status ==
                    io.github.cragcoffee.memoripple.speech.SpeechStatus.INITIALIZING
                if (reading && !wasReading) {
                    runCatching {
                        startForegroundService(
                            android.content.Intent(
                                this@MemoRippleApplication,
                                io.github.cragcoffee.memoripple.speech.SpeechPlaybackService::class.java,
                            ),
                        )
                    }
                }
                wasReading = reading
            }
        }
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivityCount += 1
        if (startedActivityCount == 1) {
            driveBackupCoordinator.onForeground()
            // The launch is the automatic export's only clock; the exporter itself decides
            // in one cheap read whether anything is due.
            applicationScope.launch { portableAutoExporter.runIfDue() }
        }
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
        if (startedActivityCount == 0) {
            driveBackupCoordinator.onBackground()
            // The speech engine is borrowed, not owned: with the app out of sight and nothing
            // being read, it goes back. A reading still in flight keeps it.
            if (speechControllerDelegate.isInitialized()) speechController.releaseWhenIdle()
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    override fun onTerminate() {
        unregisterActivityLifecycleCallbacks(this)
        if (database.isOpen) database.invalidationTracker.removeObserver(databaseObserver)
        if (database.isOpen) database.invalidationTracker.removeObserver(attachmentObserver)
        if (speechControllerDelegate.isInitialized()) speechController.shutdown()
        applicationScope.cancel()
        super.onTerminate()
    }
}
