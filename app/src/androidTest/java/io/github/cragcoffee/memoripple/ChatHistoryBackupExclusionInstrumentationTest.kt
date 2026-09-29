package io.github.cragcoffee.memoripple

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.backup.BackupInspectionResult
import io.github.cragcoffee.memoripple.backup.BackupRestoreResult
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatMessageKind
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatRole
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.zip.ZipInputStream

/**
 * Phase 8 RED 51, 52: chat history is device-local — the portable backup (format 18, unchanged)
 * carries none of it, a restore leaves the documents whole, and the Android backup rules still
 * exclude everything.
 */
@RunWith(AndroidJUnit4::class)
class ChatHistoryBackupExclusionInstrumentationTest {
    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication

    @Before
    fun startEmpty() { runBlocking { application.database.clearAllTables() } }

    @Test
    fun aBackupMadeWithChatHistoryCarriesNoneOfItAndRestoresTheDocuments() = runBlocking {
        val memoId = application.database.memoDao().insert(MemoEntity(title = "MemoRipple開発", body = "## 進捗", createdAt = 1, updatedAt = 2, kind = "memo"))
        val history = application.chatHistoryRepository
        val c = history.create("秘密の会話タイトル")
        history.append(c, ChatRole.USER, ChatMessageKind.TEXT, "ユーザーだけの入力文 SECRET-USER-9f3")
        history.append(c, ChatRole.ASSISTANT, ChatMessageKind.RESULT, "アシスタントの応答 SECRET-ASSISTANT-7c1")
        val engine = application.backupEngine
        val prepared = engine.prepareBackup()
        val texts = StringBuilder()
        ZipInputStream(prepared.file.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                texts.append(entry.name).append('\n')
                if (!entry.isDirectory) texts.append(zip.readBytes().toString(Charsets.UTF_8)).append('\n')
            }
        }
        val payload = texts.toString()
        assertTrue("the memo is in the backup", payload.contains("MemoRipple開発"))
        listOf("秘密の会話タイトル", "SECRET-USER-9f3", "SECRET-ASSISTANT-7c1", "chat_conversations", "chat_messages", "chatConversations", "conversations\"").forEach {
            assertFalse("the backup carries $it", payload.contains(it))
        }
        assertTrue(payload.contains("\"formatVersion\":23") || payload.contains("\"formatVersion\": 23"))   // 23 since the outline photo rows (2026-09-25); the history is still outside it
        val ready = engine.inspect(prepared.file) as BackupInspectionResult.Ready
        application.database.memoDao().deletePermanently(memoId)
        assertEquals(BackupRestoreResult.Success, engine.restore(ready.candidate))
        assertEquals(listOf("MemoRipple開発"), application.database.memoDao().allIds().map { application.database.memoDao().findById(it)!!.title })
        assertEquals("the restore did not touch the history either way", 2, history.messages(c).first().size)
        prepared.discard()
    }
}
