package io.github.cragcoffee.memoripple.ui.settings

import androidx.activity.result.IntentSenderRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import io.github.cragcoffee.memoripple.domain.settings.ThemePaletteStyle
import io.github.cragcoffee.memoripple.domain.settings.ThemeSeed
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Slider
import androidx.compose.ui.Alignment
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.cragcoffee.memoripple.MemoRippleApplication
import io.github.cragcoffee.memoripple.domain.memos.EditorToolbarSurface
import io.github.cragcoffee.memoripple.domain.OutlineSymbolRole
import io.github.cragcoffee.memoripple.domain.OutlineChipLabel
import io.github.cragcoffee.memoripple.domain.OutlineSymbolSelection
import io.github.cragcoffee.memoripple.domain.OutlineSymbolSet
import io.github.cragcoffee.memoripple.BuildConfig
import io.github.cragcoffee.memoripple.backup.BACKUP_MIME_TYPE
import io.github.cragcoffee.memoripple.backup.RestorePreview
import io.github.cragcoffee.memoripple.domain.settings.AppSettings
import io.github.cragcoffee.memoripple.domain.settings.CommentFont
import io.github.cragcoffee.memoripple.domain.settings.CommentSize
import io.github.cragcoffee.memoripple.domain.settings.PlaybackSpeed
import io.github.cragcoffee.memoripple.domain.settings.StageBackground
import io.github.cragcoffee.memoripple.domain.settings.ThemeMode
import io.github.cragcoffee.memoripple.drive.DriveBackupErrorKind
import io.github.cragcoffee.memoripple.drive.DriveBackupUiState
import io.github.cragcoffee.memoripple.drive.DriveRuntimeStatus
import io.github.cragcoffee.memoripple.overlay.OverlayPermissionGateway
import io.github.cragcoffee.memoripple.ui.memos.OverlayPermissionDisclosureDialog
import io.github.cragcoffee.memoripple.overlay.OverlaySettingsLauncher
import io.github.cragcoffee.memoripple.ui.components.DestructiveTextButton
import io.github.cragcoffee.memoripple.ui.components.ImportWording
import io.github.cragcoffee.memoripple.ui.components.ProductSettingsRow
import io.github.cragcoffee.memoripple.ui.components.ProductSize
import io.github.cragcoffee.memoripple.ui.components.ProductSpacing
import io.github.cragcoffee.memoripple.ui.components.ProductTopBar
import io.github.cragcoffee.memoripple.ui.components.SectionHeader
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenOssLicenses: () -> Unit,
    onOpenSpeechDictionary: () -> Unit,
    onOpenTemplates: () -> Unit,
    onOpenAiModels: () -> Unit,
    onOpenToolbarOrder: (EditorToolbarSurface) -> Unit,
) {
    val context = LocalContext.current
    val application = context.applicationContext as MemoRippleApplication
    val ttsSettingsLauncher = remember { AndroidTtsSettingsLauncher() }
    val overlayPermissionGateway = remember(context) { OverlayPermissionGateway(context) }
    val overlaySettingsLauncher = remember { OverlaySettingsLauncher() }
    var overlayPermissionGranted by remember {
        mutableStateOf(overlayPermissionGateway.isGranted())
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.factory(
            repository = application.settingsRepository,
            backupEngine = application.backupEngine,
            backupFileStore = application.backupFileStore,
            driveBackupCoordinator = application.driveBackupCoordinator,
        ),
    )
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val autoPlayOnLaunch by application.settingsRepository.autoPlayOnLaunch
        .collectAsStateWithLifecycle(initialValue = true)
    val exportDocx by application.settingsRepository.editorExportDocx
        .collectAsStateWithLifecycle(initialValue = false)
    val outlineSymbolsStored by application.settingsRepository.outlineSymbolSet
        .collectAsStateWithLifecycle(initialValue = "")
    val outlineChipLabelStored by application.settingsRepository.outlineChipLabel
        .collectAsStateWithLifecycle(initialValue = "")
    val speechReadRuby by application.settingsRepository.speechReadRuby
        .collectAsStateWithLifecycle(initialValue = false)
    val readerSwipeTurns by application.settingsRepository.readerSwipeTurns
        .collectAsStateWithLifecycle(initialValue = true)
    val menuCopyAll by application.settingsRepository.editorMenuCopyAll
        .collectAsStateWithLifecycle(initialValue = true)
    val menuPdfExport by application.settingsRepository.editorMenuPdfExport
        .collectAsStateWithLifecycle(initialValue = true)
    val toolbarTwoRows by application.settingsRepository.editorToolbarTwoRows
        .collectAsStateWithLifecycle(initialValue = false)
    val hideTags by application.settingsRepository.editorHideTags
        .collectAsStateWithLifecycle(initialValue = false)
    val commentFontId by application.settingsRepository.commentFontId
        .collectAsStateWithLifecycle(initialValue = CommentFont.DEFAULT.storageId)
    val userCommentFonts by application.settingsRepository.userCommentFonts
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val themeSeed by application.settingsRepository.themeSeed
        .collectAsStateWithLifecycle(initialValue = ThemeSeed.DEFAULT)
    val themePaletteStyle by application.settingsRepository.themePaletteStyle
        .collectAsStateWithLifecycle(initialValue = ThemePaletteStyle.TONAL_SPOT)
    val wallOverlayPlayback by application.settingsRepository.wallOverlayPlayback
        .collectAsStateWithLifecycle(initialValue = false)
    val commentBackdrop by application.settingsRepository.commentBackdrop
        .collectAsStateWithLifecycle(initialValue = false)
    val commentTransparency by application.settingsRepository.commentTransparency
        .collectAsStateWithLifecycle(initialValue = 0f)
    val wallPlaysContent by application.settingsRepository.wallPlaysContent
        .collectAsStateWithLifecycle(initialValue = false)
    val settingsScope = rememberCoroutineScope()
    var commentFontMessage by remember { mutableStateOf<String?>(null) }
    val pickCommentFont = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            settingsScope.launch {
                // The catalog is capped; refusing here keeps the copy from being written and then
                // dropped by the cap, which used to leave a file nothing named.
                if (userCommentFonts.size >= io.github.cragcoffee.memoripple.domain.settings.UserCommentFont.MAXIMUM) {
                    commentFontMessage = "フォントは${io.github.cragcoffee.memoripple.domain.settings.UserCommentFont.MAXIMUM}件までです"
                    return@launch
                }
                val displayName = runCatching {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val index = cursor.getColumnIndex(
                            android.provider.OpenableColumns.DISPLAY_NAME,
                        )
                        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                    }
                }.getOrNull() ?: "フォント.ttf"
                val installed = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        application.commentFontStore.install(input, displayName)
                    }
                }.getOrNull()
                if (installed == null) {
                    commentFontMessage = "フォントとして読み込めませんでした"
                } else {
                    application.settingsRepository.setUserCommentFonts(
                        userCommentFonts + installed,
                    )
                    application.settingsRepository.setCommentFontId(
                        io.github.cragcoffee.memoripple.domain.settings.CommentFontSelection
                            .userStorageId(installed),
                    )
                }
            }
        }
    }
    commentFontMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { commentFontMessage = null },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { commentFontMessage = null }) { Text("OK") }
            },
        )
    }
    val backupState by viewModel.backupState.collectAsStateWithLifecycle()
    val driveBackupState by viewModel.driveBackupState.collectAsStateWithLifecycle()
    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BACKUP_MIME_TYPE),
        viewModel::onBackupDestinationSelected,
    )
    val portableExportViewModel: PortableExportViewModel = viewModel()
    val portableImportViewModel: PortableImportViewModel = viewModel()
    val autoExportEnabled by application.settingsRepository.portableAutoExportEnabled
        .collectAsStateWithLifecycle(initialValue = false)
    val autoExportTree by application.settingsRepository.portableAutoExportTreeUri
        .collectAsStateWithLifecycle(initialValue = "")
    val autoExportInterval by application.settingsRepository.portableAutoExportIntervalDays
        .collectAsStateWithLifecycle(initialValue = 1)
    val autoExportLastResult by application.settingsRepository.portableAutoExportLastResult
        .collectAsStateWithLifecycle(initialValue = "")
    var enableAutoExportAfterPick by remember { mutableStateOf(false) }
    var showAutoExportInterval by remember { mutableStateOf(false) }
    val pickAutoExportFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) {
            enableAutoExportAfterPick = false
        } else {
            val previous = autoExportTree
            val enableAfter = enableAutoExportAfterPick
            enableAutoExportAfterPick = false
            settingsScope.launch {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
                if (previous.isNotBlank() && previous != uri.toString()) {
                    runCatching {
                        context.contentResolver.releasePersistableUriPermission(
                            android.net.Uri.parse(previous),
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                    }
                }
                application.settingsRepository.setPortableAutoExportTreeUri(uri.toString())
                if (enableAfter) {
                    application.settingsRepository.setPortableAutoExportEnabled(true)
                }
            }
        }
    }
    if (showAutoExportInterval) {
        PortableAutoExportIntervalDialog(
            currentDays = autoExportInterval,
            onPick = { days ->
                showAutoExportInterval = false
                settingsScope.launch {
                    application.settingsRepository.setPortableAutoExportIntervalDays(days)
                }
            },
            onDismiss = { showAutoExportInterval = false },
        )
    }
    val portableImportState by portableImportViewModel.state.collectAsStateWithLifecycle()
    val openPortableImport = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
        portableImportViewModel::onSourceSelected,
    )
    LaunchedEffect(portableImportViewModel) {
        portableImportViewModel.effects.collect { effect ->
            when (effect) {
                PortableImportViewModel.Effect.OpenDocument -> openPortableImport.launch(
                    arrayOf("application/zip", "application/octet-stream"),
                )
            }
        }
    }
    val portableExportState by portableExportViewModel.state.collectAsStateWithLifecycle()
    val createPortableExport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
        portableExportViewModel::onDestinationSelected,
    )
    val createPortablePdf = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
        portableExportViewModel::onDestinationSelected,
    )
    LaunchedEffect(portableExportViewModel) {
        portableExportViewModel.effects.collect { effect ->
            when (effect) {
                is PortableExportViewModel.Effect.CreateDocument -> when (effect.format) {
                    PortableExportViewModel.Format.ZIP ->
                        createPortableExport.launch(effect.suggestedFileName)
                    PortableExportViewModel.Format.PDF ->
                        createPortablePdf.launch(effect.suggestedFileName)
                }
            }
        }
    }
    val openDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
        viewModel::onRestoreSourceSelected,
    )
    val driveAuthorization = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result -> viewModel.onDriveAuthorizationResult(result.resultCode, result.data) }
    val overlaySettingsResult = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        overlayPermissionGranted = overlayPermissionGateway.isGranted()
    }

    DisposableEffect(lifecycleOwner, overlayPermissionGateway) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayPermissionGranted = overlayPermissionGateway.isGranted()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is SettingsBackupEffect.CreateDocument -> createDocument.launch(effect.suggestedFileName)
                SettingsBackupEffect.OpenDocument -> openDocument.launch(arrayOf(BACKUP_MIME_TYPE))
                is SettingsBackupEffect.LaunchDriveAuthorization -> driveAuthorization.launch(
                    IntentSenderRequest.Builder(effect.pendingIntent.intentSender).build(),
                )
            }
        }
    }
    when (val exportState = portableExportState) {
        is PortableExportViewModel.UiState.Ready -> PortableExportSheet(
            counts = exportState.counts,
            includeTrash = exportState.includeTrash,
            onIncludeTrashChange = portableExportViewModel::setIncludeTrash,
            stripPhotoMetadata = exportState.stripPhotoMetadata,
            onStripPhotoMetadataChange = portableExportViewModel::setStripPhotoMetadata,
            format = exportState.format,
            onFormatChange = portableExportViewModel::setFormat,
            onChooseDestination = portableExportViewModel::chooseDestination,
            onDismiss = portableExportViewModel::dismiss,
        )
        is PortableExportViewModel.UiState.Exporting -> PortableExportProgressDialog(
            done = exportState.done,
            total = exportState.total,
            onCancel = portableExportViewModel::cancelExport,
        )
        is PortableExportViewModel.UiState.Finished -> PortableExportResultDialog(
            message = exportState.message,
            onDismiss = portableExportViewModel::dismiss,
        )
        PortableExportViewModel.UiState.Idle,
        PortableExportViewModel.UiState.Preparing,
        -> Unit
    }
    when (val importState = portableImportState) {
        is PortableImportViewModel.UiState.Confirm -> PortableImportConfirmDialog(
            memoCount = importState.preview.memos,
            photoCount = importState.preview.photos,
            onConfirm = portableImportViewModel::confirmImport,
            onDismiss = portableImportViewModel::dismiss,
        )
        is PortableImportViewModel.UiState.Importing -> PortableImportProgressDialog(
            done = importState.done,
            total = importState.total,
            onCancel = portableImportViewModel::cancelImport,
        )
        is PortableImportViewModel.UiState.Finished -> PortableExportResultDialog(
            message = importState.message,
            onDismiss = portableImportViewModel::dismiss,
        )
        PortableImportViewModel.UiState.Idle,
        PortableImportViewModel.UiState.Previewing,
        -> Unit
    }
    SettingsScreen(
        settings = settings,
        backupState = backupState,
        driveBackupState = driveBackupState,
        onThemeChange = viewModel::setTheme,
        onPlaybackSpeedScaleChange = viewModel::setPlaybackSpeedScale,
        onLongCommentReadabilityChange = viewModel::setLongCommentReadability,
        autoPlayOnLaunch = autoPlayOnLaunch,
        onAutoPlayOnLaunchChange = { enabled ->
            settingsScope.launch {
                application.settingsRepository.setAutoPlayOnLaunch(enabled)
            }
        },
        toolbarTwoRows = toolbarTwoRows,
        onToolbarTwoRowsChange = { enabled ->
            settingsScope.launch {
                application.settingsRepository.setEditorToolbarTwoRows(enabled)
            }
        },
        menuCopyAll = menuCopyAll,
        onMenuCopyAllChange = { enabled ->
            settingsScope.launch {
                application.settingsRepository.setEditorMenuCopyAll(enabled)
            }
        },
        menuPdfExport = menuPdfExport,
        onMenuPdfExportChange = { enabled ->
            settingsScope.launch {
                application.settingsRepository.setEditorMenuPdfExport(enabled)
            }
        },
        exportDocx = exportDocx,
        onExportDocxChange = { enabled ->
            settingsScope.launch {
                application.settingsRepository.setEditorExportDocx(enabled)
            }
        },
        outlineSymbols = OutlineSymbolSelection.decode(outlineSymbolsStored),
        onOutlineSymbolsChange = { selection ->
            settingsScope.launch {
                application.settingsRepository.setOutlineSymbolSet(selection.encode())
            }
        },
        outlineChipLabel = OutlineChipLabel.fromStorageId(outlineChipLabelStored),
        onOutlineChipLabelChange = { label ->
            settingsScope.launch {
                application.settingsRepository.setOutlineChipLabel(label.storageId)
            }
        },
        hideTags = hideTags,
        onHideTagsChange = { enabled ->
            settingsScope.launch {
                application.settingsRepository.setEditorHideTags(enabled)
            }
        },
        onCommentSizeScaleChange = viewModel::setCommentSizeScale,
        onStageBackgroundChange = viewModel::setStageBackground,
        themeSeed = themeSeed,
        onThemeSeedChange = { seed ->
            settingsScope.launch { application.settingsRepository.setThemeSeed(seed) }
        },
        themePaletteStyle = themePaletteStyle,
        onThemePaletteStyleChange = { style ->
            settingsScope.launch { application.settingsRepository.setThemePaletteStyle(style) }
        },
        commentFontId = commentFontId,
        userCommentFonts = userCommentFonts,
        commentFontStore = application.commentFontStore,
        onCommentFontIdChange = { id ->
            settingsScope.launch { application.settingsRepository.setCommentFontId(id) }
        },
        onAddCommentFont = {
            pickCommentFont.launch(
                arrayOf(
                    "font/ttf", "font/otf", "font/collection",
                    "application/x-font-ttf", "application/font-sfnt",
                    "application/octet-stream",
                ),
            )
        },
        onDeleteCommentFont = { font ->
            settingsScope.launch {
                val selected = io.github.cragcoffee.memoripple.domain.settings
                    .CommentFontSelection.userStorageId(font)
                if (commentFontId == selected) {
                    application.settingsRepository.setCommentFontId(
                        CommentFont.DEFAULT.storageId,
                    )
                }
                application.settingsRepository.setUserCommentFonts(
                    userCommentFonts.filterNot { it.id == font.id },
                )
                application.commentFontStore.delete(font)
            }
        },

        wallOverlayPlayback = wallOverlayPlayback,
        onWallOverlayPlaybackChange = { enabled ->
            settingsScope.launch {
                application.settingsRepository.setWallOverlayPlayback(enabled)
            }
        },
        commentBackdrop = commentBackdrop,
        onCommentBackdropChange = { enabled ->
            settingsScope.launch {
                application.settingsRepository.setCommentBackdrop(enabled)
            }
        },
        commentTransparency = commentTransparency,
        onCommentTransparencyChange = { value ->
            settingsScope.launch {
                application.settingsRepository.setCommentTransparency(value)
            }
        },
        wallPlaysContent = wallPlaysContent,
        onWallPlaysContentChange = { enabled ->
            settingsScope.launch {
                application.settingsRepository.setWallPlaysContent(enabled)
            }
        },
        onReset = viewModel::resetToDefaults,
        onCreateBackup = viewModel::createBackup,
        onChooseRestoreFile = viewModel::chooseRestoreFile,
        onCancelRestore = viewModel::cancelRestorePreview,
        onConfirmRestore = viewModel::confirmRestore,
        onEnableDriveAutoBackup = viewModel::enableDriveAutoBackup,
        onDisableDriveAutoBackup = viewModel::disableDriveAutoBackup,
        onBackupToDriveNow = viewModel::backupToDriveNow,
        onRestoreFromDrive = viewModel::restoreFromDrive,
        onMessageShown = viewModel::clearMessage,
        onOpenAndroidTtsSettings = { ttsSettingsLauncher.open(context) },
        onOpenSpeechDictionary = onOpenSpeechDictionary,
        speechReadRuby = speechReadRuby,
        onSpeechReadRubyChange = { enabled ->
            settingsScope.launch {
                application.settingsRepository.setSpeechReadRuby(enabled)
            }
        },
        readerSwipeTurns = readerSwipeTurns,
        onReaderSwipeTurnsChange = { enabled ->
            settingsScope.launch {
                application.settingsRepository.setReaderSwipeTurns(enabled)
            }
        },
        onPortableExport = { portableExportViewModel.open() },
        onPortableImport = { portableImportViewModel.open() },
        autoExportEnabled = autoExportEnabled,
        autoExportFolderChosen = autoExportTree.isNotBlank(),
        autoExportFolderLabel = autoExportTree.takeIf { it.isNotBlank() }?.let { raw ->
            android.net.Uri.parse(raw).lastPathSegment?.substringAfter(':')?.ifBlank { null }
        },
        autoExportIntervalDays = autoExportInterval,
        autoExportStatus = when {
            !autoExportEnabled -> "無効"
            autoExportTree.isBlank() -> "保存先が未設定です"
            autoExportLastResult.isNotBlank() -> autoExportLastResult
            else -> "有効（次にアプリを開いたとき、期限が来ていれば書き出します）"
        },
        onAutoExportToggle = { enabled ->
            if (enabled && autoExportTree.isBlank()) {
                enableAutoExportAfterPick = true
                pickAutoExportFolder.launch(null)
            } else {
                settingsScope.launch {
                    application.settingsRepository.setPortableAutoExportEnabled(enabled)
                }
            }
        },
        onAutoExportPickFolder = { pickAutoExportFolder.launch(null) },
        onAutoExportPickInterval = { showAutoExportInterval = true },
        onOpenTemplates = onOpenTemplates,
        onOpenAiModels = onOpenAiModels,
        onOpenToolbarOrder = onOpenToolbarOrder,
        overlayPermissionGranted = overlayPermissionGranted,
        onOpenAndroidOverlaySettings = {
            val intent = overlaySettingsLauncher.createIntent(context)
            intent != null && runCatching { overlaySettingsResult.launch(intent) }.isSuccess
        },
        versionName = BuildConfig.VERSION_NAME,
        onOpenAbout = onOpenAbout,
        onOpenPrivacy = onOpenPrivacy,
        onOpenOssLicenses = onOpenOssLicenses,
        onBack = onBack,
    )
}

