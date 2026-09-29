package io.github.cragcoffee.memoripple.domain.outline

/**
 * An outline as stored rows (Room 28, docs/OUTLINE_STABLE_ROWS.md): each line of the outline is a
 * row with an id that lasts — across saves, restarts, process death, reorders and indents — and
 * the memo's body is only the rows' projection (their lines joined by `\n`, exactly
 * [OutlineText.serialize]). Pure rules; nothing here reads or writes storage.
 */
object OutlineRows {
    /**
     * One stored line: its lasting id and its text exactly as written — or, with [photo], a photo
     * row (Room 29, docs/OUTLINE_PHOTO_ROWS.md) whose [line] is only its indent.
     */
    data class Row(val id: Int, val line: String, val photo: Long? = null) {
        val isPhoto: Boolean get() = photo != null
    }

    /** The body the rows stand for — byte for byte what [OutlineText.serialize] writes; photo rows are never in it. */
    fun projection(rows: List<Row>): String = rows.filterNot { it.isPhoto }.joinToString("\n") { it.line }

    /** The rows an outline document is: each entry keeps its id. */
    fun rowsOf(document: OutlineDocument): List<Row> = document.entries.map {
        Row(it.id, it.toLine(), (it as? OutlineNode)?.photoAttachmentId)
    }

    /**
     * An outline written before photo rows (Room ≤ 28, Backup ≤ 22): its photos, in their order, as
     * photo rows at the top at depth 0 — where the strip showed them, above the lines — with ids
     * past the highest; the lines and their ids stay as they are.
     */
    fun withPhotosOnTop(rows: List<Row>, attachmentIds: List<Long>): List<Row> {
        val placed = rows.mapNotNull { it.photo }.toSet()
        var next = (rows.maxOfOrNull { it.id } ?: 0) + 1
        val added = attachmentIds.filter { it !in placed }.map { Row(next++, "", it) }
        return added + rows
    }

    /**
     * The outline document the rows are, with their ids; the next new line takes one past the
     * highest id ever held here ([floor], when known), so an id is not handed out twice.
     */
    fun documentOf(rows: List<Row>, floor: Int = 1): OutlineDocument = OutlineDocument(
        entries = rows.map { row ->
            if (row.photo != null) {
                OutlineNode(row.id, row.line, marker = "", role = null, text = "", photoAttachmentId = row.photo)
            } else {
                OutlineText.parseLine(row.id, row.line)
            }
        },
        nextId = maxOf(floor, (rows.maxOfOrNull { it.id } ?: 0) + 1),
    )

    /**
     * The rows a body written before rows existed becomes: its lines in order, ids 1..n — the
     * same reading [OutlineText.parse] gives, so the projection is the body unchanged.
     */
    fun fresh(body: String): List<Row> = body.split('\n').mapIndexed { index, line -> Row(index + 1, line) }

    /**
     * A whole new body from a writer that knows nothing of rows (the AI's append, a task ticked on
     * the reading page, the split pane's plain text), laid onto [rows] line by line:
     * - a line that is unchanged keeps its id (found by the longest common run of lines);
     * - between two unchanged lines, the changed lines pair up in order and keep the old ids — a
     *   line whose words were edited is still that line;
     * - lines beyond the pairs are new (the next ids, in order) or gone.
     * Deterministic: the same rows and body always give the same ids.
     */
    fun reconcile(rows: List<Row>, newBody: String, nextId: Int): List<Row> {
        if (projection(rows) == newBody) return rows
        if (rows.none { it.isPhoto }) return reconcileLines(rows, newBody, nextId)
        // Photo rows are not in the body: the lines are laid on first, then each photo goes back
        // after the line it followed (or the nearest line before that one still there), and a
        // photo that followed no line stays at the top — in their order, with their ids.
        val lines = rows.filterNot { it.isPhoto }
        val anchorOf = LinkedHashMap<Row, Int?>()
        var lastLine: Int? = null
        val lineOrder = ArrayList<Int>()
        rows.forEach { row -> if (row.isPhoto) anchorOf[row] = lastLine else { lastLine = row.id; lineOrder += row.id } }
        val next = maxOf(nextId, (rows.maxOfOrNull { it.id } ?: 0) + 1)
        val laid = reconcileLines(lines, newBody, next)
        val surviving = laid.map { it.id }.toSet()
        fun resolve(anchor: Int?): Int? {
            var index = if (anchor == null) -1 else lineOrder.indexOf(anchor)
            while (index >= 0 && lineOrder[index] !in surviving) index--
            return if (index >= 0) lineOrder[index] else null
        }
        val byAnchor = anchorOf.entries.groupBy({ resolve(it.value) }, { it.key })
        return byAnchor[null].orEmpty() + laid.flatMap { line -> listOf(line) + byAnchor[line.id].orEmpty() }
    }

    private fun reconcileLines(rows: List<Row>, newBody: String, nextId: Int): List<Row> {
        val newLines = newBody.split('\n')
        if (projection(rows) == newBody) return rows
        var next = maxOf(nextId, (rows.maxOfOrNull { it.id } ?: 0) + 1)
        val oldLines = rows.map { it.line }
        // The common head and tail first: most rewrites touch a few lines in one place.
        var head = 0
        while (head < oldLines.size && head < newLines.size && oldLines[head] == newLines[head]) head++
        var tail = 0
        while (tail < oldLines.size - head && tail < newLines.size - head &&
            oldLines[oldLines.size - 1 - tail] == newLines[newLines.size - 1 - tail]
        ) tail++
        val oldMid = rows.subList(head, rows.size - tail)
        val newMid = newLines.subList(head, newLines.size - tail)
        val result = ArrayList<Row>(newLines.size)
        result += rows.subList(0, head)
        // Unchanged lines inside the middle, by the longest common subsequence — bounded, since a
        // wholesale rewrite (nothing in common) pairs its lines in order just as well.
        val matches = if (oldMid.size.toLong() * newMid.size <= LCS_LIMIT) lcs(oldMid.map { it.line }, newMid) else emptyList()
        var o = 0
        var n = 0
        fun pairUp(oldEnd: Int, newEnd: Int) {
            while (n < newEnd) {
                result += if (o < oldEnd) Row(oldMid[o++].id, newMid[n++]) else Row(next++, newMid[n++])
            }
            o = oldEnd
        }
        for ((oi, ni) in matches) {
            pairUp(oi, ni)
            result += Row(oldMid[oi].id, newMid[ni])
            o = oi + 1
            n = ni + 1
        }
        pairUp(oldMid.size, newMid.size)
        result += rows.subList(rows.size - tail, rows.size)
        return result
    }

    /** Pairs (old index, new index) of equal lines, in order — the longest such chain. */
    private fun lcs(a: List<String>, b: List<String>): List<Pair<Int, Int>> {
        if (a.isEmpty() || b.isEmpty()) return emptyList()
        val table = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) {
            for (j in b.indices.reversed()) {
                table[i][j] = if (a[i] == b[j]) table[i + 1][j + 1] + 1 else maxOf(table[i + 1][j], table[i][j + 1])
            }
        }
        val pairs = ArrayList<Pair<Int, Int>>()
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> { pairs += i to j; i++; j++ }
                table[i + 1][j] >= table[i][j + 1] -> i++
                else -> j++
            }
        }
        return pairs
    }

    /** Above this many line comparisons the middle is paired in order instead (a rewrite of thousands of lines). */
    private const val LCS_LIMIT = 4_000_000L
}
