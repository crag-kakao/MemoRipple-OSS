package io.github.cragcoffee.memoripple.portableexport

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import io.github.cragcoffee.memoripple.data.SettingsRepository
import io.github.cragcoffee.memoripple.domain.export.PortableAutoExportPolicy
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.first

/**
 * 読める形式の自動書き出し, without WorkManager, services, or notifications: opening the app
 * is the trigger. When the feature is on, a folder has been granted, and the interval has
 * passed, one fresh ZIP (trash excluded, photos untouched) is written into that folder — a
 * new file every time, never overwriting or deleting what is already there. Everything about
 * it is device-local: the folder grant, the rhythm, the last result.
 */
class PortableAutoExporter(
    private val engine: PortableExportEngine,
    private val settingsRepository: SettingsRepository,
    private val contentResolver: ContentResolver,
    private val nowMillis: () -> Long,
    /** Creates the destination document in the granted tree; replaceable for tests. */
    private val createDocument: (treeUri: Uri, fileName: String) -> Uri? = { tree, name ->
        runCatching {
            DocumentsContract.createDocument(
                contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(
                    tree,
                    DocumentsContract.getTreeDocumentId(tree),
                ),
                "application/zip",
                name,
            )
        }.getOrNull()
    },
) {

    private val running = AtomicBoolean(false)

    /** Called on app launch. Quiet unless the schedule says it is time. */
    suspend fun runIfDue() {
        if (!settingsRepository.portableAutoExportEnabled.first()) return
        val tree = settingsRepository.portableAutoExportTreeUri.first()
        if (tree.isBlank()) return
        val interval = settingsRepository.portableAutoExportIntervalDays.first()
        val lastRun = settingsRepository.portableAutoExportLastRunMillis.first()
        if (!PortableAutoExportPolicy.isDue(nowMillis(), lastRun, interval)) return
        runNow(Uri.parse(tree))
    }

    /** One export into [treeUri], recorded win or lose; false when one is already running. */
    suspend fun runNow(treeUri: Uri): Boolean {
        if (!running.compareAndSet(false, true)) return false
        try {
            val now = nowMillis()
            val stamp = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            val destination = createDocument(treeUri, engine.suggestedFileName(now))
            if (destination == null) {
                settingsRepository.setPortableAutoExportLastRun(
                    now,
                    "$stamp 失敗（保存先フォルダに書き込めませんでした）",
                )
                return false
            }
            val result = runCatching {
                engine.export(
                    includeTrash = false,
                    stripPhotoMetadata = false,
                    destination = destination,
                    nowMillis = now,
                )
            }.getOrNull()
            val succeeded = result is PortableExportEngine.ExportResult.Done
            settingsRepository.setPortableAutoExportLastRun(
                now,
                if (succeeded) "$stamp に書き出しました" else "$stamp 失敗（書き出せませんでした）",
            )
            return succeeded
        } finally {
            running.set(false)
        }
    }
}