private enum class Selection {
    THEME, THEME_SEED, PALETTE_STYLE, STAGE_BACKGROUND, COMMENT_FONT, OUTLINE_SYMBOLS,
    FLOW_MODIFIERS,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    settings: AppSettings,
    backupState: SettingsBackupUiState,
    driveBackupState: DriveBackupUiState,
    onThemeChange: (ThemeMode) -> Unit,
    onPlaybackSpeedScaleChange: (Float) -> Unit,
    onLongCommentReadabilityChange: (Boolean) -> Unit = {},
    autoPlayOnLaunch: Boolean,
    onAutoPlayOnLaunchChange: (Boolean) -> Unit,
    toolbarTwoRows: Boolean,
    onToolbarTwoRowsChange: (Boolean) -> Unit,
    menuCopyAll: Boolean = true,
    onMenuCopyAllChange: (Boolean) -> Unit = {},
    menuPdfExport: Boolean = true,
    onMenuPdfExportChange: (Boolean) -> Unit = {},
    exportDocx: Boolean = false,
    onExportDocxChange: (Boolean) -> Unit = {},
    outlineSymbols: OutlineSymbolSelection = OutlineSymbolSelection.Default,
    onOutlineSymbolsChange: (OutlineSymbolSelection) -> Unit = {},
    outlineChipLabel: OutlineChipLabel = OutlineChipLabel.SYMBOL_AND_WORD,
    onOutlineChipLabelChange: (OutlineChipLabel) -> Unit = {},
    hideTags: Boolean,
    onHideTagsChange: (Boolean) -> Unit,
    onCommentSizeScaleChange: (Float) -> Unit,
    onStageBackgroundChange: (StageBackground) -> Unit,
    themeSeed: ThemeSeed,
    onThemeSeedChange: (ThemeSeed) -> Unit,
    themePaletteStyle: ThemePaletteStyle,
    onThemePaletteStyleChange: (ThemePaletteStyle) -> Unit,
    commentFontId: String,
    userCommentFonts: List<io.github.cragcoffee.memoripple.domain.settings.UserCommentFont>,
    commentFontStore: io.github.cragcoffee.memoripple.data.CommentFontStore,
    onCommentFontIdChange: (String) -> Unit,
    onAddCommentFont: () -> Unit,
    onDeleteCommentFont: (io.github.cragcoffee.memoripple.domain.settings.UserCommentFont) -> Unit,
    wallOverlayPlayback: Boolean,
    onWallOverlayPlaybackChange: (Boolean) -> Unit,
    commentBackdrop: Boolean,
    onCommentBackdropChange: (Boolean) -> Unit,
    commentTransparency: Float,
    onCommentTransparencyChange: (Float) -> Unit,
    wallPlaysContent: Boolean,
    onWallPlaysContentChange: (Boolean) -> Unit,
    onReset: () -> Unit,
    onCreateBackup: () -> Unit,
    onChooseRestoreFile: () -> Unit,
    onCancelRestore: () -> Unit,
    onConfirmRestore: () -> Unit,
    onEnableDriveAutoBackup: () -> Unit,
    onDisableDriveAutoBackup: () -> Unit,
    onBackupToDriveNow: () -> Unit,
    onRestoreFromDrive: () -> Unit,
    onMessageShown: () -> Unit,
    onOpenAndroidTtsSettings: () -> Boolean,
    onOpenSpeechDictionary: () -> Unit,
    speechReadRuby: Boolean = false,
    onSpeechReadRubyChange: (Boolean) -> Unit = {},
    readerSwipeTurns: Boolean = true,
    onReaderSwipeTurnsChange: (Boolean) -> Unit = {},
    onPortableExport: () -> Unit,
    onPortableImport: () -> Unit,
    autoExportEnabled: Boolean,
    autoExportFolderChosen: Boolean,
    autoExportFolderLabel: String?,
    autoExportIntervalDays: Int,
    autoExportStatus: String,
    onAutoExportToggle: (Boolean) -> Unit,
    onAutoExportPickFolder: () -> Unit,
    onAutoExportPickInterval: () -> Unit,
    onOpenTemplates: () -> Unit,
    onOpenAiModels: () -> Unit,
    onOpenToolbarOrder: (EditorToolbarSurface) -> Unit,
    overlayPermissionGranted: Boolean,
    onOpenAndroidOverlaySettings: () -> Boolean,
    versionName: String,
    onOpenAbout: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenOssLicenses: () -> Unit,
    onBack: () -> Unit,
) {
    var selection by remember { mutableStateOf<Selection?>(null) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showFinalRestoreConfirmation by remember { mutableStateOf(false) }
    var ttsSettingsMessage by remember { mutableStateOf<String?>(null) }
    var showDriveDisclosure by remember { mutableStateOf(false) }
    var overlaySettingsMessage by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    var showOverlayDisclosure by remember { mutableStateOf(false) }

    LaunchedEffect(backupState.message) {
        backupState.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            onMessageShown()
        }
    }

    LaunchedEffect(backupState.restorePreview) {
        if (backupState.restorePreview == null) showFinalRestoreConfirmation = false
    }

    LaunchedEffect(ttsSettingsMessage) {
        ttsSettingsMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            ttsSettingsMessage = null
        }
    }
    LaunchedEffect(overlaySettingsMessage) {
        overlaySettingsMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            overlaySettingsMessage = null
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            ProductTopBar(title = "設定", onBack = onBack)
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("settings_list"),
        ) {
            item { SettingsSection("外観") }
            item {
                SettingsRow(
                    label = "外観",
                    value = settings.themeMode.displayName(),
                    testTag = "setting_theme",
                    onClick = { selection = Selection.THEME },
                )
            }
            item {
                SettingsRow(
                    label = "テーマカラー",
                    value = themeSeed.displayName(),
                    testTag = "setting_theme_seed",
                    onClick = { selection = Selection.THEME_SEED },
                )
            }
            item {
                SettingsRow(
                    label = "パレットスタイル",
                    value = themePaletteStyle.displayName(),
                    testTag = "setting_palette_style",
                    onClick = { selection = Selection.PALETTE_STYLE },
                )
            }
            item { SettingsSection("編集") }
            item {
                ProductSettingsRow(
                    title = "ショートカットバーを二段にする",
                    supportingText = "書く道具をすべて見せておきたいときに。通常は一段です",
                    testTag = "setting_toolbar_two_rows",
                    trailingContent = {
                        Switch(
                            checked = toolbarTwoRows,
                            onCheckedChange = onToolbarTwoRowsChange,
                            modifier = Modifier.testTag("toolbar_two_rows_switch"),
                        )
                    },
                )
            }
            item {
                SettingsRow(
                    label = "ショートカットバーの並びと表示（メモ）",
                    value = "メモの書く道具を並べ替える・使わないものを隠す",
                    testTag = "setting_toolbar_order_memo",
                    onClick = { onOpenToolbarOrder(EditorToolbarSurface.MEMO) },
                )
            }
            item {
                SettingsRow(
                    label = "ショートカットバーの並びと表示（アウトライナー）",
                    value = "アウトライナーの道具を並べ替える・使わないものを隠す",
                    testTag = "setting_toolbar_order_outliner",
                    onClick = { onOpenToolbarOrder(EditorToolbarSurface.OUTLINER) },
                )
            }
            item {
                SettingsRow(
                    label = "ショートカットバーの並びと表示（ノート）",
                    value = "エピソードの書く道具を並べ替える・使わないものを隠す",
                    testTag = "setting_toolbar_order_note",
                    onClick = { onOpenToolbarOrder(EditorToolbarSurface.NOTE) },
                )
            }
            item {
                ProductSettingsRow(
                    title = "メニューの「全てのテキストをコピー」",
                    supportingText = "オフにすると、編集画面の⋮メニューから隠れます",
                    testTag = "setting_menu_copy_all",
                    trailingContent = {
                        Switch(
                            checked = menuCopyAll,
                            onCheckedChange = onMenuCopyAllChange,
                            modifier = Modifier.testTag("menu_copy_all_switch"),
                        )
                    },
                )
            }
            item {
                ProductSettingsRow(
                    title = "メニューの「PDFで保存」",
                    supportingText = "オフにすると、編集画面の⋮メニューから隠れます",
                    testTag = "setting_menu_pdf_export",
                    trailingContent = {
                        Switch(
                            checked = menuPdfExport,
                            onCheckedChange = onMenuPdfExportChange,
                            modifier = Modifier.testTag("menu_pdf_export_switch"),
                        )
                    },
                )
            }
            item {
                SettingsRow(
                    label = "アウトライン記号",
                    value = OutlineSymbolRole.entries
                        .joinToString(" ", transform = outlineSymbols::symbol),
                    testTag = "setting_outline_symbols",
                    onClick = { selection = Selection.OUTLINE_SYMBOLS },
                )
            }
            item {
                SettingsRow(
                    label = "行末の流れ方",
                    value = "← → ↑ ↓ などで流れ方を変える記法の一覧",
                    testTag = "setting_flow_modifiers",
                    onClick = { selection = Selection.FLOW_MODIFIERS },
                )
            }
            item {
                ProductSettingsRow(
                    title = "書き出しをWord (.docx)にする",
                    supportingText = "オンにすると、⋮メニューの「Markdownで書き出す」が" +
                        "「Word (.docx)で書き出す」になります",
                    testTag = "setting_export_docx",
                    trailingContent = {
                        Switch(
                            checked = exportDocx,
                            onCheckedChange = onExportDocxChange,
                            modifier = Modifier.testTag("export_docx_switch"),
                        )
                    },
                )
            }
            item {
                ProductSettingsRow(
                    title = "タグを非表示にする",
                    supportingText = "編集画面のタグ行を隠します。メモ一覧のタグはそのままです",
                    testTag = "setting_hide_tags",
                    trailingContent = {
                        Switch(
                            checked = hideTags,
                            onCheckedChange = onHideTagsChange,
                            modifier = Modifier.testTag("hide_tags_switch"),
                        )
                    },
                )
            }
            item {
                SettingsRow(
                    label = "テンプレート",
                    value = "登録したテンプレートの一覧と編集",
                    testTag = "setting_templates",
                    onClick = onOpenTemplates,
                )
            }
            item { SettingsSection("コメント") }
            item {
                ProductSettingsRow(
                    title = "アプリを開いた時にコメントを流す",
                    supportingText = "起動のたびに一度だけ、壁のコメントが流れます",
                    testTag = "setting_auto_play_on_launch",
                    trailingContent = {
                        Switch(
                            checked = autoPlayOnLaunch,
                            onCheckedChange = onAutoPlayOnLaunchChange,
                            modifier = Modifier.testTag("auto_play_on_launch_switch"),
                        )
                    },
                )
            }
            item {
                SettingsSliderRow(
                    title = "再生速度",
                    valueText = "×%.2f".format(settings.playbackSpeedScale),
                    value = settings.playbackSpeedScale,
                    valueRange = PLAYBACK_SPEED_SCALE_RANGE,
                    step = SCALE_STEP,
                    onValueChange = onPlaybackSpeedScaleChange,
                    rowTestTag = "setting_playback_speed",
                    sliderTestTag = "playback_speed_slider",
                )
            }
            item {
                ProductSettingsRow(
                    title = "長いコメントを読みやすくする",
                    supportingText = "長いコメントの表示時間を少し延ばして、読みやすくします",
                    testTag = "setting_long_comment_readability",
                    trailingContent = {
                        Switch(
                            checked = settings.longCommentReadability,
                            onCheckedChange = onLongCommentReadabilityChange,
                            modifier = Modifier.testTag("long_comment_readability_switch"),
                        )
                    },
                )
            }
            item {
                SettingsSliderRow(
                    title = "コメントサイズ",
                    valueText = "${(settings.commentSizeScale * 100).roundToInt()}%",
                    value = settings.commentSizeScale,
                    valueRange = COMMENT_SIZE_SCALE_RANGE,
                    step = SCALE_STEP,
                    onValueChange = onCommentSizeScaleChange,
                    rowTestTag = "setting_comment_size",
                    sliderTestTag = "comment_size_slider",
                )
            }
            item {
                SettingsRow(
                    label = "コメントのフォント",
                    value = io.github.cragcoffee.memoripple.domain.settings
                        .CommentFontSelection.displayName(
                            commentFontId,
                            userCommentFonts,
                        ) { it.displayName() },
                    testTag = "setting_comment_font",
                    onClick = { selection = Selection.COMMENT_FONT },
                )
            }
            item {
                ProductSettingsRow(
                    title = "コメントの文字枠",
                    supportingText = "オンにすると、縁取り文字の代わりに半透明の枠を着て流れます",
                    testTag = "setting_comment_backdrop",
                    trailingContent = {
                        Switch(
                            checked = commentBackdrop,
                            onCheckedChange = onCommentBackdropChange,
                            modifier = Modifier.testTag("comment_backdrop_switch"),
                        )
                    },
                )
            }
            item {
                SettingsSliderRow(
                    title = "コメントの透過",
                    valueText = "${(commentTransparency * 100).roundToInt()}%",
                    value = commentTransparency,
                    valueRange = COMMENT_TRANSPARENCY_RANGE,
                    step = SCALE_STEP,
                    onValueChange = onCommentTransparencyChange,
                    rowTestTag = "setting_comment_transparency",
                    sliderTestTag = "comment_transparency_slider",
                )
            }
            item {
                SettingsRow(
                    label = "コメントステージ背景",
                    value = settings.stageBackground.displayName(),
                    testTag = "setting_stage_background",
                    onClick = { selection = Selection.STAGE_BACKGROUND },
                )
            }
            item {
                ProductSettingsRow(
                    title = "タイトルではなくメモの内容を流す",
                    supportingText = "メモホームの▶と起動時のコメントが、タイトルの代わりに本文を流します",
                    testTag = "setting_wall_plays_content",
                    trailingContent = {
                        Switch(
                            checked = wallPlaysContent,
                            onCheckedChange = onWallPlaysContentChange,
                            modifier = Modifier.testTag("wall_plays_content_switch"),
                        )
                    },
                )
            }
            item {
                ProductSettingsRow(
                    title = "メモホームのコメントをオーバーレイで流す",
                    supportingText = "▶で、コメントが他のアプリの上を流れます",
                    testTag = "setting_wall_overlay_playback",
                    trailingContent = {
                        Switch(
                            checked = wallOverlayPlayback,
                            onCheckedChange = { enabled ->
                                onWallOverlayPlaybackChange(enabled)
                                // The first time it is switched on, Android's permission is still to
                                // be given: the same disclosure the editor's ▶ shows, from here too.
                                if (enabled && !overlayPermissionGranted) showOverlayDisclosure = true
                            },
                            modifier = Modifier.testTag("wall_overlay_playback_switch"),
                        )
                    },
                )
            }
            item { SettingsSection("読み上げ") }
            item {
                ProductSettingsRow(
                    title = "（ ）内をルビとして読み上げる",
                    supportingText = "「草（くさ）」のように読み方を（ ）で書いた言葉は、" +
                        "「くさ」のように（ ）内の読みだけを読み上げます",
                    testTag = "setting_speech_read_ruby",
                    trailingContent = {
                        Switch(
                            checked = speechReadRuby,
                            onCheckedChange = onSpeechReadRubyChange,
                            modifier = Modifier.testTag("speech_read_ruby_switch"),
                        )
                    },
                )
            }
            item { SettingsSection("ノートの閲覧") }
            item {
                ProductSettingsRow(
                    title = "スワイプで前後の話へ",
                    supportingText = "ノートの閲覧モードで、右から左に払うと次の話、左から右に払うと前の話へ、" +
                        "ページが指についてめくれます",
                    testTag = "setting_reader_swipe_turns",
                    trailingContent = {
                        Switch(
                            checked = readerSwipeTurns,
                            onCheckedChange = onReaderSwipeTurnsChange,
                            modifier = Modifier.testTag("reader_swipe_turns_switch"),
                        )
                    },
                )
            }
            item {
                SettingsRow(
                    label = "ユーザー辞書",
                    value = "言葉ごとに読み方を教えられます",
                    testTag = "setting_speech_dictionary",
                    onClick = onOpenSpeechDictionary,
                )
            }
            item {
                SettingsRow(
                    label = "Androidの読み上げ設定を開く",
                    value = "音声の速さや声の高さなどを変更できます",
                    testTag = "open_android_tts_settings",
                    onClick = {
                        if (!onOpenAndroidTtsSettings()) {
                            ttsSettingsMessage = "Androidの読み上げ設定を開けませんでした"
                        }
                    },
                )
            }
            item { SettingsSection("手動バックアップ") }
            item {
                SettingsRow(
                    label = "バックアップを作成",
                    value = "端末上のファイルへ保存",
                    testTag = "create_backup",
                    enabled = !backupState.isBusy && !driveBackupState.isBusy &&
                        backupState.restorePreview == null,
                    onClick = onCreateBackup,
                )
            }
            item {
                SettingsRow(
                    label = "バックアップから復元",
                    value = "現在のデータを選択したバックアップで置き換え",
                    testTag = "restore_backup",
                    enabled = !backupState.isBusy && !driveBackupState.isBusy &&
                        backupState.restorePreview == null,
                    onClick = onChooseRestoreFile,
                )
            }
            item {
                SettingsRow(
                    label = "読める形式で書き出す",
                    value = "メモや日記をMarkdownと写真のZIPに（復元用ではありません）",
                    testTag = "setting_portable_export",
                    enabled = !backupState.isBusy && !driveBackupState.isBusy &&
                        backupState.restorePreview == null,
                    onClick = onPortableExport,
                )
            }
            item {
                SettingsRow(
                    label = "書き出したZIPを取り込む",
                    value = ImportWording.PORTABLE_ROW_DESCRIPTION,
                    testTag = "setting_portable_import",
                    enabled = !backupState.isBusy && !driveBackupState.isBusy &&
                        backupState.restorePreview == null,
                    onClick = onPortableImport,
                )
            }
            item {
                ProductSettingsRow(
                    title = "読める形式の自動書き出し",
                    supportingText = "アプリを開いたとき、期限が来ていれば選んだフォルダへ" +
                        "新しいZIPを保存します。$autoExportStatus",
                    testTag = "setting_auto_export",
                    trailingContent = {
                        Switch(
                            checked = autoExportEnabled,
                            onCheckedChange = onAutoExportToggle,
                            modifier = Modifier.testTag("auto_export_switch"),
                        )
                    },
                )
            }
            if (autoExportEnabled || autoExportFolderChosen) {
                item {
                    SettingsRow(
                        label = "自動書き出しの保存先",
                        value = autoExportFolderLabel ?: "未設定（タップして選択）",
                        testTag = "setting_auto_export_folder",
                        onClick = onAutoExportPickFolder,
                    )
                }
                item {
                    SettingsRow(
                        label = "自動書き出しの間隔",
                        value = if (autoExportIntervalDays >= 7) "毎週" else "毎日",
                        testTag = "setting_auto_export_interval",
                        onClick = onAutoExportPickInterval,
                    )
                }
            }
            if (backupState.isBusy) {
                item {
                    ListItem(
                        headlineContent = { Text(backupState.operation.displayName()) },
                        leadingContent = { CircularProgressIndicator() },
                        modifier = Modifier.testTag("backup_progress"),
                    )
                }
            }
            item { SettingsSection("Google Drive") }
            item {
                ProductSettingsRow(
                    title = "自動バックアップ",
                    supportingText = if (driveBackupState.persisted.autoBackupEnabled) {
                        "有効"
                    } else {
                        "無効"
                    },
                    testTag = "drive_auto_backup",
                    trailingContent = {
                        Switch(
                            checked = driveBackupState.persisted.autoBackupEnabled,
                            enabled = !driveBackupState.isBusy && backupState.restorePreview == null,
                            onCheckedChange = { enabled ->
                                if (enabled) showDriveDisclosure = true
                                else onDisableDriveAutoBackup()
                            },
                            modifier = Modifier.testTag("drive_auto_backup_switch"),
                        )
                    },
                )
            }
            item {
                ProductSettingsRow(
                    title = "バックアップの状態",
                    supportingText = driveBackupState.displayStatus(),
                    testTag = "drive_backup_status",
                )
            }
            item {
                SettingsRow(
                    label = "今すぐバックアップ",
                    value = "アプリ専用のGoogle Drive領域へ保存",
                    testTag = "drive_backup_now",
                    enabled = !driveBackupState.isBusy && !backupState.isBusy &&
                        backupState.restorePreview == null,
                    onClick = onBackupToDriveNow,
                )
            }
            item {
                SettingsRow(
                    label = "Google Driveから復元",
                    value = "現在のデータをDriveのバックアップで置き換え",
                    testTag = "drive_restore",
                    enabled = !driveBackupState.isBusy && !backupState.isBusy &&
                        backupState.restorePreview == null,
                    onClick = onRestoreFromDrive,
                )
            }
            if (driveBackupState.isBusy) {
                item {
                    ListItem(
                        headlineContent = { Text(driveBackupState.runtimeStatus.displayName()) },
                        leadingContent = { CircularProgressIndicator() },
                        modifier = Modifier.testTag("drive_backup_progress"),
                    )
                }
            }
            item { SettingsSection("Androidオーバーレイ") }
            item {
                SettingsRow(
                    label = "他のアプリの上に表示",
                    value = if (overlayPermissionGranted) "許可済み" else "未許可",
                    testTag = "overlay_permission_settings",
                    onClick = { showOverlayDisclosure = true },
                )
            }
            item {
                ProductSettingsRow(
                    title = "表示できない画面について",
                    supportingText = "一部のアプリや保護された画面では表示されない場合があります。",
                    testTag = "overlay_protected_screen_notice",
                )
            }
            item { SettingsSection("AI") }
            item {
                ProductSettingsRow(
                    title = "Local AIモデル",
                    supportingText = "端末内で動くAIモデルのダウンロード・選択・削除",
                    testTag = "settings_ai_models",
                    onClick = onOpenAiModels,
                )
            }
            item { SettingsSection("その他") }
            item {
                TextButton(
                    onClick = { showResetDialog = true },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("reset_settings"),
                ) {
                    Text("設定を初期値に戻す")
                }
            }
            item {
                SettingsSection(
                    title = "アプリ情報",
                    modifier = Modifier.testTag("settings_app_information_section"),
                )
            }
            item {
                NavigationSettingsRow(
                    label = "MemoRippleについて",
                    value = "アプリの考え方と情報",
                    testTag = "settings_about",
                    onClick = onOpenAbout,
                )
            }
            item {
                NavigationSettingsRow(
                    label = "プライバシー",
                    value = "データの扱いを確認",
                    testTag = "settings_privacy",
                    onClick = onOpenPrivacy,
                )
            }
            item {
                NavigationSettingsRow(
                    label = "オープンソースライセンス",
                    value = "利用しているソフトウェアのライセンス",
                    testTag = "settings_oss_licenses",
                    onClick = onOpenOssLicenses,
                )
            }
            item {
                ProductSettingsRow(
                    title = "バージョン",
                    supportingText = versionName,
                    testTag = "settings_version",
                )
            }
            item { Spacer(Modifier.height(ProductSpacing.lg)) }
        }
    }

    when (selection) {
        Selection.THEME -> SelectionDialog(
            title = "外観",
            values = ThemeMode.entries,
            selected = settings.themeMode,
            label = ThemeMode::displayName,
            storageId = ThemeMode::storageId,
            tagPrefix = "setting_theme_option",
            onSelect = {
                onThemeChange(it)
                selection = null
            },
            onDismiss = { selection = null },
        )
        Selection.THEME_SEED -> SeedSelectionDialog(
            selected = themeSeed,
            onSelect = {
                onThemeSeedChange(it)
                selection = null
            },
            onDismiss = { selection = null },
        )
        Selection.PALETTE_STYLE -> SelectionDialog(
            title = "パレットスタイル",
            values = ThemePaletteStyle.entries,
            selected = themePaletteStyle,
            label = ThemePaletteStyle::displayName,
            storageId = ThemePaletteStyle::storageId,
            tagPrefix = "setting_palette_style_option",
            onSelect = {
                onThemePaletteStyleChange(it)
                selection = null
            },
            onDismiss = { selection = null },
        )
        Selection.OUTLINE_SYMBOLS -> OutlineSymbolsDialog(
            selection = outlineSymbols,
            onChange = onOutlineSymbolsChange,
            chipLabel = outlineChipLabel,
            onChipLabelChange = onOutlineChipLabelChange,
            onDismiss = { selection = null },
        )
        Selection.FLOW_MODIFIERS -> FlowModifiersHelpDialog(onDismiss = { selection = null })
        Selection.COMMENT_FONT -> CommentFontDialog(
            selectedId = commentFontId,
            userFonts = userCommentFonts,
            store = commentFontStore,
            builtInLabel = { it.displayName() },
            onSelect = { id ->
                onCommentFontIdChange(id)
                selection = null
            },
            onDeleteUserFont = onDeleteCommentFont,
            onAddFont = onAddCommentFont,
            onDismiss = { selection = null },
        )
        Selection.STAGE_BACKGROUND -> SelectionDialog(
            title = "コメントステージ背景",
            values = StageBackground.entries,
            selected = settings.stageBackground,
            label = StageBackground::displayName,
            storageId = StageBackground::storageId,
            tagPrefix = "setting_stage_background_option",
            onSelect = {
                onStageBackgroundChange(it)
                selection = null
            },
            onDismiss = { selection = null },
        )
        null -> Unit
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("設定を初期値に戻しますか？") },
            text = { Text("テーマ・コメント再生設定が\n初期状態に戻ります。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onReset()
                        showResetDialog = false
                    },
                    modifier = Modifier.testTag("confirm_reset_settings"),
                ) { Text("初期値に戻す") }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("キャンセル") }
            },
        )
    }

    if (showDriveDisclosure) {
        AlertDialog(
            onDismissRequest = { showDriveDisclosure = false },
            title = { Text("Google Drive自動バックアップ") },
            text = {
                Column {
                    Text("自動バックアップを有効にすると、次の内容を端末外のGoogle Driveへ送信します。")
                    Text("・メモ、アウトライン、コメント")
                    Text("・日記、未来コメント（封印中の本文を含む）")
                    Text("・テーマとコメント再生設定")
                    Text("\n保存先はMemoRippleだけが使うアプリ専用領域です。通常のDriveファイル一覧には表示されません。")
                    Text("\nバックアップは圧縮して保存しますが、MemoRipple独自のパスワード暗号化は行いません。")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDriveDisclosure = false
                        onEnableDriveAutoBackup()
                    },
                    modifier = Modifier.testTag("confirm_drive_disclosure"),
                ) { Text("接続して有効にする") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDriveDisclosure = false },
                    modifier = Modifier.testTag("cancel_drive_disclosure"),
                ) { Text("キャンセル") }
            },
            modifier = Modifier.testTag("drive_backup_disclosure"),
        )
    }

    if (showOverlayDisclosure) {
        // The editor's disclosure, tutorial included — 設定 keeps its own tags and button label.
        OverlayPermissionDisclosureDialog(
            onOpenSettings = {
                showOverlayDisclosure = false
                if (!onOpenAndroidOverlaySettings()) {
                    overlaySettingsMessage = "Android設定を開けませんでした"
                }
            },
            onDismiss = { showOverlayDisclosure = false },
            openLabel = "Android設定を開く",
            dialogTag = "settings_overlay_disclosure",
            openTag = "settings_open_overlay_permission",
            cancelTag = "settings_cancel_overlay_disclosure",
        )
    }

    val preview = backupState.restorePreview
    if (preview != null && !showFinalRestoreConfirmation) {
        AlertDialog(
            onDismissRequest = onCancelRestore,
            title = { Text("復元するバックアップ") },
            text = { RestorePreviewDetails(preview) },
            confirmButton = {
                TextButton(
                    onClick = { showFinalRestoreConfirmation = true },
                    modifier = Modifier.testTag("continue_restore"),
                ) { Text("復元へ進む") }
            },
            dismissButton = {
                TextButton(onClick = onCancelRestore) { Text("キャンセル") }
            },
        )
    }

    if (preview != null && showFinalRestoreConfirmation) {
        AlertDialog(
            onDismissRequest = { showFinalRestoreConfirmation = false },
            title = { Text("このバックアップを復元しますか？") },
            text = {
                Column {
                    Text("現在のメモ・コメント・日記・未来コメント・設定は置き換えられます。")
                    RestorePreviewDetails(preview)
                }
            },
            confirmButton = {
                DestructiveTextButton(
                    label = "復元する",
                    onClick = {
                        showFinalRestoreConfirmation = false
                        onConfirmRestore()
                    },
                    modifier = Modifier.testTag("confirm_restore"),
                )
            },
            dismissButton = {
                TextButton(onClick = { showFinalRestoreConfirmation = false }) {
                    Text("戻る")
                }
            },
        )
    }
}

