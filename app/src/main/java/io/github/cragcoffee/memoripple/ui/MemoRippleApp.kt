package io.github.cragcoffee.memoripple.ui

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import io.github.cragcoffee.memoripple.domain.settings.AppSettings
import io.github.cragcoffee.memoripple.MemoRippleApplication
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.data.MemoRepository
import io.github.cragcoffee.memoripple.domain.documents.DocumentDestination
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.domain.documents.DocumentRef
import io.github.cragcoffee.memoripple.domain.documents.destination
import io.github.cragcoffee.memoripple.domain.memos.MemoDestination
import io.github.cragcoffee.memoripple.domain.memos.MemoKind
import io.github.cragcoffee.memoripple.domain.memos.destination
import io.github.cragcoffee.memoripple.domain.memos.memoKind
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import io.github.cragcoffee.memoripple.ui.calendar.CalendarRoute
import io.github.cragcoffee.memoripple.ui.chat.ChatRoute
import io.github.cragcoffee.memoripple.ui.chat.chatStageBackground
import io.github.cragcoffee.memoripple.ui.diary.DiaryEditorRoute
import io.github.cragcoffee.memoripple.ui.diary.JournalDayRoute
import io.github.cragcoffee.memoripple.domain.diary.JournalDateRouting
import io.github.cragcoffee.memoripple.domain.diary.JournalDateTarget
import io.github.cragcoffee.memoripple.ui.diary.DiaryRoute
import io.github.cragcoffee.memoripple.ui.diary.FutureCommentRevealRoute
import io.github.cragcoffee.memoripple.ui.memos.MemoEditorRoute
import io.github.cragcoffee.memoripple.ui.notes.NoteDetailRoute
import io.github.cragcoffee.memoripple.ui.outline.OutlinerRoute
import io.github.cragcoffee.memoripple.ui.notes.NoteReaderRoute
import io.github.cragcoffee.memoripple.ui.memos.MemoArchiveRoute
import io.github.cragcoffee.memoripple.ui.memos.MemoListRoute
import io.github.cragcoffee.memoripple.ui.memos.MemoTrashRoute
import io.github.cragcoffee.memoripple.ui.memos.TagManagementRoute
import io.github.cragcoffee.memoripple.ui.settings.SettingsRoute
import io.github.cragcoffee.memoripple.ui.settings.SpeechDictionaryRoute
import io.github.cragcoffee.memoripple.ui.settings.TemplatesRoute
import io.github.cragcoffee.memoripple.domain.memos.EditorToolbarSurface
import io.github.cragcoffee.memoripple.domain.memos.ToolbarChipGroup
import io.github.cragcoffee.memoripple.ui.settings.ToolbarOrderRoute
import io.github.cragcoffee.memoripple.ui.settings.ToolbarChipsRoute
import io.github.cragcoffee.memoripple.ui.settings.AboutScreen
import io.github.cragcoffee.memoripple.ui.settings.PrivacyScreen
import io.github.cragcoffee.memoripple.ui.settings.OssLicenseDetailScreen
import io.github.cragcoffee.memoripple.ui.settings.OssLicenseListScreen
import io.github.cragcoffee.memoripple.BuildConfig

