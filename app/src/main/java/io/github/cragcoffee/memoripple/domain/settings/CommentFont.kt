package io.github.cragcoffee.memoripple.domain.settings

/**
 * The typeface a flowing comment is drawn with.
 *
 * A way of looking, not part of the product data: the choice lives device-locally in
 * [io.github.cragcoffee.memoripple.data.SettingsRepository], never in [AppSettings], so a
 * backup/restore or 設定リセット leaves it alone.
 */
enum class CommentFont(val storageId: String) {
    /** The device's own letters — whatever the system (or its owner) has chosen. */
    DEFAULT("default"),
    GOTHIC("gothic"),
    MINCHO("mincho"),
    ROUNDED("rounded"),
    ;

    companion object {
        fun fromStorageId(value: String?): CommentFont =
            entries.firstOrNull { it.storageId == value } ?: DEFAULT
    }
}