@Composable
private fun SettingsSection(title: String, modifier: Modifier = Modifier) {
    SectionHeader(
        title = title,
        divider = true,
        modifier = modifier.fillMaxWidth().padding(
            start = ProductSpacing.xl,
            end = ProductSpacing.xl,
            top = ProductSpacing.xl,
            bottom = ProductSpacing.sm,
        ),
    )
}

@Composable
private fun NavigationSettingsRow(
    label: String,
    value: String,
    testTag: String,
    onClick: () -> Unit,
) {
    ProductSettingsRow(
        title = label,
        supportingText = value,
        testTag = testTag,
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
            )
        },
        onClick = onClick,
    )
}

/** ゆっくり(×0.80)を左端に、従来の速い(×1.25)を越えて×2.00まで。 */
private val PLAYBACK_SPEED_SCALE_RANGE = 0.80f..2.00f

/** 小さめ(90%)を左端に、従来の大きめ(115%)を越えて160%まで。 */
private val COMMENT_SIZE_SCALE_RANGE = 0.90f..1.60f

private const val SCALE_STEP = 0.05f

/** ニコニコのコメント透過に倣う: 無(0%)から強(80%)まで。 */
private val COMMENT_TRANSPARENCY_RANGE = 0f..0.80f

/**
 * A named amount with a bar under it: the row says what it is and how much, the slider moves
 * it in 5% notches. The exact value is spoken in the row's own description, the way every
 * other settings row already speaks its value.
 */
