package io.github.cragcoffee.memoripple.domain.export

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PortableZipWriterTest {

    private fun readAll(bytes: ByteArray): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes()
                entry = zip.nextEntry
            }
        }
        return entries
    }

    @Test
    fun entriesComeBackWithTheSameBytes() {
        val out = ByteArrayOutputStream()
        val photo = ByteArray(70_000) { (it % 251).toByte() }
        PortableZipWriter(out).use { writer ->
            writer.addText("MemoRipple-Export/README.md", "# 書き出し\n")
            writer.addStream(
                "MemoRipple-Export/memos/active/a/photos/photo-01.jpg",
                ByteArrayInputStream(photo),
            )
            assertEquals(2, writer.entryCount)
        }
        val entries = readAll(out.toByteArray())
        assertEquals(
            listOf(
                "MemoRipple-Export/README.md",
                "MemoRipple-Export/memos/active/a/photos/photo-01.jpg",
            ),
            entries.keys.toList(),
        )
        assertEquals("# 書き出し\n", entries.values.first().toString(Charsets.UTF_8))
        // Photo bytes ride through untouched — the container compresses, the image does not change.
        assertArrayEquals(photo, entries.values.last())
    }

    @Test
    fun unsafePathsAreRefusedNotRepaired() {
        PortableZipWriter(ByteArrayOutputStream()).use { writer ->
            assertThrows(IllegalArgumentException::class.java) {
                writer.addText("../escape.md", "x")
            }
            assertThrows(IllegalArgumentException::class.java) {
                writer.addText("/absolute.md", "x")
            }
        }
    }

    @Test
    fun duplicatePathsAreRefused() {
        PortableZipWriter(ByteArrayOutputStream()).use { writer ->
            writer.addText("a/same.md", "one")
            assertThrows(IllegalArgumentException::class.java) {
                writer.addText("a/same.md", "two")
            }
        }
    }
}
