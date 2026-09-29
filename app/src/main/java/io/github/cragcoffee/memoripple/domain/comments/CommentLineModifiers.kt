package io.github.cragcoffee.memoripple.domain.comments

/**
 * 行末修飾子 — the video-site idea of writing how a comment should fly right on the line.
 * Tokens stand at the end of a line, space-separated from the words and from each other:
 *
 * ```
 * ! 大事な話 ↑ ++ {赤}
 * ```
 *
 * Everything here is pure text arithmetic in the same layer as the outline symbols and the
 * [R1] markers: what the tokens mean, what the line reads like with them gone, and nothing
 * about rendering. A token anywhere but the trailing run is ordinary prose; `[R n]` markers
 * may stand among the trailing tokens in any order and are left in place.
 */
object CommentLineModifiers {

    /** How far a size or speed token pushes from the line's own default. */
    enum class SizeStep { SMALL, LARGE, X_LARGE }
    enum class SpeedStep { FAST, FASTEST }

    /** The flying instructions one line carries. Null/false means "the line's own default". */
    data class LineModifiers(
        val direction: CommentFlowDirection? = null,
        val mode: CommentMotionMode? = null,
        val speed: SpeedStep? = null,
        val size: SizeStep? = null,
        val colorRole: CommentColorRole? = null,
        val wave: Boolean = false,
        val blink: Boolean = false,
        val delayed: Boolean = false,
        val repeat: Int = 1,
        /** 完全固定: a fixed line that never expires — it stays until playback is stopped. */
        val persistent: Boolean = false,
        /** ループ: a flowing line that sets off again the moment it has crossed, endlessly. */
        val loop: Boolean = false,
    ) {
        val isEmpty: Boolean
            get() = this == None

        companion object {
            val None = LineModifiers()
        }
    }

    /** The line as the reader (and the flying comment) receives it, plus its instructions. */
    data class Stripped(val text: String, val modifiers: LineModifiers)

    /** 溜め: how long a `...` line waits before setting off. */
    const val DELAY_MILLIS = 800L

    /** ×N is clamped into this range; anything outside rounds to the top. */
    val REPEAT_RANGE = 2..5

    private val linkMarkerToken = Regex("""^\[R\d{1,4}]$""")
    private val repeatToken = Regex("""^[×xX]([0-9]{1,3})$""")
    private val colorToken = Regex("""^\{(.+)\}$""")
    private val hexColor = Regex("""^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$""")
    private const val VARIATION_SELECTOR = '\uFE0F'

    /**
     * Canonical RGB per palette role — the stage palette's own values — for snapping a hex
     * colour to its nearest role. DEFAULT stands for 白: the theme's own text colour, white
     * on the stage.
     */
    private val paletteRgb: Map<CommentColorRole, Int> = mapOf(
        CommentColorRole.DEFAULT to 0xFFFFFF,
        CommentColorRole.RED to 0xFF6B6B,
        CommentColorRole.PINK to 0xF48FB1,
        CommentColorRole.ORANGE to 0xFFB74D,
        CommentColorRole.YELLOW to 0xFFD54F,
        CommentColorRole.GREEN to 0x81C784,
        CommentColorRole.CYAN to 0x4DD0E1,
        CommentColorRole.BLUE to 0x64B5F6,
        CommentColorRole.PURPLE to 0xCE93D8,
        CommentColorRole.GRAY to 0x9E9E9E,
        CommentColorRole.BLACK to 0x111111,
    )

    private val colorNames: Map<String, CommentColorRole> = buildMap {
        fun both(japanese: String, english: String, role: CommentColorRole) {
            put(japanese, role)
            put(english, role)
        }
        both("赤", "red", CommentColorRole.RED)
        both("青", "blue", CommentColorRole.BLUE)
        both("緑", "green", CommentColorRole.GREEN)
        both("黄", "yellow", CommentColorRole.YELLOW)
        both("橙", "orange", CommentColorRole.ORANGE)
        both("紫", "purple", CommentColorRole.PURPLE)
        both("桃", "pink", CommentColorRole.PINK)
        both("白", "white", CommentColorRole.DEFAULT)
        both("黒", "black", CommentColorRole.BLACK)
        both("灰", "gray", CommentColorRole.GRAY)
        put("grey", CommentColorRole.GRAY)
    }

