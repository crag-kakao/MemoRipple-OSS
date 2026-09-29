package io.github.cragcoffee.memoripple

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.cragcoffee.memoripple.data.AttachmentBlobStore
import io.github.cragcoffee.memoripple.data.PhotoAttachment
import io.github.cragcoffee.memoripple.ui.attachments.AttachmentImageLoader
import io.github.cragcoffee.memoripple.ui.attachments.PhotoAttachmentStrip
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoViewerInstrumentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var root: File
    private lateinit var store: AttachmentBlobStore
    private lateinit var imageLoader: AttachmentImageLoader

    @Before
    fun setUp() {
        root = File(context.cacheDir, "photo-viewer-${System.nanoTime()}").apply { mkdirs() }
        store = AttachmentBlobStore(root)
        imageLoader = AttachmentImageLoader(store)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun persistedOrderSwipesZoomsWithoutPagingThenResetsAndDoubleTaps() {
        val original = listOf(
            createPhoto(1, Color.RED, width = 80, height = 120),
            createPhoto(2, Color.GREEN, width = 140, height = 70),
            createPhoto(3, Color.BLUE, width = 100, height = 100),
        )
        val reordered = listOf(original[1], original[2], original[0])
        showPhotos(reordered)

        composeRule.onNodeWithContentDescription("添付写真 1 / 3").performClick()
        composeRule.onNodeWithTag("photo_viewer_page_2").assertIsDisplayed()
        composeRule.onNodeWithTag("photo_viewer_pager").performTouchInput { swipeLeft() }
        composeRule.onNodeWithTag("photo_viewer_counter").assertTextEquals("2 / 3")
        composeRule.onNodeWithTag("photo_viewer_page_3").assertIsDisplayed()

        composeRule.onNodeWithTag("photo_viewer_image_3").performTouchInput {
            pinch(
                start0 = Offset(center.x - 30f, center.y),
                end0 = Offset(center.x - 180f, center.y),
                start1 = Offset(center.x + 30f, center.y),
                end1 = Offset(center.x + 180f, center.y),
            )
        }
        assertEquals(
            "4倍",
            composeRule.onNodeWithTag("photo_viewer_image_3")
                .fetchSemanticsNode().config[SemanticsProperties.StateDescription],
        )
        composeRule.onNodeWithTag("photo_viewer_image_3").performTouchInput { swipeLeft() }
        composeRule.onNodeWithTag("photo_viewer_counter").assertTextEquals("2 / 3")

        performZoomAction(photoId = 3, label = "等倍に戻す")
        performZoomAction(photoId = 3, label = "拡大")
        assertEquals(
            "1.5倍",
            composeRule.onNodeWithTag("photo_viewer_image_3")
                .fetchSemanticsNode().config[SemanticsProperties.StateDescription],
        )
        performZoomAction(photoId = 3, label = "等倍に戻す")
        composeRule.onNodeWithTag("photo_viewer_pager").performTouchInput { swipeLeft() }
        composeRule.onNodeWithTag("photo_viewer_counter").assertTextEquals("3 / 3")
        composeRule.onNodeWithTag("photo_viewer_page_1").assertIsDisplayed()

        composeRule.onNodeWithTag("photo_viewer_image_1").performTouchInput {
            doubleClick(center)
        }
        assertEquals(
            "2倍",
            composeRule.onNodeWithTag("photo_viewer_image_1")
                .fetchSemanticsNode().config[SemanticsProperties.StateDescription],
        )
        composeRule.onNodeWithTag("photo_viewer_image_1").performTouchInput {
            doubleClick(center)
        }
        assertEquals(
            "1倍",
            composeRule.onNodeWithTag("photo_viewer_image_1")
                .fetchSemanticsNode().config[SemanticsProperties.StateDescription],
        )
    }

    @Test
    fun physicalLikeDoubleTapJitterZoomsThenResetsWithoutStartingPan() {
        showPhotos(listOf(createPhoto(1, Color.RED, width = 140, height = 70)))
        composeRule.onNodeWithContentDescription("添付写真 1 / 1").performClick()

        composeRule.onNodeWithTag("photo_viewer_image_1").performTouchInput {
            down(center)
            moveBy(Offset(2f, 1f))
            up()
            advanceEventTime(80)
            down(center + Offset(2f, 1f))
            moveBy(Offset(-2f, -1f))
            up()
        }
        assertEquals(
            "2倍",
            composeRule.onNodeWithTag("photo_viewer_image_1")
                .fetchSemanticsNode().config[SemanticsProperties.StateDescription],
        )

        composeRule.onNodeWithTag("photo_viewer_image_1").performTouchInput {
            advanceEventTime(400)
            down(center)
            moveBy(Offset(2f, 1f))
            up()
            advanceEventTime(80)
            down(center + Offset(2f, 1f))
            moveBy(Offset(-2f, -1f))
            up()
        }
        assertEquals(
            "1倍",
            composeRule.onNodeWithTag("photo_viewer_image_1")
                .fetchSemanticsNode().config[SemanticsProperties.StateDescription],
        )
    }

    @Test
    fun missingPageKeepsPagerAndAccessiblePreviousNextWorking() {
        val photos = listOf(
            createPhoto(1, Color.CYAN),
            PhotoAttachment(
                id = 2,
                ownerId = 9,
                blobSha256 = "a".repeat(64),
                sortOrder = 1,
                createdAt = 2,
                mimeType = "image/png",
                sizeBytes = 99,
                widthPx = 20,
                heightPx = 20,
            ),
            createPhoto(3, Color.MAGENTA),
        )
        showPhotos(photos)

        composeRule.onNodeWithContentDescription("添付写真 1 / 3").performClick()
        composeRule.onNodeWithContentDescription("次の写真").performClick()
        composeRule.onNodeWithContentDescription(
            "添付写真 2 / 3、写真を表示できません",
        ).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("次の写真").performClick()
        composeRule.onNodeWithTag("photo_viewer_page_3").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("前の写真").performClick()
        composeRule.onNodeWithTag("photo_viewer_counter").assertTextEquals("2 / 3")
    }

    @Test
    fun deletingLastAndThenOnlyPhotoClampsPageAndClosesViewer() {
        val initial = listOf(
            createPhoto(1, Color.RED),
            createPhoto(2, Color.GREEN),
            createPhoto(3, Color.BLUE),
        )
        composeRule.setContent {
            MaterialTheme {
                var photos by remember { mutableStateOf(initial) }
                PhotoAttachmentStrip(
                    photos = photos,
                    imageLoader = imageLoader,
                    editable = true,
                    importing = false,
                    onDelete = { deleted -> photos = photos.filterNot { it.id == deleted.id } },
                )
            }
        }

        composeRule.onNodeWithContentDescription("添付写真 3 / 3").performClick()
        deleteCurrentPhoto()
        composeRule.onNodeWithTag("photo_viewer_counter").assertTextEquals("2 / 2")
        deleteCurrentPhoto()
        composeRule.onNodeWithTag("photo_viewer_counter").assertTextEquals("1 / 1")
        assertTrue(composeRule.onAllNodesWithContentDescription("前の写真").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithContentDescription("次の写真").fetchSemanticsNodes().isEmpty())
        deleteCurrentPhoto()
        assertTrue(composeRule.onAllNodesWithContentDescription("閉じる").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun twentyPhotosTraverseFromFirstToLastAndZoomWithoutLosingCurrentPage() {
        val photos = (1L..20L).map { id ->
            createPhoto(
                id = id,
                color = Color.rgb((id * 11).toInt(), (id * 7).toInt(), (id * 3).toInt()),
                width = if (id % 2L == 0L) 120 else 60,
                height = if (id % 2L == 0L) 60 else 120,
            )
        }
        showPhotos(photos)

        composeRule.onNodeWithContentDescription("添付写真 1 / 20").performClick()
        repeat(19) { composeRule.onNodeWithContentDescription("次の写真").performClick() }
        composeRule.onNodeWithTag("photo_viewer_counter").assertTextEquals("20 / 20")
        composeRule.onNodeWithTag("photo_viewer_page_20").assertIsDisplayed()
        performZoomAction(photoId = 20, label = "拡大")
        composeRule.onNodeWithTag("photo_viewer_image_20").performTouchInput { swipeLeft() }
        composeRule.onNodeWithTag("photo_viewer_counter").assertTextEquals("20 / 20")
        performZoomAction(photoId = 20, label = "等倍に戻す")
        repeat(19) { composeRule.onNodeWithContentDescription("前の写真").performClick() }
        composeRule.onNodeWithTag("photo_viewer_counter").assertTextEquals("1 / 20")
    }

    private fun showPhotos(photos: List<PhotoAttachment>) {
        composeRule.setContent {
            MaterialTheme {
                PhotoAttachmentStrip(
                    photos = photos,
                    imageLoader = imageLoader,
                    editable = false,
                    importing = false,
                    onDelete = {},
                )
            }
        }
    }

    private fun performZoomAction(photoId: Long, label: String) {
        val action = composeRule.onNodeWithTag("photo_viewer_image_$photoId")
            .fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .single { it.label == label }
        composeRule.runOnIdle { assertTrue(action.action()) }
        composeRule.waitForIdle()
    }

    private fun deleteCurrentPhoto() {
        composeRule.onNodeWithContentDescription("写真を削除").performClick()
        composeRule.onNodeWithText("削除").performClick()
        composeRule.waitForIdle()
    }

    private fun createPhoto(
        id: Long,
        color: Int,
        width: Int = 40,
        height: Int = 40,
    ): PhotoAttachment = runBlocking {
        val source = store.newTempFile("viewer-$id")
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(color)
            source.outputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            bitmap.recycle()
        }
        val size = source.length()
        val sha = AttachmentBlobStore.hash(source)
        store.installValidated(source, sha, size)
        PhotoAttachment(
            id = id,
            ownerId = 9,
            blobSha256 = sha,
            sortOrder = id.toInt(),
            createdAt = id,
            mimeType = "image/png",
            sizeBytes = size,
            widthPx = width,
            heightPx = height,
        )
    }
}