@Composable
private fun SettingsSliderRow(
    title: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float,
    onValueChange: (Float) -> Unit,
    rowTestTag: String,
    sliderTestTag: String,
) {
    val steps = ((valueRange.endInclusive - valueRange.start) / step).roundToInt() - 1
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ProductSpacing.md, vertical = ProductSpacing.xs)
            .testTag(rowTestTag)
            .semantics { contentDescription = "$title、$valueText" },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                valueText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = { raw ->
                // Snap to the notch so the stored value is a clean 5% step, not a float tail.
                val snapped = (valueRange.start +
                    ((raw - valueRange.start) / step).roundToInt() * step)
                    .coerceIn(valueRange.start, valueRange.endInclusive)
                if (snapped != value) onValueChange(snapped)
            },
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.testTag(sliderTestTag),
        )
    }
}

@Composable
private fun SettingsRow(
    label: String,
    value: String,
    testTag: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    ProductSettingsRow(
        title = label,
        supportingText = value,
        testTag = testTag,
        enabled = enabled,
        onClick = onClick,
    )
}

@Composable
private fun RestorePreviewDetails(preview: RestorePreview) {
    Column(modifier = Modifier.padding(top = 12.dp)) {
        Text("作成日時: ${preview.exportedAt.displayDateTime()}")
        Text("メモ: ${preview.memoCount}件")
        Text("アウトラインとコメント: ${preview.memoCommentCount}件")
        Text("日記: ${preview.diaryCount}件")
        Text("未来コメント: ${preview.futureCommentCount}件")
        Text("写真: ${preview.photoCount}枚")
        Text("設定: ${if (preview.hasSettings) "あり" else "なし"}")
    }
}