private object Routes {
    const val MEMOS = "memos"
    const val DIARY = "diary"
    /** カレンダー: one time axis over memos, outlines and journal entries (HANDOFF §16.18). */
    const val CALENDAR = "calendar"
    /** チャット: the search and command workspace on the Document boundary; a model comes later. */
    const val CHAT = "chat"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    const val PRIVACY = "privacy"
    const val OSS_LICENSES = "oss-licenses"
    const val SPEECH_DICTIONARY = "speech-dictionary"
    const val TEMPLATES = "memo-templates"
    const val TEMPLATE_FOLDERS = "memo-template-folders"
    /** Local AI モデル: catalog, download, selection, deletion (docs/AI_MODEL_MANAGEMENT.md). */
    const val AI_MODELS = "ai-models"
    const val CHAT_HISTORY = "chat-history"
    /** 新しいチャット as its own stage (UI review 2026-09-21): the chat screen without the bottom navigation; Back returns to the tab. */
    const val CHAT_STAGE = "chat-stage"
    const val TEMPLATE_EDITOR = "template-editor?templateId={templateId}"
    fun templateEditor(templateId: String?) = if (templateId == null) "template-editor" else "template-editor?templateId=$templateId"
    const val TOOLBAR_ORDER = "toolbar-order/{surface}"
    fun toolbarOrder(surface: EditorToolbarSurface) = "toolbar-order/${surface.name}"
    const val TOOLBAR_CHIPS = "toolbar-order/{surface}/chips/{group}"
    fun toolbarChips(surface: EditorToolbarSurface, group: ToolbarChipGroup) = "toolbar-order/${surface.name}/chips/${group.name}"
    const val OSS_LICENSE_DETAIL = "oss-license/{licenseId}"
    /** A memo by id; `folderId` only matters for a new memo (id 0), which is filed there. */
    const val EDITOR = "editor/{memoId}?folderId={folderId}"
    /** Compatibility only (HANDOFF §16.18): a date is not an identity; see the shim below. */
    const val DIARY_EDITOR = "diary-editor/{epochDay}"
    /** A journal entry by id — the canonical route; the date is read from the entry. */
    const val JOURNAL = "journal/{entryId}"
    /** One day's entries, with the explicit action that makes a new one. */
    const val JOURNAL_DAY = "journal-day/{epochDay}"
    fun journal(entryId: Long) = "journal/$entryId"
    fun journalDay(epochDay: Long) = "journal-day/$epochDay"
    const val FUTURE_REVEAL = "future-reveal/{commentId}"
    const val TAG_MANAGEMENT = "tag-management"
    const val ARCHIVE = "memo-archive"
    const val TRASH = "memo-trash"
    const val NOTE_DETAIL = "note/{noteId}"
    const val NOTE_READER = "note/{noteId}/episode/{memoId}"
    /** An outline (kind = outline) in the outliner: opened from the アウトライナー page and the archive. */
    const val OUTLINER = "outliner/{memoId}?fresh={fresh}"
    fun outliner(memoId: Long) = "outliner/$memoId"
    /** An outline ＋ just made (docs/OUTLINE_PHOTO_ROWS.md §8). */
    fun newOutliner(memoId: Long) = "outliner/$memoId?fresh=true"
    fun noteDetail(noteId: Long) = "note/$noteId"
    fun noteReader(noteId: Long, memoId: Long) = "note/$noteId/episode/$memoId"
    fun editor(memoId: Long) = "editor/$memoId"
    fun newMemo(folderId: Long?) = if (folderId == null) "editor/0" else "editor/0?folderId=$folderId"
    fun diaryEditor(epochDay: Long) = "diary-editor/$epochDay"
    fun futureReveal(commentId: Long) = "future-reveal/$commentId"
    fun ossLicenseDetail(licenseId: String) = "oss-license/$licenseId"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemoRippleApp(appSettings: AppSettings = AppSettings.Default) {
    val application = LocalContext.current.applicationContext as MemoRippleApplication
    // 初回起動のようこそ: one static page, shown once, in place of the app until はじめる.
    // Null while the flag is still loading — neither the app nor the welcome flashes early.
    val hasSeenWelcomeDemo by application.settingsRepository.hasSeenWelcomeDemo
        .collectAsStateWithLifecycle(initialValue = null)
    val welcomeScope = rememberCoroutineScope()
    when (hasSeenWelcomeDemo) {
        null -> return
        false -> {
            WelcomeScreen(
                onStart = {
                    welcomeScope.launch {
                        application.settingsRepository.setHasSeenWelcomeDemo(true)
                    }
                },
            )
            return
        }
        true -> Unit
    }
    val navController = rememberNavController()
    // Opening by id can need a lookup first; that runs in the activity's own scope, not the
    // composition's frame clock — navigation is a side effect of the host, and it must
    // neither run mid-frame nor outlive the activity.
    val hostScope = LocalLifecycleOwner.current.lifecycleScope
    val documentNavigator = remember(navController, hostScope) {
        DocumentNavigator(navController, application.memoRepository, hostScope)
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // The main switch is メモ / カレンダー (HANDOFF §16.18); the old diary page is a secondary
    // screen (日記一覧) under the calendar and shows no bar.
    // The chat's input bar follows the keyboard (UI review 2026-09-21): while the keyboard is up the bar gives way, as the reference does.
    val imeVisible = WindowInsets.isImeVisible
    val showBottomBar = (currentRoute == Routes.MEMOS || currentRoute == Routes.CALENDAR || currentRoute == Routes.CHAT) && !(currentRoute == Routes.CHAT && imeVisible)
    var clearMemoSelection by remember { mutableStateOf<() -> Unit>({}) }
    val reselectSignal = application.tabReselectSignal
    val registerMemoSelectionClearer = remember {
        { clearer: () -> Unit -> clearMemoSelection = clearer }
    }

    Scaffold(
        // the chat stage paints the whole window, system bars included, with its own background
        containerColor = if (currentRoute == Routes.CHAT_STAGE) chatStageBackground() else MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.testTag(
            "app_settings_${appSettings.themeMode.storageId}_" +
                "${appSettings.playbackSpeed.storageId}_" +
                "${appSettings.commentSize.storageId}_" +
                appSettings.stageBackground.storageId,
        ),
        bottomBar = {
            if (showBottomBar) {
                // The tab already shown, tapped again, is that tab's own "back to its top" — a
                // one-shot event its root screen handles (docs/BOTTOM_NAV_RESELECT.md); any other
                // tab is the usual navigation.
                val shownTab = topLevelTabOf(currentRoute)
                fun tap(tab: TopLevelTab, navigate: () -> Unit) {
                    when (tabTap(shownTab, tab)) {
                        TabTap.RESELECT -> reselectSignal.reselect(tab)
                        TabTap.NAVIGATE -> navigate()
                    }
                }
                QuietBottomNavigation(
                    currentRoute = currentRoute,
                    onOpenMemos = { tap(TopLevelTab.MEMOS) { navController.navigateTopLevel(Routes.MEMOS) } },
                    onOpenCalendar = {
                        tap(TopLevelTab.CALENDAR) {
                            clearMemoSelection()
                            navController.navigateTopLevel(Routes.CALENDAR)
                        }
                    },
                    onOpenChat = {
                        tap(TopLevelTab.CHAT) {
                            clearMemoSelection()
                            navController.navigateTopLevel(Routes.CHAT)
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
            CompositionLocalProvider(LocalTabReselect provides reselectSignal) {
            NavHost(
                navController = navController,
                startDestination = Routes.MEMOS,
                modifier = Modifier.weight(1f),
            ) {
            composable(Routes.MEMOS) {
                MemoListRoute(
                    appSettings = appSettings,
                    // The wall lists memos and the アウトライナー page outlines: each page says
                    // the kind of what it holds, so neither open needs a lookup.
                    onOpenMemo = { documentNavigator.open(it, MemoKind.MEMO) },
                    onCreateMemo = { navController.navigate(Routes.newMemo(it)) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenTagManagement = { navController.navigate(Routes.TAG_MANAGEMENT) },
                    onOpenArchive = { navController.navigate(Routes.ARCHIVE) },
                    onOpenTrash = { navController.navigate(Routes.TRASH) },
                    onOpenNote = { navController.navigate(Routes.noteDetail(it)) },
                    onReadEpisode = { noteId, memoId ->
                        navController.navigate(Routes.noteReader(noteId, memoId))
                    },
                    onOpenOutline = { documentNavigator.open(it, MemoKind.OUTLINE) },
                    onOpenNewOutline = { navController.navigate(Routes.newOutliner(it)) },
                    registerSelectionClearer = registerMemoSelectionClearer,
                )
            }
            // 日記一覧: the old diary page, kept whole for now (docs/CALENDAR_TAB_MIGRATION.md),
            // reached from the calendar's top bar; its duplicates of the banner and 過去の今日 are
            // trimmed in a later round.
            composable(Routes.DIARY) { entry ->
                DiaryRoute(
                    onOpenEntry = { documentNavigator.openJournal(it) },
                    onOpenDay = { navController.navigate(Routes.journalDay(it)) },
                    onBack = { navController.popBackStackFrom(entry) },
                    onReceiveFutureComment = {
                        navController.navigate(Routes.futureReveal(0))
                    },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.SETTINGS) { entry ->
                SettingsRoute(
                    onBack = { navController.popBackStackFrom(entry) },
                    onOpenAbout = { navController.navigate(Routes.ABOUT) },
                    onOpenPrivacy = { navController.navigate(Routes.PRIVACY) },
                    onOpenOssLicenses = { navController.navigate(Routes.OSS_LICENSES) },
                    onOpenSpeechDictionary = {
                        navController.navigate(Routes.SPEECH_DICTIONARY)
                    },
                    onOpenTemplates = { navController.navigate(Routes.TEMPLATES) },
                    onOpenAiModels = { navController.navigate(Routes.AI_MODELS) },
                    onOpenToolbarOrder = { surface ->
                        navController.navigate(Routes.toolbarOrder(surface))
                    },
                )
            }
            composable(Routes.SPEECH_DICTIONARY) { entry ->
                SpeechDictionaryRoute(onBack = { navController.popBackStackFrom(entry) })
            }
            composable(Routes.AI_MODELS) { entry ->
                io.github.cragcoffee.memoripple.ui.settings.AiModelsRoute(onBack = { navController.popBackStackFrom(entry) })
            }
            composable(Routes.TEMPLATES) { entry ->
                TemplatesRoute(onBack = { navController.popBackStackFrom(entry) }, onEdit = { id -> navController.navigate(Routes.templateEditor(id)) }, onOpenFolders = { navController.navigate(Routes.TEMPLATE_FOLDERS) })
            }
            composable(Routes.TEMPLATE_FOLDERS) { entry ->
                io.github.cragcoffee.memoripple.ui.settings.TemplateFoldersRoute(onBack = { navController.popBackStackFrom(entry) })
            }
            composable(Routes.TEMPLATE_EDITOR) { entry ->
                io.github.cragcoffee.memoripple.ui.settings.TemplateEditorRoute(templateId = entry.arguments?.getString("templateId"), onDone = { navController.popBackStackFrom(entry) })
            }
            composable(Routes.TOOLBAR_ORDER) { entry ->
                val surface = entry.arguments?.getString("surface")
                    ?.let { name -> EditorToolbarSurface.entries.firstOrNull { it.name == name } }
                    ?: EditorToolbarSurface.MEMO
                ToolbarOrderRoute(
                    surface = surface,
                    onBack = { navController.popBackStackFrom(entry) },
                    onOpenChips = { group -> navController.navigate(Routes.toolbarChips(surface, group)) },
                )
            }
            composable(Routes.TOOLBAR_CHIPS) { entry ->
                val surface = entry.arguments?.getString("surface")
                    ?.let { name -> EditorToolbarSurface.entries.firstOrNull { it.name == name } }
                    ?: EditorToolbarSurface.MEMO
                val group = entry.arguments?.getString("group")
                    ?.let { name -> ToolbarChipGroup.entries.firstOrNull { it.name == name } }
                    ?: ToolbarChipGroup.LABELS
                ToolbarChipsRoute(
                    surface = surface,
                    group = group,
                    onBack = { navController.popBackStackFrom(entry) },
                )
            }
            composable(Routes.ABOUT) { entry ->
                AboutScreen(
                    versionName = BuildConfig.VERSION_NAME,
                    onBack = { navController.popBackStackFrom(entry) },
                )
            }
            composable(Routes.PRIVACY) { entry ->
                PrivacyScreen(onBack = { navController.popBackStackFrom(entry) })
            }
            composable(Routes.OSS_LICENSES) { entry ->
                OssLicenseListScreen(
                    onBack = { navController.popBackStackFrom(entry) },
                    onOpenLicense = { navController.navigate(Routes.ossLicenseDetail(it)) },
                )
            }
            composable(
                route = Routes.OSS_LICENSE_DETAIL,
                arguments = listOf(navArgument("licenseId") { type = NavType.StringType }),
            ) { entry ->
                OssLicenseDetailScreen(
                    licenseId = requireNotNull(entry.arguments?.getString("licenseId")),
                    onBack = { navController.popBackStackFrom(entry) },
                )
            }
            composable(Routes.TAG_MANAGEMENT) { entry ->
                TagManagementRoute(onBack = { navController.popBackStackFrom(entry) })
            }
            composable(Routes.ARCHIVE) { entry ->
                MemoArchiveRoute(
                    onBack = { navController.popBackStackFrom(entry) },
                    onOpenMemo = documentNavigator::open,
                )
            }
            composable(Routes.TRASH) { entry ->
                MemoTrashRoute(onBack = { navController.popBackStackFrom(entry) })
            }
            composable(Routes.NOTE_DETAIL) { entry ->
                val noteId = entry.arguments?.getString("noteId")?.toLongOrNull()
                    ?: return@composable
                NoteDetailRoute(
                    noteId = noteId,
                    onBack = { navController.popBackStackFrom(entry) },
                    onOpenEpisode = { memoId ->
                        navController.navigate(Routes.noteReader(noteId, memoId))
                    },
                    onEditEpisode = { documentNavigator.open(it) },
                )
            }
            composable(Routes.NOTE_READER) { entry ->
                val noteId = entry.arguments?.getString("noteId")?.toLongOrNull()
                    ?: return@composable
                val memoId = entry.arguments?.getString("memoId")?.toLongOrNull()
                    ?: return@composable
                NoteReaderRoute(
                    noteId = noteId,
                    memoId = memoId,
                    appSettings = appSettings,
                    onBack = { navController.popBackStackFrom(entry) },
                    onEdit = { documentNavigator.open(memoId) },
                )
            }
            composable(
                route = Routes.EDITOR,
                arguments = listOf(
                    navArgument("folderId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                val memoId = entry.arguments?.getString("memoId")?.toLongOrNull() ?: 0
                MemoEditorRoute(
                    memoId = memoId,
                    initialFolderId = entry.arguments?.getString("folderId")?.toLongOrNull(),
                    appSettings = appSettings,
                    onBack = { navController.popBackStackFrom(entry) },
                    onOpenAppSettings = { navController.navigate(Routes.SETTINGS) },
                    // A `[[title]]` chip, a backlink, a reading-mode link: resolved by title
                    // as before, then sent where the memo's kind says.
                    onOpenMemo = { documentNavigator.open(it) },
                    // 「こちらを編集」swaps rather than stacks: the reference becomes the one
                    // screen on top, and back still leads where it led before the split.
                    onSwapPrimaryMemo = { documentNavigator.openReplacing(entry, it) },
                    onOpenNoteAsPrimary = {
                        if (navController.popBackStackFrom(entry)) {
                            navController.navigate(Routes.noteDetail(it))
                        }
                    },
                    // An episode's 閲覧モード is its note's reader — the paged view with ruby
                    // and the read-aloud follow — not the memo's plain reading surface.
                    onOpenNoteReader = { noteId, episodeId ->
                        navController.navigate(Routes.noteReader(noteId, episodeId))
                    },
                )
            }
            composable(
                Routes.OUTLINER,
                arguments = listOf(navArgument("fresh") { type = NavType.BoolType; defaultValue = false }),
            ) { entry ->
                val memoId = entry.arguments?.getString("memoId")?.toLongOrNull()
                    ?: return@composable
                OutlinerRoute(
                    memoId = memoId,
                    fresh = entry.arguments?.getBoolean("fresh") == true,
                    appSettings = appSettings,
                    onBack = { navController.popBackStackFrom(entry) },
                    onOpenMemo = { documentNavigator.open(it) },
                    onSwapPrimaryMemo = { documentNavigator.openReplacing(entry, it) },
                    onOpenNoteAsPrimary = {
                        if (navController.popBackStackFrom(entry)) {
                            navController.navigate(Routes.noteDetail(it))
                        }
                    },
                )
            }
            composable(Routes.CHAT) {
                ChatRoute(
                    onOpen = documentNavigator::open,
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenAiModels = { navController.navigate(Routes.AI_MODELS) },
                    onOpenHistory = { navController.navigate(Routes.CHAT_HISTORY) },
                    onOpenTemplates = { navController.navigate(Routes.TEMPLATES) },
                    onEditTemplate = { id -> navController.navigate(Routes.templateEditor(id)) },
                    onEditTemplateDraft = { draft -> application.templateDraftHandoff.offer(draft); navController.navigate(Routes.templateEditor(null)) },
                    onOpenStage = { navController.navigate(Routes.CHAT_STAGE) },
                )
            }
            composable(Routes.CHAT_STAGE) { entry ->
                ChatRoute(
                    onOpen = documentNavigator::open,
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenAiModels = { navController.navigate(Routes.AI_MODELS) },
                    onOpenHistory = { navController.navigate(Routes.CHAT_HISTORY) },
                    onOpenTemplates = { navController.navigate(Routes.TEMPLATES) },
                    onEditTemplate = { id -> navController.navigate(Routes.templateEditor(id)) },
                    onEditTemplateDraft = { draft -> application.templateDraftHandoff.offer(draft); navController.navigate(Routes.templateEditor(null)) },
                    stage = true,
                    onBack = { navController.popBackStackFrom(entry) },
                )
            }
            composable(Routes.CHAT_HISTORY) { entry ->
                io.github.cragcoffee.memoripple.ui.chat.ChatHistoryRoute(onBack = { navController.popBackStackFrom(entry) })
            }
            composable(Routes.CALENDAR) {
                CalendarRoute(
                    onOpen = documentNavigator::open,
                    onOpenJournalList = { navController.navigate(Routes.DIARY) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onReceiveFutureComment = { navController.navigate(Routes.futureReveal(0)) },
                )
            }
            composable(Routes.JOURNAL) { entry ->
                val entryId = entry.arguments?.getString("entryId")?.toLongOrNull()
                    ?: return@composable
                DiaryEditorRoute(
                    entryId = entryId,
                    onBack = { navController.popBackStackFrom(entry) },
                    onReplayFutureComment = {
                        navController.navigate(Routes.futureReveal(it))
                    },
                )
            }
            composable(Routes.JOURNAL_DAY) { entry ->
                val epochDay = entry.arguments?.getString("epochDay")?.toLongOrNull()
                    ?: return@composable
                JournalDayRoute(
                    epochDay = epochDay,
                    onOpenEntry = { documentNavigator.openJournal(it) },
                    onBack = { navController.popBackStackFrom(entry) },
                )
            }
            // The old date route, kept for whatever still names a day: one entry that day opens
            // that entry, none or several open the day's list. Nothing is created here.
            composable(Routes.DIARY_EDITOR) { entry ->
                val epochDay = entry.arguments?.getString("epochDay")?.toLongOrNull()
                    ?: return@composable
                val application = LocalContext.current.applicationContext as MemoRippleApplication
                LaunchedEffect(epochDay) {
                    val ids = application.diaryRepository.entriesForDate(epochDay).map { it.id }
                    val route = when (val target = JournalDateRouting.resolve(ids)) {
                        is JournalDateTarget.Entry -> documentNavigator.route(DocumentRef(DocumentKind.JOURNAL, target.entryId))
                        JournalDateTarget.DayList -> Routes.journalDay(epochDay)
                    }
                    navController.navigate(route) {
                        popUpTo(Routes.DIARY_EDITOR) { inclusive = true }
                    }
                }
            }
            composable(Routes.FUTURE_REVEAL) { entry ->
                val commentId = entry.arguments?.getString("commentId")?.toLongOrNull()
                    ?: return@composable
                FutureCommentRevealRoute(
                    commentId = commentId,
                    appSettings = appSettings,
                    onClose = { navController.popBackStackFrom(entry) },
                )
            }
            }
            }
        }
    }
}

/** The bottom navigation's tab a route is the root of, or null for any other screen (which shows no bar). */
private fun topLevelTabOf(route: String?): TopLevelTab? = when (route) {
    Routes.MEMOS -> TopLevelTab.MEMOS
    Routes.CALENDAR -> TopLevelTab.CALENDAR
    Routes.CHAT -> TopLevelTab.CHAT
    else -> null
}

@Composable
private fun QuietBottomNavigation(
    currentRoute: String?,
    onOpenMemos: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenChat: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth().height(64.dp).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(
                    space = 20.dp,
                    alignment = Alignment.CenterHorizontally,
                ),
            ) {
                QuietNavigationItem(
                    label = "メモ",
                    imageVector = Icons.Outlined.Description,
                    selected = currentRoute == Routes.MEMOS,
                    onClick = onOpenMemos,
                    testTag = "nav_memos",
                )
                QuietNavigationItem(
                    label = "カレンダー",
                    imageVector = Icons.Outlined.CalendarMonth,
                    selected = currentRoute == Routes.CALENDAR,
                    onClick = onOpenCalendar,
                    testTag = "nav_calendar",
                )
                QuietNavigationItem(
                    label = "チャット",
                    imageVector = Icons.AutoMirrored.Outlined.Chat,
                    selected = currentRoute == Routes.CHAT,
                    onClick = onOpenChat,
                    testTag = "nav_chat",
                )
            }
        }
    }
}

@Composable
private fun QuietNavigationItem(
    label: String,
    imageVector: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
) {
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .width(88.dp)
            .fillMaxHeight()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
            )
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(22.dp),
        )
        Box(
            Modifier
                .padding(top = 3.dp)
                .size(width = 20.dp, height = 3.dp)
                .background(
                    color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = MaterialTheme.shapes.extraSmall,
                ),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}


/**
 * Pops only while [entry] is still the screen on top. A screen stays composed while it
 * fades out after its first 戻る, so a second tap in that window — or anything else that
 * asks the already-popped screen to leave again — would otherwise pop the destination
 * beneath it as well and leave the host empty. Returns whether a pop happened, so a swap
 * (pop, then navigate) can skip its navigate too when the request turns out to be stale.
 */
private fun NavHostController.popBackStackFrom(entry: NavBackStackEntry): Boolean =
    if (currentBackStackEntry?.id == entry.id) popBackStack() else false

/**
 * The one door through which a memo is opened by id.
 *
 * Every screen that can open a memo — the wall, the アウトライナー page, a `[[title]]` chip,
 * a backlink, the archive, a note's episode, the split pane's 「こちらを編集」 — hands the id
 * here, and here alone the kind decides the route (`MemoDestination`). A caller that already
 * holds the row passes its kind and navigates at once; one that holds only an id has the
 * kind looked up first, so the right screen is the first screen — never an editor that then
 * hands over to the outliner.
 */
private class DocumentNavigator(
    private val navController: NavHostController,
    private val memos: MemoRepository,
    private val scope: CoroutineScope,
) {
    fun open(memo: MemoEntity) = open(memo.id, memo.memoKind)

    fun open(memoId: Long, kind: MemoKind? = null) {
        if (kind != null) {
            navController.navigate(routeFor(memoId, kind))
        } else {
            scope.launch { navController.navigate(routeFor(memoId, kindOf(memoId))) }
        }
    }

    /**
     * Opens [memoId] in place of [entry]: the opener leaves the stack and the memo takes its
     * spot, so Back still leads where it led before. A stale request (that entry already
     * popped) swaps nothing.
     */
    fun openReplacing(entry: NavBackStackEntry, memoId: Long) {
        scope.launch {
            val route = routeFor(memoId, kindOf(memoId))
            if (navController.popBackStackFrom(entry)) navController.navigate(route)
        }
    }

    private suspend fun kindOf(memoId: Long): MemoKind =
        memos.findById(memoId)?.memoKind ?: MemoKind.MEMO

    private fun routeFor(memoId: Long, kind: MemoKind): String = when (kind.destination) {
        MemoDestination.EDITOR -> Routes.editor(memoId)
        MemoDestination.OUTLINER -> Routes.outliner(memoId)
    }

    /** Any document by its ref: the kind of screen comes from the domain, the route from here. */
    fun open(ref: DocumentRef) = navController.navigate(route(ref))

    fun openJournal(entryId: Long) = open(DocumentRef(DocumentKind.JOURNAL, entryId))

    /** The only place a document's route is spelled from its ref (the date shim needs the string for its popUpTo). */
    fun route(ref: DocumentRef): String = when (val destination = ref.destination()) {
        is DocumentDestination.MemoEditor -> Routes.editor(destination.memoId)
        is DocumentDestination.Outliner -> Routes.outliner(destination.memoId)
        is DocumentDestination.Journal -> Routes.journal(destination.entryId)
    }
}

private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
