package io.github.cragcoffee.memoripple.ui.notes

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.cragcoffee.memoripple.data.PhotoAttachment
import io.github.cragcoffee.memoripple.domain.notes.NoteCoverColor
import io.github.cragcoffee.memoripple.domain.notes.NoteCoverPaint
import io.github.cragcoffee.memoripple.ui.attachments.AttachmentImageLoader

/**
 * A note's cover.
 *
 * A colour, and nothing written on it. The title stands beside every cover this app draws, so
 * printing the first six characters on the block as well said the same thing twice and said it
 * worse — six characters is rarely enough to tell two notes apart. The colour is what the eye
 * actually finds a note by, and it is the writer's to choose.
 *
 * Only the width is given. The height follows from [COVER_ASPECT], so a cover cannot be made to
 * drift out of 16:9 by someone picking a pair of numbers that nearly match.
 */
@Composable
fun NoteCover(
    paint: NoteCoverPaint,
    modifier: Modifier = Modifier,
    width: Dp = 112.dp,
    photo: PhotoAttachment? = null,
    imageLoader: AttachmentImageLoader? = null,
) {
    val dark = MaterialTheme.colorScheme.surface.let {
        it.red * 0.299f + it.green * 0.587f + it.blue * 0.114f < 0.5f
    }
    val shape = RoundedCornerShape(6.dp)
    val density = LocalDensity.current
    val targetPx = remember(width, density) { with(density) { width.roundToPx() } }
    // The colour is drawn whether or not there is a picture, so a cover whose file has gone is
    // still a cover rather than a hole.
    Box(
        modifier = modifier
            .width(width)
            .aspectRatio(COVER_ASPECT)
            .background(paint.brush(dark), shape)
            .clip(shape),
    ) {
        if (photo != null && imageLoader != null) {
            var bitmap by remember(photo.blobSha256, targetPx) {
                mutableStateOf<ImageBitmap?>(null)
            }
            LaunchedEffect(photo.blobSha256, targetPx) {
                bitmap = imageLoader.load(photo, targetPx)?.asImageBitmap()
            }
            bitmap?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
    }
}

@Composable
fun NoteCover(
    color: NoteCoverColor,
    modifier: Modifier = Modifier,
    width: Dp = 112.dp,
    photo: PhotoAttachment? = null,
    imageLoader: AttachmentImageLoader? = null,
) = NoteCover(NoteCoverPaint.Preset(color), modifier, width, photo, imageLoader)

private fun NoteCoverPaint.brush(dark: Boolean): Brush = when (this) {
    is NoteCoverPaint.Preset -> color.brush(dark)
    // A colour of the writer's own is the same in both themes, the way the light presets are:
    // it was chosen for what it is, and the gradient is made from it rather than looked up.
    is NoteCoverPaint.Custom -> gradientEnds().let { (start, end) ->
        Brush.linearGradient(listOf(Color(start), Color(end)))
    }
}

private fun NoteCoverColor.brush(dark: Boolean): Brush = when (this) {
    NoteCoverColor.PLUM -> gradient(0xFF7B2B4A, 0xFF2A1220, 0xFFA84C6E, 0xFF5E2038, dark)
    NoteCoverColor.INDIGO -> gradient(0xFF2F4A6B, 0xFF131F2C, 0xFF4C74A3, 0xFF243B54, dark)
    NoteCoverColor.AMBER -> gradient(0xFF4A3A1E, 0xFF221A0F, 0xFF8A6C33, 0xFF4A3A1E, dark)
    NoteCoverColor.MOSS -> gradient(0xFF2F4A41, 0xFF16241F, 0xFF44705F, 0xFF24463A, dark)
    NoteCoverColor.SLATE -> gradient(0xFF3B4550, 0xFF191E23, 0xFF5C6B79, 0xFF313A43, dark)
    // Light covers pass the same pair for both themes, so they stay the colour they were chosen for.
    NoteCoverColor.SAKURA -> gradient(0xFFF3C3D2, 0xFFDE93AC, 0xFFF3C3D2, 0xFFDE93AC, dark)
    NoteCoverColor.SKY -> gradient(0xFFC6DDF2, 0xFF8FBBDF, 0xFFC6DDF2, 0xFF8FBBDF, dark)
    NoteCoverColor.SAND -> gradient(0xFFF0DCAB, 0xFFDDBE72, 0xFFF0DCAB, 0xFFDDBE72, dark)
    NoteCoverColor.MINT -> gradient(0xFFC5E5D4, 0xFF8CC8AC, 0xFFC5E5D4, 0xFF8CC8AC, dark)
}

private fun gradient(
    darkStart: Long,
    darkEnd: Long,
    lightStart: Long,
    lightEnd: Long,
    dark: Boolean,
): Brush = Brush.linearGradient(
    listOf(
        Color(if (dark) darkStart else lightStart),
        Color(if (dark) darkEnd else lightEnd),
    ),
)

/** 16:9, the shape a thumbnail is expected to be. */
private const val COVER_ASPECT = 16f / 9f