private fun BackupOperation.displayName(): String = when (this) {
    BackupOperation.IDLE -> ""
    BackupOperation.PREPARING -> "バックアップを準備しています"
    BackupOperation.WAITING_FOR_SAVE_LOCATION -> "保存先を選択しています"
    BackupOperation.WRITING -> "バックアップを保存しています"
    BackupOperation.WAITING_FOR_SOURCE -> "バックアップを選択しています"
    BackupOperation.READING -> "バックアップを確認しています"
    BackupOperation.RESTORING -> "バックアップを復元しています"
}

private fun DriveRuntimeStatus.displayName(): String = when (this) {
    DriveRuntimeStatus.IDLE -> ""
    DriveRuntimeStatus.AUTHORIZING -> "Google Driveへ接続しています"
    DriveRuntimeStatus.BACKING_UP -> "Google Driveへバックアップしています"
    DriveRuntimeStatus.DOWNLOADING -> "Google Driveのバックアップを確認しています"
    DriveRuntimeStatus.RESTORING -> "Google Driveのバックアップを復元しています"
}

private fun DriveBackupUiState.displayStatus(): String {
    if (!persisted.autoBackupEnabled) return "未接続"
    if (runtimeStatus != DriveRuntimeStatus.IDLE) return runtimeStatus.displayName()
    return when (persisted.lastError) {
        DriveBackupErrorKind.NEEDS_AUTHORIZATION,
        DriveBackupErrorKind.AUTHORIZATION_FAILED,
        -> "再接続が必要です"
        DriveBackupErrorKind.NETWORK -> "通信エラー（変更は保持されています）"
        DriveBackupErrorKind.FORBIDDEN -> "Driveへのアクセスが拒否されました"
        DriveBackupErrorKind.REMOTE_NOT_FOUND -> "バックアップが見つかりません"
        DriveBackupErrorKind.REMOTE_INVALID -> "バックアップを読み込めません"
        DriveBackupErrorKind.BACKUP_FAILED,
        DriveBackupErrorKind.RESTORE_FAILED,
        -> "前回の処理に失敗しました"
        null -> persisted.lastSuccessfulBackupAt?.let {
            "最終成功: ${it.displayDateTime()}"
        } ?: "接続済み・未バックアップ"
    }
}

