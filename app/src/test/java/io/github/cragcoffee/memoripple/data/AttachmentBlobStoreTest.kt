package io.github.cragcoffee.memoripple.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AttachmentBlobStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun installIsContentAddressedAndReusesValidBlob() = runBlocking {
        val store = AttachmentBlobStore(temporaryFolder.newFolder("files"))
        val first = temporaryFolder.newFile("one").apply { writeText("same bytes") }
        val second = temporaryFolder.newFile("two").apply { writeText("same bytes") }
        val sha = AttachmentBlobStore.hash(first)

        val installed = store.installValidated(first, sha, second.length())
        val reused = store.installValidated(second, sha, installed.length())

        assertEquals(installed, reused)
        assertTrue(store.isValid(sha, installed.length()))
        assertFalse(second.exists())
    }
}
