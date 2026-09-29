package io.github.cragcoffee.memoripple.ui.playback

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import io.github.cragcoffee.memoripple.R
import io.github.cragcoffee.memoripple.domain.settings.CommentFont

/**
 * Every named face is bundled so every device draws the same letters: Android ships no rounded
 * Japanese face at all, the system serif falls back to gothic on devices without Noto Serif
 * CJK, and the system sans itself varies (OEM faces, owner-chosen fonts). Kosugi Maru is
 * Apache-2.0; Noto Sans JP and Noto Serif JP are SIL OFL 1.1 (variable fonts — the single 400
 * instance is declared, emphasis is synthesised like the other faces). Only DEFAULT stays
 * `null`: it deliberately means "whatever letters this device already speaks in".
 */
private val gothicFamily = FontFamily(Font(R.font.noto_sans_jp))
private val minchoFamily = FontFamily(Font(R.font.noto_serif_jp))
private val roundedFamily = FontFamily(Font(R.font.kosugi_maru))

/** The [FontFamily] a chosen [CommentFont] draws with; `null` keeps the default sans. */
fun CommentFont.commentFontFamily(): FontFamily? = when (this) {
    CommentFont.DEFAULT -> null
    CommentFont.GOTHIC -> gothicFamily
    CommentFont.MINCHO -> minchoFamily
    CommentFont.ROUNDED -> roundedFamily
}

/**
 * Resolves the raw selection — bundled id or `user:<id>` — to what the comments draw with.
 * A user font whose file has gone quietly resolves to the default rather than crashing a
 * playback that was already rolling.
 */
suspend fun resolveCommentFontFamily(
    storageId: String,
    userFonts: List<io.github.cragcoffee.memoripple.domain.settings.UserCommentFont>,
    store: io.github.cragcoffee.memoripple.data.CommentFontStore,
): FontFamily? {
    val userId = io.github.cragcoffee.memoripple.domain.settings.CommentFontSelection
        .userIdOrNull(storageId)
        ?: return CommentFont.fromStorageId(storageId).commentFontFamily()
    val font = userFonts.firstOrNull { it.id == userId } ?: return null
    val typeface = store.loadTypeface(font) ?: return null
    return FontFamily(typeface)
}

/** The composition-side resolver: bundled faces at once, a user file as soon as it loads. */
@androidx.compose.runtime.Composable
fun rememberCommentFontFamily(
    storageId: String,
    userFonts: List<io.github.cragcoffee.memoripple.domain.settings.UserCommentFont>,
    store: io.github.cragcoffee.memoripple.data.CommentFontStore,
): FontFamily? {
    val userId = io.github.cragcoffee.memoripple.domain.settings.CommentFontSelection
        .userIdOrNull(storageId)
    if (userId == null) {
        return CommentFont.fromStorageId(storageId).commentFontFamily()
    }
    val family = androidx.compose.runtime.produceState<FontFamily?>(
        initialValue = null,
        storageId,
        userFonts,
    ) {
        value = resolveCommentFontFamily(storageId, userFonts, store)
    }
    return family.value
}