private fun Long.displayDateTime(): String = RESTORE_DATE_FORMATTER.format(
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()),
)

private val RESTORE_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")

@Composable
private fun FlowModifiersHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("行末の流れ方") },
        text = {
            // The table is the answer someone opened this for; it stands open rather
            // than behind a 詳しく tap.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "行末に矢印を書くと流れ方が変わります。" +
                        "← 左 / → 右 / ↑ 上に固定 / ↓ 下に固定 / ↑↑ 完全固定 / ↺ ループ",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    """
                    本文と修飾子、修飾子同士は半角スペースで区切ります。複数併用できます。
                    例: ! 大事な話 ↑ ++ {赤}

                    ← (<-)　左へ流す（既定）
                    → (->)　右へ流す
                    ↑ (^)　上に固定（3秒）
                    ↓ (v)　下に固定（3秒）
                    ↑↑ / ↓↓ (^^ / vv)　完全固定（停止するまで消えない）
                    ↺ (oo)　ループ（停止するまで繰り返し流れる）
                    ←← / →→ (<<- / ->>)　速く流す
                    ←←← / →→→ (<<<- / ->>>)　超高速
                    +　大　　++　特大　　-　小
                    ×N (xN)　N回連続で流す（2〜5）
                    {色}　色指定: {赤}{青}{緑}{黄}{橙}{紫}{桃}{白}{黒}{灰}
                    　　　英語名や {#ff8800} も使えます
                    ~　揺れながら流す
                    *　点滅
                    ...　溜め（少し遅れて流す）

                    ↑↑ と ↺ は自分では終わらないので、停止を押すまで残ります
                    （オーバーレイは2分で自動的に終わります）。

                    修飾子は閲覧モードや流れるコメントには表示されません。
                    """.trimIndent(),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = ProductSpacing.sm)
                        .testTag("flow_modifiers_detail"),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
        modifier = Modifier.testTag("flow_modifiers_dialog"),
    )
}

