package io.github.cragcoffee.memoripple.ui.attachments

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.cragcoffee.memoripple.data.PhotoAttachment

/**
 * How big an inline photo is drawn, from its stored size alone — a pure rule, so the page never
 * jumps when the picture arrives.
 *
 * A photo is drawn at the content width with its own proportions, whole (`ContentScale.Fit`),
 * never cropped to a fixed height. Only the extremes are bounded: a picture taller than a
 * portrait photo (3:4) is given a 3:4 frame and shown whole inside it, and a panorama wider than
 * 3:1 a 3:1 frame, so a screenshot does not become a wall and a strip does not become a line.
 */
object InlinePhotoGeometry {
    /** Width ÷ height of the tallest frame drawn: a portrait photo, 3:4. */
    const val TALLEST = 3f / 4f

    /** Width ÷ height of the widest frame drawn: 3:1. */
    const val WIDEST = 3f

    /** The frame of a photo whose size is unknown until it is decoded. */
    const val UNKNOWN = 4f / 3f

    fun frameRatio(widthPx: Int, heightPx: Int): Float =
        if (widthPx <= 0 || heightPx <= 0) UNKNOWN
        else (widthPx.toFloat() / heightPx.toFloat()).coerceIn(TALLEST, WIDEST)
}

/**
 * One photo of a memo as a picture in the note (docs/MEMO_CONTENT_BLOCKS.md): the content width,
 * its own proportions, whole, the design system's small corner. On the reading page a tap opens
 * it; on the writing page it answers no touch and its ⋮ carries what can be done with it.
 */
@Composable
internal fun InlinePhoto(
    photo: PhotoAttachment,
    imageLoader: AttachmentImageLoader,
    targetPx: Int,
    description: String,
    /** Null: the picture answers no touch (the writing page, where its ⋮ does). */
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(photo.blobSha256) { mutableStateOf<Bitmap?>(null) }
    var loaded by remember(photo.blobSha256) { mutableStateOf(false) }
    LaunchedEffect(photo.blobSha256, targetPx) {
        bitmap = imageLoader.load(photo, targetPx)
        loaded = true
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(InlinePhotoGeometry.frameRatio(photo.widthPx, photo.heightPx))
            .clip(MaterialTheme.shapes.small)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics {
                contentDescription = description
                role = Role.Image
            }
            .testTag("memo_inline_photo_${photo.id}"),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                // Whole, never cropped: a frame bounded at the extremes shows the rest as margin.
                contentScale = ContentScale.Fit,
            )
            !loaded -> Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(Modifier.size(24.dp))
            }
            else -> MissingPhoto(Modifier.fillMaxSize())
        }
    }
}
