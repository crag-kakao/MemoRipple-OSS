package io.github.cragcoffee.memoripple.data

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Removes staging the app left in its cache directory when a process died mid-way.
 *
 * Backup (`cache/backup/backup-…`), restore (`cache/backup/import`, `cache/backup/restore`),
 * Drive (`cache/backup/drive`) and portable export / import (`cache/portable-…`) each delete
 * their own staging on every live path; only a death between two steps leaves something
 * behind, and nothing swept the cache afterwards. This does, at start, and only for those
 * names: a file (or a restore directory, judged by its newest file) older than [maxAgeMillis]
 * cannot belong to an operation that is still running. Anything else in the cache is left
 * alone.
 */
class StagingSweeper(private val cacheDirectory: File) {

    /** Deletes what qualifies and returns how many files went. */
    suspend fun sweep(now: Long, maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS): Int =
        withContext(Dispatchers.IO) {
            if (!cacheDirectory.isDirectory) return@withContext 0
            val cutoff = now - maxAgeMillis
            var removed = 0
            val backupRoot = File(cacheDirectory, "backup")
            backupRoot.listFiles()?.forEach { entry ->
                when {
                    entry.isFile && entry.lastModified() < cutoff -> if (entry.delete()) removed++
                    entry.isDirectory -> entry.listFiles()?.forEach { staged ->
                        if (newestModified(staged) < cutoff) removed += deleteTree(staged)
                    }
                }
            }
            cacheDirectory.listFiles()?.forEach { entry ->
                if (entry.isFile && entry.name.startsWith(PORTABLE_PREFIX) && entry.lastModified() < cutoff) {
                    if (entry.delete()) removed++
                }
            }
            removed
        }

    // A directory is as old as its newest file (an empty one is old): directory mtimes belong to
    // the filesystem and move when anything inside is listed or created.
    private fun newestModified(file: File): Long =
        if (file.isDirectory) file.listFiles()?.maxOfOrNull(::newestModified) ?: 0L else file.lastModified()

    private fun deleteTree(file: File): Int {
        var count = 0
        if (file.isDirectory) file.listFiles()?.forEach { count += deleteTree(it) }
        if (file.isFile && file.delete()) count++ else if (file.isDirectory) file.delete()
        return count
    }

    companion object {
        /** Longer than any backup, restore or export could plausibly take. */
        const val DEFAULT_MAX_AGE_MILLIS = 24L * 60 * 60 * 1000
        private const val PORTABLE_PREFIX = "portable-"
    }
}
