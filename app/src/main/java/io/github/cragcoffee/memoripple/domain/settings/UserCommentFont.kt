package io.github.cragcoffee.memoripple.domain.settings

import kotlinx.serialization.Serializable

/**
 * A typeface the user brought themselves: one font file, copied into the app's own storage,
 * offered in the コメントのフォント list beside the bundled faces. Device-local through and
 * through — the file lives on this phone and the list never enters a backup, the same stance
 * every "way of looking" preference takes.
 */
@Serializable
data class UserCommentFont(
    val id: String,
    /** What the list shows — the file's own name, without its extension. */
    val name: String,
    /** The copy's file name inside the store's directory. */
    val fileName: String,
) {
    companion object {
        /** Fonts one device may hold; far above what a person curates by hand. */
        const val MAXIMUM = 20
    }
}

/**
 * How a chosen font is written into the one `comment_font` preference: bundled faces keep
 * their enum ids (`default`, `gothic`…), a user font is `user:<id>`. Old values stay valid,
 * so nothing migrates.
 */
object CommentFontSelection {

    private const val USER_PREFIX = "user:"

    fun userStorageId(font: UserCommentFont): String = USER_PREFIX + font.id

    fun userIdOrNull(storageId: String?): String? =
        storageId?.takeIf { it.startsWith(USER_PREFIX) }?.removePrefix(USER_PREFIX)

    /** The name the settings row shows; a vanished user font reads as the default again. */
    fun displayName(
        storageId: String,
        userFonts: List<UserCommentFont>,
        builtInName: (CommentFont) -> String,
    ): String {
        val userId = userIdOrNull(storageId)
        return if (userId != null) {
            userFonts.firstOrNull { it.id == userId }?.name
                ?: builtInName(CommentFont.DEFAULT)
        } else {
            builtInName(CommentFont.fromStorageId(storageId))
        }
    }
}
