package io.github.cragcoffee.memoripple.domain.comments

enum class CommentMotionMode(val storageId: String) {
    FLOW("flow"),
    FIXED_TOP("fixed_top"),
    FIXED_BOTTOM("fixed_bottom");

    companion object {
        fun fromStorageId(value: String): CommentMotionMode =
            entries.firstOrNull { it.storageId == value } ?: FLOW

        fun isKnownStorageId(value: String): Boolean = entries.any { it.storageId == value }
    }
}

enum class CommentSpeedRole(
    val storageId: String,
    val velocityMultiplier: Float,
) {
    SLOW("slow", 0.85f),
    STANDARD("standard", 1.00f),
    FAST("fast", 1.20f);

    companion object {
        fun fromStorageId(value: String): CommentSpeedRole =
            entries.firstOrNull { it.storageId == value } ?: STANDARD

        fun isKnownStorageId(value: String): Boolean = entries.any { it.storageId == value }
    }
}

enum class CommentPlacementRole(val storageId: String) {
    AUTO("auto"),
    TOP("top"),
    MIDDLE("middle"),
    BOTTOM("bottom");

    companion object {
        fun fromStorageId(value: String): CommentPlacementRole =
            entries.firstOrNull { it.storageId == value } ?: AUTO

        fun isKnownStorageId(value: String): Boolean = entries.any { it.storageId == value }
    }
}

enum class CommentFlowDirection(val storageId: String) {
    RIGHT_TO_LEFT("rtl"),
    LEFT_TO_RIGHT("ltr");

    companion object {
        fun fromStorageId(value: String): CommentFlowDirection =
            entries.firstOrNull { it.storageId == value } ?: RIGHT_TO_LEFT

        fun isKnownStorageId(value: String): Boolean = entries.any { it.storageId == value }
    }
}

enum class CommentFlowEffect(val storageId: String) {
    STRAIGHT("straight"),
    WAVE("wave");

    companion object {
        fun fromStorageId(value: String): CommentFlowEffect =
            entries.firstOrNull { it.storageId == value } ?: STRAIGHT

        fun isKnownStorageId(value: String): Boolean = entries.any { it.storageId == value }
    }
}

data class CommentMotion(
    val speedRole: CommentSpeedRole = CommentSpeedRole.STANDARD,
    val placementRole: CommentPlacementRole = CommentPlacementRole.AUTO,
    val mode: CommentMotionMode = CommentMotionMode.FLOW,
    val direction: CommentFlowDirection = CommentFlowDirection.RIGHT_TO_LEFT,
    val flowEffect: CommentFlowEffect = CommentFlowEffect.STRAIGHT,
) {
    val isDefault: Boolean
        get() = mode == CommentMotionMode.FLOW &&
            speedRole == CommentSpeedRole.STANDARD &&
            placementRole == CommentPlacementRole.AUTO &&
            direction == CommentFlowDirection.RIGHT_TO_LEFT &&
            flowEffect == CommentFlowEffect.STRAIGHT

    companion object {
        val Default = CommentMotion()
    }
}
