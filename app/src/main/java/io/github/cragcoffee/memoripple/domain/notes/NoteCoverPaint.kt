package io.github.cragcoffee.memoripple.domain.notes

/**
 * What a note's cover is painted: one of the preset colours, or a colour of the writer's own.
 *
 * Both are kept in the same `coverColor` column. A preset is its name; a writer's colour is
 * `custom:#RRGGBB`. A build older than this form reads the latter as a name it does not know and
 * draws the default cover — a wrong colour, never a crash — so the schema and the backup format
 * stay where they are.
 */
sealed interface NoteCoverPaint {
    val storageId: String

    data class Preset(val color: NoteCoverColor) : NoteCoverPaint {
        override val storageId: String get() = color.storageId
    }

    /** A colour of the writer's own. Always opaque: the alpha byte is not theirs to set. */
    data class Custom(private val value: Int) : NoteCoverPaint {
        val argb: Int = value or ALPHA
        override val storageId: String get() = "$CUSTOM_PREFIX#%06X".format(argb and RGB)

        /**
         * The two ends of the cover's gradient: the colour itself, then the same hue a step
         * darker. A step rather than a plunge, so a colour chosen for being pale stays pale.
         */
        fun gradientEnds(): Pair<Int, Int> {
            val (h, s, l) = toHsl(argb)
            return argb to fromHsl(h, s, (l * SHADE_STEP).coerceAtLeast(0.04f))
        }

        override fun equals(other: Any?): Boolean = other is Custom && other.argb == argb
        override fun hashCode(): Int = argb
        override fun toString(): String = "Custom($storageId)"
    }

    companion object {
        private const val CUSTOM_PREFIX = "custom:"
        private const val ALPHA = 0xFF000000.toInt()
        private const val RGB = 0xFFFFFF
        private const val SHADE_STEP = 0.72f
        private val CUSTOM_FORM = Regex("custom:#([0-9A-Fa-f]{6})")

        val Default: NoteCoverPaint = Preset(NoteCoverColor.Default)

        fun fromStorageId(value: String?): NoteCoverPaint {
            val custom = value?.let(CUSTOM_FORM::matchEntire)?.groupValues?.get(1)
            if (custom != null) return Custom(custom.toInt(16))
            return Preset(NoteCoverColor.fromStorageId(value))
        }

        /** Hue in degrees 0..360, saturation and lightness 0..1, to an opaque ARGB. */
        fun fromHsl(hue: Float, saturation: Float, lightness: Float): Int {
            val h = ((hue % 360f) + 360f) % 360f
            val s = saturation.coerceIn(0f, 1f)
            val l = lightness.coerceIn(0f, 1f)
            val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
            val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
            val m = l - c / 2f
            val (r, g, b) = when {
                h < 60f -> Triple(c, x, 0f)
                h < 120f -> Triple(x, c, 0f)
                h < 180f -> Triple(0f, c, x)
                h < 240f -> Triple(0f, x, c)
                h < 300f -> Triple(x, 0f, c)
                else -> Triple(c, 0f, x)
            }
            fun channel(v: Float): Int = Math.round((v + m) * 255f).coerceIn(0, 255)
            return ALPHA or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
        }

        /** The inverse of [fromHsl]: hue in degrees, saturation and lightness 0..1. */
        fun toHsl(argb: Int): FloatArray {
            val r = ((argb shr 16) and 0xFF) / 255f
            val g = ((argb shr 8) and 0xFF) / 255f
            val b = (argb and 0xFF) / 255f
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val delta = max - min
            val l = (max + min) / 2f
            if (delta == 0f) return floatArrayOf(0f, 0f, l)
            val s = delta / (1f - kotlin.math.abs(2f * l - 1f))
            val hue = when (max) {
                r -> 60f * (((g - b) / delta) % 6f)
                g -> 60f * ((b - r) / delta + 2f)
                else -> 60f * ((r - g) / delta + 4f)
            }
            return floatArrayOf(((hue % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), l)
        }
    }
}

/** マイカラー: the colours a writer chose to keep. The newest first, no repeats, at most six. */
object NoteCoverPalette {
    const val MAXIMUM = 6

    fun remembered(existing: List<Int>, argb: Int): List<Int> =
        (listOf(argb) + existing.filter { it != argb }).take(MAXIMUM)

    fun forgotten(existing: List<Int>, argb: Int): List<Int> = existing.filter { it != argb }
}
