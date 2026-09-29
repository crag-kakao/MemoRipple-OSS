package io.github.cragcoffee.memoripple.data.ai.models

import io.github.cragcoffee.memoripple.domain.ai.models.CommitResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * Phase 5 RED (docs/AI_MODEL_MANAGEMENT.md §storage): the model directory under the no-backup
 * root — `models/<id>/model.gguf`, written as `model.gguf.part` first, committed only after the
 * SHA-256 matches, by an atomic rename; a mismatch or a partial file is never "installed".
 */
class FileModelStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var store: FileModelStore

    @org.junit.Before
    fun setUp() {
        root = tmp.newFolder("no_backup")
        store = FileModelStore(root)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun theLayoutIsOneDirectoryPerModelUnderTheNoBackupRoot() {
        val f = store.files("qwen3-4b-instruct-2507")
        assertEquals(File(root, "models/qwen3-4b-instruct-2507"), f.directory)
        assertEquals(File(root, "models/qwen3-4b-instruct-2507/model.gguf"), f.file)
        assertEquals(File(root, "models/qwen3-4b-instruct-2507/model.gguf.part"), f.partFile)
    }

    @Test
    fun anIdThatIsNotPathSafeIsRefused() {
        listOf("../etc", "a/b", "Qwen", "", "x".repeat(65), "id with space").forEach { bad ->
            val r = runCatching { store.files(bad) }
            assertTrue("refused: '$bad'", r.isFailure && r.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun nothingOnDiskIsNotInstalledAndNotPartial() {
        assertNull(store.installedBytes("qwen3-4b-instruct-2507"))
        assertEquals(0L, store.partialBytes("qwen3-4b-instruct-2507"))
        assertTrue(store.installedModelIds().isEmpty())
    }

    @Test
    fun aPartialFileIsNotInstalledButItsLengthIsKnownForAResume() {
        store.openPart("m1", resume = false).use { it.write(ByteArray(1000)) }
        assertNull(store.installedBytes("m1"))
        assertEquals(1000L, store.partialBytes("m1"))
        assertTrue(store.installedModelIds().isEmpty())
        // resume appends; a fresh start truncates
        store.openPart("m1", resume = true).use { it.write(ByteArray(500)) }
        assertEquals(1500L, store.partialBytes("m1"))
        store.openPart("m1", resume = false).use { it.write(ByteArray(10)) }
        assertEquals(10L, store.partialBytes("m1"))
    }

    @Test
    fun aMatchingHashCommitsThePartAtomicallyAndTheModelIsInstalled() {
        val bytes = ByteArray(100_000) { (it * 7).toByte() }
        store.openPart("m1", resume = false).use { it.write(bytes) }
        val r = runBlocking { store.verifyAndCommit("m1", sha256(bytes)) }
        assertEquals(CommitResult.Committed(100_000L), r)
        assertEquals(100_000L, store.installedBytes("m1"))
        assertEquals(0L, store.partialBytes("m1"))
        assertFalse(store.files("m1").partFile.exists())
        assertTrue(store.files("m1").file.readBytes().contentEquals(bytes))
        assertEquals(setOf("m1"), store.installedModelIds())
    }

    @Test
    fun aMismatchNeverInstallsAndTheCorruptPartIsRemoved() {
        store.openPart("m1", resume = false).use { it.write(ByteArray(5000) { 1 }) }
        val r = runBlocking { store.verifyAndCommit("m1", sha256(ByteArray(5000) { 2 })) }
        assertEquals(CommitResult.Mismatch, r)
        assertNull(store.installedBytes("m1"))
        assertEquals(0L, store.partialBytes("m1"))
        assertFalse(store.files("m1").file.exists())
        assertFalse(store.files("m1").partFile.exists())
    }

    @Test
    fun aCommitWithoutAPartIsNoPartAndChangesNothing() {
        assertEquals(CommitResult.NoPart, runBlocking { store.verifyAndCommit("m1", sha256(ByteArray(0))) })
        assertNull(store.installedBytes("m1"))
    }

    @Test
    fun anInstalledFileIsNeverOverwrittenByAPartUntilItVerifies() {
        val good = ByteArray(10) { 9 }
        store.openPart("m1", resume = false).use { it.write(good) }
        runBlocking { store.verifyAndCommit("m1", sha256(good)) }
        store.openPart("m1", resume = false).use { it.write(ByteArray(3)) }
        assertEquals(10L, store.installedBytes("m1"))
        assertEquals(CommitResult.Mismatch, runBlocking { store.verifyAndCommit("m1", sha256(ByteArray(99))) })
        assertEquals(10L, store.installedBytes("m1"))
        assertTrue(store.files("m1").file.readBytes().contentEquals(good))
    }

    @Test
    fun deleteRemovesTheModelItsPartAndItsDirectoryAndNothingElse() {
        store.openPart("m1", resume = false).use { it.write(ByteArray(10)) }
        runBlocking { store.verifyAndCommit("m1", sha256(ByteArray(10))) }
        store.openPart("m2", resume = false).use { it.write(ByteArray(4)) }
        val other = File(root, "something-else.txt").also { it.writeText("keep") }
        assertTrue(runBlocking { store.delete("m1") })
        assertFalse(store.files("m1").directory.exists())
        assertEquals(4L, store.partialBytes("m2"))
        assertTrue(other.exists())
        assertTrue(runBlocking { store.delete("m2") })
        assertFalse(runBlocking { store.delete("m2") })
        assertTrue(File(root, "models").exists())
    }

    @Test
    fun usableBytesComesFromTheRootVolume() {
        assertTrue(store.usableBytes() > 0)
    }
}
