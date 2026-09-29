package io.github.cragcoffee.memoripple.domain.memos

import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.domain.BodyText
import io.github.cragcoffee.memoripple.domain.WorkCommentSyntax
import java.text.Collator
import java.util.Locale

enum class MemoFilterMode {
    ALL,
    PINNED,
}

enum class MemoSortMode {
    UPDATED_DESC,
    CREATED_DESC,
    TITLE_ASC,
    /** 並べた順: where the hand put each card; cards not placed yet (index 0) come first, newest first. */
    MANUAL,
}

enum class TagMatchMode {
    ANY,
    ALL,
}

/**
 * How the one wall draws its cards.
 *
 * There used to be two tabs for this, and the same memo would silently change tab when a `■` was
 * typed into its body — the notation decided where a memo lived, not its writer. Now the wall is
 * one place and this is only a way of looking at it: combined shows everything, with structured
 * memos drawing their bones inside the card; the other two are the old tabs, kept as views for
 * anyone who read the wall that way.
 */
enum class WallDisplayMode(val storageId: String, val label: String) {
    COMBINED("combined", "メモとアウトライン"),
    MEMO("memo", "メモのみ"),
    OUTLINE("outline", "アウトラインのみ"),
    ;

    companion object {
        fun fromStorageId(storageId: String?): WallDisplayMode =
            entries.firstOrNull { it.storageId == storageId } ?: COMBINED
    }
}

fun MemoEntity.displayTitle(): String = title.ifBlank { "無題のメモ" }

/**
 * The body as a reader sees it: the opening lines with the writing markers taken off. A list is
 * for reading, so it shows the words rather than the notation that produced them — and it keeps
 * the writer's line breaks, because where a line ends is part of what was written. The card
 * decides how many of those lines fit; blank lines carry no words and are skipped.
 */
fun MemoEntity.previewText(maxChars: Int = PREVIEW_MAX_CHARS): String {
    val builder = StringBuilder()
    for (line in body.lineSequence()) {
        val words = BodyText
            .readable(WorkCommentSyntax.recognize(line)?.text ?: line.trimStart())
            .trim()
        if (words.isEmpty()) continue
        if (builder.isNotEmpty()) builder.append('\n')
        builder.append(words)
        if (builder.length >= maxChars) break
    }
    return builder.toString()
}

private const val PREVIEW_MAX_CHARS = 160

object MemoOrganizationPolicy {
    fun organize(
        memos: List<MemoEntity>,
        query: String,
        filter: MemoFilterMode,
        sort: MemoSortMode,
        locale: Locale = Locale.getDefault(),
        tagIdsByMemo: Map<Long, Set<Long>> = emptyMap(),
        selectedTagIds: Set<Long> = emptySet(),
        tagMatchMode: TagMatchMode = TagMatchMode.ANY,
        tagNamesByMemo: Map<Long, List<String>> = emptyMap(),
    ): List<MemoEntity> {
        val parsed = MemoSearch.parse(query)
        val searched = if (parsed.isEmpty) {
            memos
        } else {
            memos.filter { memo ->
                MemoSearch.matches(parsed, memo.title, memo.body, tagNamesByMemo[memo.id].orEmpty())
            }
        }
        val filtered = when (filter) {
            MemoFilterMode.ALL -> searched
            MemoFilterMode.PINNED -> searched.filter(MemoEntity::isPinned)
        }
        val tagFiltered = if (selectedTagIds.isEmpty()) {
            filtered
        } else {
            filtered.filter { memo ->
                val memoTagIds = tagIdsByMemo[memo.id].orEmpty()
                when (tagMatchMode) {
                    TagMatchMode.ANY -> selectedTagIds.any(memoTagIds::contains)
                    TagMatchMode.ALL -> selectedTagIds.all(memoTagIds::contains)
                }
            }
        }
        val sortComparator = sortComparator(sort, locale)
        return tagFiltered.sortedWith(
            compareByDescending<MemoEntity> { it.isPinned }.then(sortComparator),
        )
    }

    private fun sortComparator(sort: MemoSortMode, locale: Locale): Comparator<MemoEntity> =
        when (sort) {
            MemoSortMode.UPDATED_DESC -> compareByDescending<MemoEntity> { it.updatedAt }
                .thenByDescending(MemoEntity::id)

            MemoSortMode.CREATED_DESC -> compareByDescending<MemoEntity> { it.createdAt }
                .thenByDescending(MemoEntity::id)

            MemoSortMode.MANUAL -> compareBy<MemoEntity> { it.sortIndex }
                .thenByDescending { it.updatedAt }
                .thenByDescending(MemoEntity::id)

            MemoSortMode.TITLE_ASC -> {
                val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
                Comparator { left, right ->
                    val titleComparison = collator.compare(left.displayTitle(), right.displayTitle())
                    if (titleComparison != 0) {
                        titleComparison
                    } else {
                        val updateComparison = right.updatedAt.compareTo(left.updatedAt)
                        if (updateComparison != 0) updateComparison else right.id.compareTo(left.id)
                    }
                }
            }
        }
}
