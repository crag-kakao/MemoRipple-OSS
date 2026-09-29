package io.github.cragcoffee.memoripple.domain.settings

import io.github.cragcoffee.memoripple.domain.WorkCommentScope

enum class ThemeMode(val storageId: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        fun fromStorageId(value: String?): ThemeMode =
            entries.firstOrNull { it.storageId == value } ?: SYSTEM
    }
}

enum class PlaybackSpeed(val storageId: String, val velocityMultiplier: Float) {
    SLOW("slow", 0.80f),
    STANDARD("standard", 1.00f),
    FAST("fast", 1.25f),
    ;

    companion object {
        fun fromStorageId(value: String?): PlaybackSpeed =
            entries.firstOrNull { it.storageId == value } ?: STANDARD

        /** The named speed a slider position rounds to, for the backup's fixed vocabulary. */
        fun nearest(scale: Float): PlaybackSpeed =
            entries.minByOrNull { kotlin.math.abs(it.velocityMultiplier - scale) } ?: STANDARD
    }
}

enum class CommentSize(val storageId: String, val scaleMultiplier: Float) {
    SMALL("small", 0.90f),
    STANDARD("standard", 1.00f),
    LARGE("large", 1.15f),
    ;

    companion object {
        fun fromStorageId(value: String?): CommentSize =
            entries.firstOrNull { it.storageId == value } ?: STANDARD

        /** The named size a slider position rounds to, for the backup's fixed vocabulary. */
        fun nearest(scale: Float): CommentSize =
            entries.minByOrNull { kotlin.math.abs(it.scaleMultiplier - scale) } ?: STANDARD
    }
}

enum class StageBackground(val storageId: String) {
    BLACK("black"),
    DARK_GRAY("dark_gray"),
    LIGHT("light"),
    THEME("theme"),
    ;

    companion object {
        fun fromStorageId(value: String?): StageBackground =
            entries.firstOrNull { it.storageId == value } ?: BLACK
    }
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val playbackSpeed: PlaybackSpeed = PlaybackSpeed.STANDARD,
    val commentSize: CommentSize = CommentSize.STANDARD,
    val stageBackground: StageBackground = StageBackground.BLACK,
    /** Whether prose flies past too, or only the lines the writer marked. */
    val commentScope: WorkCommentScope = WorkCommentScope.OUTLINE,
    /**
     * The slider's exact speed, in the same unit as [PlaybackSpeed.velocityMultiplier]. The
     * backup keeps carrying only the named speed (its wire format is frozen), so this precise
     * value lives device-locally and defaults to whatever the named speed says.
     */
    val playbackSpeedScale: Float = playbackSpeed.velocityMultiplier,
    /** The slider's exact size, in the same unit as [CommentSize.scaleMultiplier]; see above. */
    val commentSizeScale: Float = commentSize.scaleMultiplier,
    /**
     * 長いコメントを読みやすくする: off keeps the ニコニコ準拠 4-second crossing for every
     * flowing comment; on, a comment wider than its stage crosses a little slower — a
     * MemoRipple-only reading aid, never part of the frozen backup wire format.
     */
    val longCommentReadability: Boolean = false,
) {
    companion object {
        val Default = AppSettings()
    }
}
