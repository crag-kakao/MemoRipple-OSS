package io.github.cragcoffee.memoripple.domain.comments

/**
 * The comment palette, in the order and the set the video site everyone knows uses:
 * 白（標準）・赤・ピンク・オレンジ・黄・緑・シアン・青・紫・黒. DEFAULT is the theme's own text
 * colour, which is what 白 means on a stage that can be either light or dark.
 */
enum class CommentColorRole(val storageId: String) {
    DEFAULT("default"),
    RED("red"),
    PINK("pink"),
    ORANGE("orange"),
    YELLOW("yellow"),
    GREEN("green"),
    CYAN("cyan"),
    BLUE("blue"),
    PURPLE("purple"),
    GRAY("gray"),
    BLACK("black");

    companion object {
        fun fromStorageId(value: String): CommentColorRole =
            entries.firstOrNull { it.storageId == value } ?: DEFAULT

        fun isKnownStorageId(value: String): Boolean = entries.any { it.storageId == value }
    }
}

enum class CommentSizeRole(val storageId: String, val scaleMultiplier: Float) {
    SMALL("small", 0.70f),
    STANDARD("standard", 1.00f),
    LARGE("large", 1.45f);

    companion object {
        fun fromStorageId(value: String): CommentSizeRole =
            entries.firstOrNull { it.storageId == value } ?: STANDARD

        fun isKnownStorageId(value: String): Boolean = entries.any { it.storageId == value }
    }
}

enum class CommentEmphasisRole(val storageId: String) {
    NORMAL("normal"),
    STRONG("strong");

    companion object {
        fun fromStorageId(value: String): CommentEmphasisRole =
            entries.firstOrNull { it.storageId == value } ?: NORMAL

        fun isKnownStorageId(value: String): Boolean = entries.any { it.storageId == value }
    }
}

data class CommentAppearance(
    val colorRole: CommentColorRole = CommentColorRole.DEFAULT,
    val sizeRole: CommentSizeRole = CommentSizeRole.STANDARD,
    val emphasisRole: CommentEmphasisRole = CommentEmphasisRole.NORMAL,
) {
    val isDefault: Boolean
        get() = colorRole == CommentColorRole.DEFAULT &&
            sizeRole == CommentSizeRole.STANDARD &&
            emphasisRole == CommentEmphasisRole.NORMAL

    companion object {
        val Default = CommentAppearance()
    }
}