@Composable
private fun OutlineSymbolsDialog(
    selection: OutlineSymbolSelection,
    onChange: (OutlineSymbolSelection) -> Unit,
    chipLabel: OutlineChipLabel,
    onChipLabelChange: (OutlineChipLabel) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("アウトライン記号") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "役割ごとに、ショートカットバーが挿入する記号を選べます。どの記号で" +
                        "書いたメモも認識されます。切り替えても既存のメモはそのまま使えます。" +
                        "記号の後に半角スペースを入れた行だけがコメントとして流れます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // What each role does when it flies, standing above the chips that
                // choose its glyph: the reason to pick one is read before picking.
                Text(
                    """
                    記号ごとに、流れるコメントの姿が変わります。

                    ${selection.symbol(OutlineSymbolRole.HEADING)}　見出し: 大きめに、間を置いてゆっくり流れます
                    ${selection.symbol(OutlineSymbolRole.ITEM)}　項目: 標準の速さで流れます
                    ${selection.symbol(OutlineSymbolRole.TASK)}　チェック: 標準。完了は薄く小さめに流れます
                    ${selection.symbol(OutlineSymbolRole.NOTE)}　補足: 小さめ・薄めに流れます
                    ${selection.symbol(OutlineSymbolRole.IMPORTANT)}　重要: 黄色の強調で流れます
                    ${selection.symbol(OutlineSymbolRole.QUESTION)}　疑問: 少し間を置いて流れます

                    行頭のスペース2個ごとに階層が1段深くなり、少し小さく・遅れて流れます。
                    行末の修飾子（← → ↑ ↓ など）はこの上に重なります。
                    """.trimIndent(),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = ProductSpacing.md)
                        .testTag("outline_symbols_flow_detail"),
                )
                // How the bar writes these chips on its own face. The choices wear what
                // they choose, in the writer's own glyph, so the bar is visible from here.
                Text(
                    "ショートカットバーの見え方",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = ProductSpacing.md),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ProductSpacing.xs),
                    modifier = Modifier.padding(top = ProductSpacing.xs),
                ) {
                    OutlineChipLabel.entries.forEach { style ->
                        FilterChip(
                            selected = chipLabel == style,
                            onClick = { onChipLabelChange(style) },
                            label = {
                                Text(
                                    style.textFor(
                                        "見出し",
                                        OutlineSymbolRole.HEADING,
                                        selection,
                                    ),
                                )
                            },
                            modifier = Modifier.testTag(
                                "outline_chip_label_${style.storageId}",
                            ),
                        )
                    }
                }
                outlineSymbolRoleRows().forEach { (role, label) ->
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = ProductSpacing.md),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(ProductSpacing.xs),
                        modifier = Modifier.padding(top = ProductSpacing.xs),
                    ) {
                        // The same glyph may belong to two sets (☐); one chip is enough.
                        val options = OutlineSymbolSet.entries
                            .distinctBy { it.symbol(role) }
                        options.forEach { set ->
                            FilterChip(
                                selected = selection.symbol(role) == set.symbol(role),
                                onClick = { onChange(selection.with(role, set)) },
                                label = { Text(set.symbol(role)) },
                                modifier = Modifier.testTag(
                                    "outline_symbol_${role.name.lowercase()}_${set.storageId}",
                                ),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
        modifier = Modifier.testTag("outline_symbols_dialog"),
    )
}

private fun outlineSymbolRoleRows(): List<Pair<OutlineSymbolRole, String>> = listOf(
    OutlineSymbolRole.HEADING to "見出し",
    OutlineSymbolRole.ITEM to "項目",
    OutlineSymbolRole.TASK to "チェック未完了",
    OutlineSymbolRole.TASK_DONE to "チェック完了",
    OutlineSymbolRole.NOTE to "補足",
    OutlineSymbolRole.IMPORTANT to "重要",
    OutlineSymbolRole.QUESTION to "疑問",
)
/**
 * One choice inside a dialog. A plain row, not a ListItem: a ListItem paints its own surface
 * colour, which inside a dialog's raised container reads as a dark box around the choices.
 * The list of comment fonts has always looked right for exactly this reason.
 */
@Composable
private fun DialogChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    rowTag: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ProductSize.minimumTouchTarget)
            .clickable(onClick = onClick)
            .testTag(rowTag),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

