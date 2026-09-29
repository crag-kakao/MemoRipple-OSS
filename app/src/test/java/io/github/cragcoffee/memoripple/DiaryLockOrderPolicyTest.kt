package io.github.cragcoffee.memoripple

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lock order holds (docs/DIARY_LOCK_ORDER.md): **M_file → M_diary → M_future → T**. The one
 * way to break it is to call a diary or Future Diary operation that takes its lock from inside a
 * Room transaction (T → M_diary / M_future, the reverse of restore and the editor's autosave) — the
 * AI's journal append did exactly that until 2026-09-25. This reads the sources the way the other
 * policy tests do, but by structure rather than by one spelling: the lock-taking methods are
 * derived from the two repositories themselves (any method whose body takes their mutex), the
 * receivers of those types are found in each file's declarations, and each transaction block is
 * cut out by brace matching. The checker is tested against the old inversion, so it cannot pass by
 * finding nothing.
 */
class DiaryLockOrderPolicyTest {
    private val root = sequenceOf(File("src/main/java/io/github/cragcoffee/memoripple"), File("app/src/main/java/io/github/cragcoffee/memoripple")).first { it.isDirectory }
    private val sources: Map<String, String> by lazy {
        root.walkTopDown().filter { it.extension == "kt" }.associate { it.relativeTo(root).path to it.readText() }
    }
    private fun source(path: String) = sources.getValue(path)

    /** Methods of a class whose own body takes one of [locks] (`x.withLock`). */
    private fun lockTakingMethods(text: String, locks: List<String>): Set<String> {
        val result = mutableSetOf<String>()
        Regex("""fun\s+(?:<[^>]+>\s+)?(\w+)\s*\(""").findAll(text).forEach { match ->
            val name = match.groupValues[1]
            val body = functionBody(text, match.range.last) ?: return@forEach
            if (locks.any { lock -> body.contains("$lock.withLock") }) result += name
        }
        return result
    }

    /** From the parameter list opened at [openParen]: the function's body — `= expr…` up to the next declaration, or a `{ … }` block. */
    private fun functionBody(text: String, openParen: Int): String? {
        var depth = 0
        var i = openParen
        while (i < text.length) {
            when (text[i]) { '(' -> depth++; ')' -> { depth--; if (depth == 0) break } }
            i++
        }
        val rest = text.substring(i + 1)
        val eq = rest.indexOf('=')
        val brace = rest.indexOf('{')
        val nextFun = Regex("""\n\s*(?:private |internal |override |public )*(?:suspend )?fun\s""").find(rest)?.range?.first ?: rest.length
        return when {
            eq in 0 until nextFun && (brace < 0 || eq < brace || rest.substring(0, brace).contains("=")) -> rest.substring(0, nextFun)
            brace in 0 until nextFun -> block(rest, brace)
            else -> null
        }
    }

    /** The text of the `{ … }` block whose opening brace is at [open]. */
    private fun block(text: String, open: Int): String {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return text.substring(open, i + 1) }
            }
        }
        return text.substring(open)
    }

    /** Every transaction block in [text]: `withTransaction { … }` / `inTransaction { … }`. */
    private fun transactionBlocks(text: String): List<String> =
        Regex("""(?:withTransaction|inTransaction)\s*(?:\([^)]*\))?\s*\{""").findAll(text).map { block(text, it.range.last) }.toList()

    /** Names this file gives to values of [types] (parameters, properties, lazies). */
    private fun receivers(text: String, types: List<String>): Set<String> {
        val names = mutableSetOf<String>()
        types.forEach { type ->
            Regex("""(\w+)\s*:\s*$type\b""").findAll(text).forEach { names += it.groupValues[1] }
            Regex("""val\s+(\w+)\s+by\s+lazy\s*\{\s*$type\(""").findAll(text).forEach { names += it.groupValues[1] }
        }
        return names
    }

    /** Calls, inside a transaction block, of a lock-taking method on a receiver of the two repositories. */
    private fun violations(text: String, receivers: Set<String>, methods: Set<String>, selfMethods: Set<String> = emptySet()): List<String> =
        transactionBlocks(text).flatMap { tx ->
            val onReceivers = receivers.flatMap { r -> methods.filter { m -> Regex("""\b$r\s*\??\.\s*$m\s*[({]""").containsMatchIn(tx) }.map { "$r.$it" } }
            val onSelf = selfMethods.filter { m -> Regex("""(?<![.\w])$m\s*\(""").containsMatchIn(tx) }
            onReceivers + onSelf
        }

    private val diaryLocks = lockTakingMethods(source("data/DiaryRepository.kt"), listOf("operationMutex"))
    private val futureLocks = lockTakingMethods(source("data/FutureDiaryCommentRepository.kt"), listOf("mutex"))

    @Test
    fun theLockTakingOperationsAreFoundFromTheRepositoriesThemselves() {
        listOf("createEntry", "saveBody", "saveBlocks", "mergeTextBlocks", "appendIfUnchanged", "withOperationLock").forEach {
            assertTrue("DiaryRepository.$it takes the diary lock: $diaryLocks", it in diaryLocks)
        }
        listOf("create", "markDueDelivered", "withOperationLock").forEach {
            assertTrue("FutureDiaryCommentRepository.$it takes its lock: $futureLocks", it in futureLocks)
        }
        assertTrue("a lock-held helper takes no lock", "markDueDeliveredLockHeld" !in futureLocks)
    }

    @Test
    fun noTransactionAnywhereCallsADiaryOrFutureDiaryOperationThatTakesItsLock() {
        val types = listOf("DiaryRepository", "FutureDiaryCommentRepository")
        var scanned = 0
        val found = sources.flatMap { (path, text) ->
            scanned += transactionBlocks(text).size
            val self = when (path) {
                "data/DiaryRepository.kt" -> diaryLocks
                "data/FutureDiaryCommentRepository.kt" -> futureLocks
                else -> emptySet()
            }
            violations(text, receivers(text, types), diaryLocks + futureLocks, self).map { "$path: $it" }
        }
        assertTrue("the scan saw the app's transactions ($scanned)", scanned > 20)
        assertEquals("T → M_diary / M_future (docs/DIARY_LOCK_ORDER.md)", emptyList<String>(), found)
    }

    @Test
    fun theCheckerCatchesTheOldInversion() {
        // The AI's journal append before 2026-09-25.
        val old = """
            class RepositoryDocumentAccess(private val database: AppDatabase, private val diary: DiaryRepository) {
                private suspend fun appendToJournal(ref: DocumentRef, text: String, expected: Long) =
                    database.withTransaction {
                        val existing = diary.findById(ref.id) ?: return@withTransaction NotFound
                        when (val result = diary.saveBody(existing.id, text, releaseIfBlank = false)) { else -> Done }
                    }
            }
        """.trimIndent()
        assertEquals(listOf("diary.saveBody"), violations(old, receivers(old, listOf("DiaryRepository")), diaryLocks))
        // A read (no lock) inside a transaction is fine.
        val read = "class X(private val diary: DiaryRepository) { suspend fun f() = db.withTransaction { diary.findById(1) } }"
        assertEquals(emptyList<String>(), violations(read, receivers(read, listOf("DiaryRepository")), diaryLocks))
    }

    @Test
    fun restoreTakesTheLocksInTheAllowedOrder() {
        val engine = source("backup/BackupEngine.kt")
        val restore = engine.substring(engine.indexOf("suspend fun restore("))
        val file = restore.indexOf("installForRestore(")
        val diary = restore.indexOf("diaryRepository.withOperationLock")
        val future = restore.indexOf("futureDiaryCommentRepository.withOperationLock")
        val tx = restore.indexOf("database.withTransaction")
        assertTrue("M_diary → M_future → T in writeRoom", diary in 0 until future && future < tx)
        assertTrue("M_file wraps writeRoom", file > tx) // installForRestore(installs, writeRoom) runs writeRoom inside the file lock
        // The allowed order is written where the locks live.
        listOf("data/DiaryRepository.kt").forEach { assertTrue("$it states the order", source(it).contains("M_file → M_diary → M_future → T")) }
    }
}
