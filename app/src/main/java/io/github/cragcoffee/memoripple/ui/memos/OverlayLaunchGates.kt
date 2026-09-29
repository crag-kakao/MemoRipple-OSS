package io.github.cragcoffee.memoripple.ui.memos

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import io.github.cragcoffee.memoripple.overlay.OverlaySettingsLauncher
import kotlinx.coroutines.launch

/**
 * The dialogs and system round-trips an [OverlayLaunchFlow] walks through — replace-running
 * confirmation, the "display over other apps" disclosure with its trip to Android settings, and
 * the notification choice. One composable because the editor's ▶ and the wall's ▶ walk the same
 * gates; the flow they drive stays with the screen, which owns the request being walked.
 */
@Composable
internal fun OverlayLaunchGates(
    overlayLaunch: OverlayLaunchFlow,
    onStopOverlay: () -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    val context = LocalContext.current
    val snackbarScope = rememberCoroutineScope()
    val overlaySettingsLauncher = remember { OverlaySettingsLauncher() }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        overlayLaunch.startPendingNow()
    }
    val overlaySettingsResult = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (!overlayLaunch.permissionSettingsReturned()) {
            snackbarScope.launch {
                snackbarHostState.showSnackbar("オーバーレイ権限が許可されませんでした")
            }
        }
    }

    if (overlayLaunch.showSetupIntro) {
        AlertDialog(
            onDismissRequest = { overlayLaunch.cancelSetupIntro() },
            title = { Text("他のアプリの上にコメントを流す") },
            text = {
                Text(
                    "MemoRippleを離れても、コメントだけを他のアプリの上に表示できます。\n\n" +
                        "・画面の操作を邪魔しません\n" +
                        "・画面内容を読み取ったり録画したりしません\n" +
                        "・最大約2分で自動終了します\n" +
                        "・通知からいつでも停止できます",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { overlayLaunch.beginSetupFromIntro() },
                    modifier = Modifier.testTag("begin_overlay_setup"),
                ) { Text("設定を始める") }
            },
            dismissButton = {
                TextButton(
                    onClick = { overlayLaunch.cancelSetupIntro() },
                    modifier = Modifier.testTag("cancel_overlay_setup_intro"),
                ) { Text("キャンセル") }
            },
            modifier = Modifier.testTag("overlay_setup_intro"),
        )
    }

    if (overlayLaunch.replacementRequest != null) {
        AlertDialog(
            onDismissRequest = { overlayLaunch.dismissReplacement() },
            title = { Text("再生中のオーバーレイを切り替えますか？") },
            text = { Text("現在の再生を停止して、選択した内容で新しく再生します。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        overlayLaunch.confirmReplacement()
                        onStopOverlay()
                    },
                    modifier = Modifier.testTag("confirm_replace_overlay"),
                ) { Text("停止して再生") }
            },
            dismissButton = {
                TextButton(
                    onClick = { overlayLaunch.dismissReplacement() },
                    modifier = Modifier.testTag("cancel_replace_overlay"),
                ) { Text("キャンセル") }
            },
            modifier = Modifier.testTag("replace_overlay_confirmation"),
        )
    }

    if (overlayLaunch.showPermissionDisclosure) {
        // The same disclosure 設定's switch shows, tutorial included.
        OverlayPermissionDisclosureDialog(
            onOpenSettings = {
                overlayLaunch.leaveForPermissionSettings()
                val intent = overlaySettingsLauncher.createIntent(context)
                val launched = intent != null &&
                    runCatching { overlaySettingsResult.launch(intent) }.isSuccess
                if (!launched) {
                    overlayLaunch.permissionSettingsUnavailable()
                    snackbarScope.launch {
                        snackbarHostState.showSnackbar("Android設定を開けませんでした")
                    }
                }
            },
            onDismiss = { overlayLaunch.cancelPermissionDisclosure() },
        )
    }

    if (overlayLaunch.showNotificationDisclosure) {
        AlertDialog(
            onDismissRequest = { overlayLaunch.startPendingNow() },
            title = { Text("再生中は通知が表示されます") },
            text = {
                Text(
                    "Androidの仕組みにより、オーバーレイ再生中は停止用の通知が表示されます。" +
                        "通知の「停止」からいつでも終了できます。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        overlayLaunch.leaveForNotificationPermission()
                        runCatching {
                            notificationPermissionLauncher.launch(
                                Manifest.permission.POST_NOTIFICATIONS,
                            )
                        }.onFailure { overlayLaunch.startPendingNow() }
                    },
                    modifier = Modifier.testTag("request_overlay_notifications"),
                ) { Text("通知を許可") }
            },
            dismissButton = {
                TextButton(
                    onClick = { overlayLaunch.startPendingNow() },
                    modifier = Modifier.testTag("skip_overlay_notifications"),
                ) { Text("今は許可しない") }
            },
            modifier = Modifier.testTag("overlay_notification_disclosure"),
        )
    }
}