@Composable
private fun <T> SelectionDialog(
    title: String,
    values: List<T>,
    selected: T,
    label: (T) -> String,
    storageId: (T) -> String,
    tagPrefix: String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                values.forEach { value ->
                    DialogChoiceRow(
                        label = label(value),
                        selected = value == selected,
                        onClick = { onSelect(value) },
                        rowTag = "${tagPrefix}_${storageId(value)}",
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

/** テーマカラーの一覧。色の丸を添え、17色が入るようスクロールする。 */
@Composable
private fun SeedSelectionDialog(
    selected: ThemeSeed,
    onSelect: (ThemeSeed) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("テーマカラー") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                ThemeSeed.entries.forEach { seed ->
                    DialogChoiceRow(
                        label = seed.displayName(),
                        selected = seed == selected,
                        onClick = { onSelect(seed) },
                        rowTag = "setting_theme_seed_option_${seed.storageId}",
                        trailing = {
                            Box(
                                Modifier
                                    .size(26.dp)
                                    .background(Color(seed.argb), CircleShape),
                            )
                        },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

private fun ThemeSeed.displayName(): String = when (this) {
    ThemeSeed.DEFAULT -> "既定（ブルー）"
    ThemeSeed.RED -> "レッド"
    ThemeSeed.PINK -> "ピンク"
    ThemeSeed.PURPLE -> "パープル"
    ThemeSeed.DEEP_PURPLE -> "ディープパープル"
    ThemeSeed.INDIGO -> "インディゴ"
    ThemeSeed.BLUE -> "ブルー"
    ThemeSeed.LIGHT_BLUE -> "ライトブルー"
    ThemeSeed.CYAN -> "シアン"
    ThemeSeed.TEAL -> "ティール"
    ThemeSeed.GREEN -> "グリーン"
    ThemeSeed.LIGHT_GREEN -> "ライトグリーン"
    ThemeSeed.LIME -> "ライム"
    ThemeSeed.YELLOW -> "イエロー"
    ThemeSeed.AMBER -> "アンバー"
    ThemeSeed.ORANGE -> "オレンジ"
}

private fun ThemePaletteStyle.displayName(): String = when (this) {
    ThemePaletteStyle.TONAL_SPOT -> "TonalSpot"
    ThemePaletteStyle.NEUTRAL -> "Neutral"
    ThemePaletteStyle.VIBRANT -> "Vibrant"
    ThemePaletteStyle.EXPRESSIVE -> "Expressive"
    ThemePaletteStyle.RAINBOW -> "Rainbow"
    ThemePaletteStyle.FRUIT_SALAD -> "FruitSalad"
    ThemePaletteStyle.MONOCHROME -> "Monochrome"
    ThemePaletteStyle.FIDELITY -> "Fidelity"
    ThemePaletteStyle.CONTENT -> "Content"
}

private fun ThemeMode.displayName(): String = when (this) {
    ThemeMode.SYSTEM -> "システム設定"
    ThemeMode.LIGHT -> "ライト"
    ThemeMode.DARK -> "ダーク"
}

private fun CommentFont.displayName(): String = when (this) {
    CommentFont.DEFAULT -> "デフォルト"
    CommentFont.GOTHIC -> "ゴシック体"
    CommentFont.MINCHO -> "明朝体"
    CommentFont.ROUNDED -> "丸文字体"
}

private fun StageBackground.displayName(): String = when (this) {
    StageBackground.BLACK -> "黒"
    StageBackground.DARK_GRAY -> "ダークグレー"
    StageBackground.LIGHT -> "ライト"
    StageBackground.THEME -> "テーマ"
}