    /** One parsed token folded into the running instructions. */
    private fun LineModifiers.plus(token: String): LineModifiers? {
        val bare = token.filterNot { it == VARIATION_SELECTOR }
        return when (bare) {
            "←", "<-" -> copy(direction = CommentFlowDirection.RIGHT_TO_LEFT)
            "→", "->" -> copy(direction = CommentFlowDirection.LEFT_TO_RIGHT)
            "←←", "<<-" -> copy(
                direction = CommentFlowDirection.RIGHT_TO_LEFT,
                speed = SpeedStep.FAST,
            )
            "→→", "->>" -> copy(
                direction = CommentFlowDirection.LEFT_TO_RIGHT,
                speed = SpeedStep.FAST,
            )
            "←←←", "<<<-" -> copy(
                direction = CommentFlowDirection.RIGHT_TO_LEFT,
                speed = SpeedStep.FASTEST,
            )
            "→→→", "->>>" -> copy(
                direction = CommentFlowDirection.LEFT_TO_RIGHT,
                speed = SpeedStep.FASTEST,
            )
            // A doubled arrow means more of the same, as it does for speed and size: the
            // line is pinned and stays pinned, with no time limit of its own.
            "↑", "^" -> copy(mode = CommentMotionMode.FIXED_TOP, persistent = false)
            "↓", "v" -> copy(mode = CommentMotionMode.FIXED_BOTTOM, persistent = false)
            "↑↑", "^^" -> copy(mode = CommentMotionMode.FIXED_TOP, persistent = true)
            "↓↓", "vv" -> copy(mode = CommentMotionMode.FIXED_BOTTOM, persistent = true)
            // ループ and ×N are the same question — how many times — so each replaces the other.
            "↺", "↻", "oo" -> copy(loop = true, repeat = 1)
            "+" -> copy(size = SizeStep.LARGE)
            "++" -> copy(size = SizeStep.X_LARGE)
            "-" -> copy(size = SizeStep.SMALL)
            "~", "～" -> copy(wave = true)
            "*" -> copy(blink = true)
            "..." -> copy(delayed = true)
            else -> {
                repeatToken.matchEntire(bare)?.let { match ->
                    val n = match.groupValues[1].toIntOrNull() ?: return@let null
                    return copy(
                        repeat = if (n in REPEAT_RANGE) n else REPEAT_RANGE.last,
                        loop = false,
                    )
                }
                colorToken.matchEntire(bare)?.let { match ->
                    val name = match.groupValues[1].trim().lowercase()
                    colorNames[name]?.let { return copy(colorRole = it) }
                    hexColor.matchEntire(name)?.let { hex ->
                        return copy(colorRole = nearestRole(expandHex(hex.groupValues[1])))
                    }
                    // 未知の色名: the token is still a modifier token — it is consumed and
                    // simply changes nothing, so a typo never flies as prose.
                    return this
                }
                null
            }
        }
    }

    /**
     * Cuts the trailing modifier tokens off [line] and reads them. `[R n]` markers among the
     * trailing tokens stay exactly where they are; the first ordinary word ends the trailing
     * run, so a `-` or `*` inside the sentence is never touched.
     */
    fun strip(line: String): Stripped {
        if (line.isEmpty() || !line.any { it == ' ' }) return Stripped(line, LineModifiers.None)
        val consumed = mutableListOf<String>()
        val removals = mutableListOf<IntRange>()
        var end = line.length
        while (true) {
            while (end > 0 && line[end - 1] == ' ') end -= 1
            if (end == 0) break
            val start = line.lastIndexOf(' ', end - 1) + 1
            if (start == 0) break // the first token of the line is words, not a modifier
            val token = line.substring(start, end)
            if (linkMarkerToken.matches(token)) {
                end = start
                continue
            }
            if (LineModifiers.None.plus(token) == null) break
            consumed += token
            removals += (start - 1) until end // the token and the space that carried it
            end = start
        }
        if (removals.isEmpty()) return Stripped(line, LineModifiers.None)
        // The scan walked backwards; folding in line order lets a later token win a tie
        // (`← →` flows right), the way anyone reads the line.
        val modifiers = consumed.reversed().fold(LineModifiers.None) { acc, token ->
            acc.plus(token) ?: acc
        }
        val builder = StringBuilder(line)
        removals.forEach { range -> builder.delete(range.first, range.last + 1) }
        return Stripped(builder.toString().trimEnd(), modifiers)
    }

    /** The tokens the toolbar's 流れ方 chips write, grouped so one replaces its kin. */
    private val directionGroup = setOf(
        "←", "→", "↑", "↓", "←←", "→→", "←←←", "→→→", "↑↑", "↓↓",
        "<-", "->", "^", "v", "<<-", "->>", "<<<-", "->>>", "^^", "vv",
    )
    private val sizeGroup = setOf("+", "++", "-")

    /** ループ and ×N answer the same question, so a chip writing one clears the other. */
    private val repeatGroup = setOf("↺", "↻", "oo")

    /**
     * Writes [token] at the end of [line] for the toolbar chips: an existing token of the
     * same kind (方向系・大きさ系) is replaced rather than stacked, other modifiers and
     * `[R n]` markers stay, and a blank line just receives the token.
     */
    fun applyChipToken(line: String, token: String): String {
        val group = when (token) {
            in directionGroup -> directionGroup
            in sizeGroup -> sizeGroup
            in repeatGroup -> repeatGroup
            else -> setOf(token)
        }
        val removals = mutableListOf<IntRange>()
        var end = line.length
        while (true) {
            while (end > 0 && line[end - 1] == ' ') end -= 1
            if (end == 0) break
            val start = line.lastIndexOf(' ', end - 1) + 1
            if (start == 0) break
            val candidate = line.substring(start, end)
            if (linkMarkerToken.matches(candidate)) {
                end = start
                continue
            }
            if (LineModifiers.None.plus(candidate) == null) break
            if (candidate.filterNot { it == VARIATION_SELECTOR } in group) {
                removals += (start - 1) until end
            }
            end = start
        }
        val builder = StringBuilder(line)
        removals.forEach { range -> builder.delete(range.first, range.last + 1) }
        val kept = builder.toString().trimEnd()
        return if (kept.isEmpty()) token else "$kept $token"
    }

    /** [text] with every line's trailing modifiers removed — the reading surfaces' view. */
    fun stripText(text: String): String =
        if ('\n' in text) text.lineSequence().joinToString("\n") { strip(it).text }
        else strip(text).text

    private fun expandHex(digits: String): Int {
        val full = if (digits.length == 3) {
            digits.map { "$it$it" }.joinToString("")
        } else {
            digits
        }
        return full.toInt(16)
    }

    /** The palette role closest to [rgb], by simple channel distance. */
    private fun nearestRole(rgb: Int): CommentColorRole {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        return paletteRgb.minBy { (_, candidate) ->
            val cr = (candidate shr 16) and 0xFF
            val cg = (candidate shr 8) and 0xFF
            val cb = candidate and 0xFF
            (r - cr) * (r - cr) + (g - cg) * (g - cg) + (b - cb) * (b - cb)
        }.key
    }
}
