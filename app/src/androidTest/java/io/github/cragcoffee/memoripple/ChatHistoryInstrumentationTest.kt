package io.github.cragcoffee.memoripple

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatMessageKind
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatRole
import io.github.cragcoffee.memoripple.domain.ai.conversation.SafeConversationContext
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.domain.documents.DocumentRef
import io.github.cragcoffee.memoripple.domain.documents.DocumentSummary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 8 RED 4–17, 23, 28 (docs/AI_CONVERSATION_HISTORY.md): the persistent history and the safe
 * context on the real database — conversations ordered by activity, messages in order, the
 * latest result refs and the anchor per conversation, a delete that removes exactly the
 * conversation's rows and touches no document.
 */
@RunWith(AndroidJUnit4::class)
class ChatHistoryInstrumentationTest {
    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication
    private val history get() = application.chatHistoryRepository

    @Before
    fun startEmpty() { runBlocking { application.database.clearAllTables() } }

    private fun memo(title: String): Long = runBlocking { application.database.memoDao().insert(MemoEntity(title = title, body = "本文", createdAt = 1, updatedAt = 1, kind = "memo")) }

    @Test
    fun conversationsAreListedNewestActivityFirstAndMessagesInOrder() = runBlocking {
        val a = history.create("最初の会話")
        val b = history.create("二つ目の会話")
        history.append(a, ChatRole.USER, ChatMessageKind.TEXT, "昨日の日記を探して")
        history.append(a, ChatRole.ASSISTANT, ChatMessageKind.RESULT, "3件見つかりました。")
        val list = history.conversations().first()
        assertEquals(listOf(a, b), list.map { it.id })   // a was touched last
        assertEquals("最初の会話", list.first().title)
        assertTrue(list.first().updatedAt >= list.first().createdAt)
        val messages = history.messages(a).first()
        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT), messages.map { it.role })
        assertEquals(listOf("昨日の日記を探して", "3件見つかりました。"), messages.map { it.text })
        assertEquals(ChatMessageKind.RESULT, messages.last().kind)
        assertTrue(history.messages(b).first().isEmpty())
    }

    @Test
    fun theSafeContextIsPerConversationAndStoresKindIdAndTheShownTitle() = runBlocking {
        val m1 = memo("MemoRipple開発"); val m2 = memo("買い物")
        val a = history.create("a"); val b = history.create("b")
        history.setLatestResults(a, listOf(DocumentSummary(DocumentRef(DocumentKind.MEMO, m1), "MemoRipple開発", 1, 1), DocumentSummary(DocumentRef(DocumentKind.MEMO, m2), "買い物", 1, 1)))
        history.setAnchor(a, DocumentRef(DocumentKind.MEMO, m1))
        val ctx = history.context(a)
        assertEquals(listOf(m1, m2), ctx.latestResults.map { it.ref.id })
        assertEquals(listOf("MemoRipple開発", "買い物"), ctx.latestResults.map { it.title })
        assertEquals(listOf("result_1: MemoRipple開発", "result_2: 買い物"), ctx.resultContext().shownLines())
        assertEquals(DocumentRef(DocumentKind.MEMO, m1), ctx.lastDocumentAnchor)
        assertEquals("another conversation sees nothing", SafeConversationContext.EMPTY, history.context(b))
        // a new result set replaces the old one; the anchor is independent of it
        history.setLatestResults(a, listOf(DocumentSummary(DocumentRef(DocumentKind.MEMO, m2), "買い物", 1, 1)))
        assertEquals(listOf(m2), history.context(a).latestResults.map { it.ref.id })
        assertEquals(DocumentRef(DocumentKind.MEMO, m1), history.context(a).lastDocumentAnchor)
        history.setAnchor(a, null)
        assertNull(history.context(a).lastDocumentAnchor)
    }

    @Test
    fun deletingAConversationRemovesItsMessagesAndContextAndTouchesNoDocument() = runBlocking {
        val m1 = memo("MemoRipple開発")
        val a = history.create("a"); val b = history.create("b")
        history.append(a, ChatRole.USER, ChatMessageKind.TEXT, "x"); history.append(b, ChatRole.USER, ChatMessageKind.TEXT, "y")
        history.setLatestResults(a, listOf(DocumentSummary(DocumentRef(DocumentKind.MEMO, m1), "MemoRipple開発", 1, 1)))
        history.setAnchor(a, DocumentRef(DocumentKind.MEMO, m1))
        val memosBefore = application.database.memoDao().allIds()
        history.delete(a)
        assertNull(history.conversation(a))
        assertEquals(listOf(b), history.conversations().first().map { it.id })
        assertTrue(history.messages(a).first().isEmpty())
        assertEquals(SafeConversationContext.EMPTY, history.context(a))
        assertEquals("y", history.messages(b).first().single().text)
        assertEquals(memosBefore, application.database.memoDao().allIds())
        assertEquals("本文", application.database.memoDao().findById(m1)!!.body)
        history.deleteAll()
        assertTrue(history.conversations().first().isEmpty())
        assertEquals(memosBefore, application.database.memoDao().allIds())
    }

    @Test
    fun aDeletedDocumentLeavesTheTranscriptAloneOnlyTheResolverSaysNotFound() = runBlocking {
        val m1 = memo("MemoRipple開発")
        val a = history.create("a")
        history.append(a, ChatRole.ASSISTANT, ChatMessageKind.RESULT, "「MemoRipple開発」を開きました。")
        history.setAnchor(a, DocumentRef(DocumentKind.MEMO, m1))
        application.database.memoDao().deletePermanently(m1)
        assertEquals("the transcript is history, not a foreign key", 1, history.messages(a).first().size)
        assertEquals("the anchor stays; the Resolver re-validates it", DocumentRef(DocumentKind.MEMO, m1), history.context(a).lastDocumentAnchor)
    }
}
