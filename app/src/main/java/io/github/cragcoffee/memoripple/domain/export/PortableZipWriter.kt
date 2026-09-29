package io.github.cragcoffee.memoripple.domain.export

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The archive half of the portable export: entries go in as streams, never as whole files in
 * memory, and every path is checked against [PortableExportNaming.isSafeEntryPath] before it
 * is written — a traversal or absolute path is a bug and is refused, not repaired. Ordinary
 * DEFLATE only; the container compresses, the photo bytes inside are the original bytes.
 */
class PortableZipWriter(target: OutputStream) : AutoCloseable {

    private val zip = ZipOutputStream(target.buffered())
    private val written = mutableSetOf<String>()

    /** Entries written so far — the progress a UI can count. */
    var entryCount: Int = 0
        private set

    fun addText(path: String, text: String) {
        open(path)
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    /** Streams [source] into the entry with a small buffer; the caller owns closing [source]. */
    fun addStream(path: String, source: InputStream) {
        open(path)
        source.copyTo(zip, DEFAULT_COPY_BUFFER)
        zip.closeEntry()
    }

    private fun open(path: String) {
        require(PortableExportNaming.isSafeEntryPath(path)) { "unsafe zip entry path" }
        require(written.add(path)) { "duplicate zip entry path" }
        zip.putNextEntry(ZipEntry(path))
        entryCount += 1
    }

    /** Finishes the central directory; without this the archive does not open. */
    override fun close() {
        zip.close()
    }

    private companion object {
        const val DEFAULT_COPY_BUFFER = 64 * 1024
    }
}
