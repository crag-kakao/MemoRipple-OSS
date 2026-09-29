package io.github.cragcoffee.memoripple.data

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Backup, restore, Drive and portable export all stage their work in the cache directory and
 * clean up after themselves — unless the process dies in the middle. The sweeper, run at
 * start, removes what such a death left behind and nothing else: only the app's own staging
 * names, only when they are older than any operation could still be.
 */
class StagingSweeperTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val now = 1_700_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun file(path: String, ageMillis: Long): File {
        val file = File(temporaryFolder.root, path)
        file.parentFile?.mkdirs()
        file.writeText("x")
        file.setLastModified(now - ageMillis)
        return file
    }

    @Test
    fun oldStagingIsRemovedFreshStagingAndUnrelatedFilesAreKept() = runBlocking {
        val oldBackup = file("backup/backup-a.mrbackup", 2 * day)
        val freshBackup = file("backup/backup-b.mrbackup", 1000)
        val oldImport = file("backup/import/saf-a.mrbackup", 3 * day)
        val oldRestoreBlob = file("backup/restore/restore-a/0123abcd", 2 * day)
        val freshRestoreBlob = file("backup/restore/restore-b/0123abcd", 1000)
        val oldDownload = file("backup/drive/download-a.mrbackup", 2 * day)
        val oldExport = file("portable-export123.zip", 2 * day)
        val oldPortableImport = file("portable-import456.tmp", 2 * day)
        val freshExport = file("portable-export789.pdf", 1000)
        val unrelated = file("something-else.bin", 10 * day)
        val lock = file("memo-ripple.db.lck", 10 * day)

        val removed = StagingSweeper(temporaryFolder.root).sweep(now = now, maxAgeMillis = day)

        assertEquals(6, removed)
        listOf(oldBackup, oldImport, oldRestoreBlob, oldDownload, oldExport, oldPortableImport).forEach {
            assertFalse("${it.name} should be gone", it.exists())
        }
        assertFalse("an old restore directory goes with its blobs", oldRestoreBlob.parentFile!!.exists())
        listOf(freshBackup, freshRestoreBlob, freshExport, unrelated, lock).forEach {
            assertTrue("${it.name} should stay", it.exists())
        }
        assertTrue("the backup staging root itself stays", File(temporaryFolder.root, "backup").isDirectory)
    }

    @Test
    fun aRestoreDirectoryIsJudgedByItsNewestFile() = runBlocking {
        file("backup/restore/restore-mixed/old", 3 * day)
        val fresh = file("backup/restore/restore-mixed/new", 1000)

        val removed = StagingSweeper(temporaryFolder.root).sweep(now = now, maxAgeMillis = day)

        assertEquals(0, removed)
        assertTrue(fresh.exists())
    }

    @Test
    fun aMissingCacheDirectoryIsNotAnError() = runBlocking {
        val removed = StagingSweeper(File(temporaryFolder.root, "absent")).sweep(now = now, maxAgeMillis = day)
        assertEquals(0, removed)
    }
}
