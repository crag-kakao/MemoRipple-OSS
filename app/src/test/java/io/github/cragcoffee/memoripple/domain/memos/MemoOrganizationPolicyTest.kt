package io.github.cragcoffee.memoripple.domain.memos

import io.github.cragcoffee.memoripple.data.MemoEntity
import java.util.Locale
import kotlin.system.measureTimeMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoOrganizationPolicyTest {
    private val locale = Locale.JAPAN

    @Test
    fun allGroupsPinnedBeforeUnpinnedAndSortsInsideEachGroup() {
        val values = listOf(
            memo(1, "unpinned newest", updatedAt = 500),
            memo(2, "pinned older", updatedAt = 200, pinned = true),
            memo(3, "pinned newer", updatedAt = 300, pinned = true),
            memo(4, "unpinned older", updatedAt = 100),
        )

        val result = organize(values)

        assertEquals(listOf(3L, 2L, 1L, 4L), result.map(MemoEntity::id))
    }

    @Test
    fun pinnedFilterReturnsOnlyPinnedAndAppliesSelectedSort() {
        val values = listOf(
            memo(1, "B", createdAt = 300, pinned = true),
            memo(2, "A", createdAt = 100, pinned = true),
            memo(3, "C", createdAt = 500),
        )

        val result = organize(
            values,
            filter = MemoFilterMode.PINNED,
            sort = MemoSortMode.CREATED_DESC,
        )

        assertEquals(listOf(1L, 2L), result.map(MemoEntity::id))
    }

    @Test
    fun allSortModesUseStableTieBreakers() {
        val values = listOf(
            memo(1, "同じ", createdAt = 10, updatedAt = 20),
            memo(3, "同じ", createdAt = 10, updatedAt = 20),
            memo(2, "同じ", createdAt = 10, updatedAt = 20),
        )

        MemoSortMode.entries.forEach { sort ->
            assertEquals(
                listOf(3L, 2L, 1L),
                organize(values, sort = sort).map(MemoEntity::id),
            )
        }
    }

    @Test
    fun titleSortUsesDisplayTitleAndLocaleAwareCollation() {
        val values = listOf(
            memo(1, "", updatedAt = 100),
            memo(2, "あいう", updatedAt = 100),
            memo(3, "アイウ", updatedAt = 200),
        )

        val result = organize(values, sort = MemoSortMode.TITLE_ASC)

        assertEquals(listOf(3L, 2L, 1L), result.map(MemoEntity::id))
        assertEquals("無題のメモ", values.first().displayTitle())
    }

    @Test
    fun searchComposesWithThePinnedFilterAndPinnedGrouping() {
        val values = listOf(
            memo(1, "小説メモ"),
            memo(2, "構想", body = "小説の設定", pinned = true),
            memo(3, "小説資料", pinned = true),
            memo(4, "買い物"),
        )

        val pinnedOnly = organize(
            values,
            query = "小説",
            filter = MemoFilterMode.PINNED,
        )
        val all = organize(values, query = "小説")

        assertEquals(listOf(3L, 2L), pinnedOnly.map(MemoEntity::id))
        assertEquals(listOf(3L, 2L, 1L), all.map(MemoEntity::id))
    }

    @Test
    fun tagFilterComposesWithSearchPinnedFilterSortAndPinnedGrouping() {
        val values = listOf(
            memo(1, "小説A", updatedAt = 200),
            memo(2, "小説B", pinned = true, updatedAt = 100),
            memo(3, "小説C", pinned = true, updatedAt = 300),
            memo(4, "買い物", updatedAt = 400),
        )
        val tags = mapOf(1L to setOf(10L), 2L to setOf(10L, 20L), 3L to setOf(10L))

        val result = organize(
            values = values,
            query = "小説",
            filter = MemoFilterMode.PINNED,
            tagIdsByMemo = tags,
            selectedTagIds = setOf(10L),
        )

        assertEquals(listOf(3L, 2L), result.map(MemoEntity::id))
    }

    @Test
    fun missingOrDeletedSelectedTagProducesAnEmptyResult() {
        val values = listOf(memo(1, "A"), memo(2, "B"))

        assertTrue(
            organize(values, selectedTagIds = setOf(999L), tagIdsByMemo = emptyMap()).isEmpty(),
        )
    }

    @Test
    fun emptySingleAnyAndAllTagSelectionsFollowSetSemantics() {
        val values = listOf(memo(1, "A"), memo(2, "B"), memo(3, "C"))
        val relations = mapOf(
            1L to setOf(10L),
            2L to setOf(10L, 20L),
            3L to setOf(20L),
        )

        assertEquals(3, organize(values, tagIdsByMemo = relations).size)
        assertEquals(
            listOf(2L, 1L),
            organize(values, tagIdsByMemo = relations, selectedTagIds = setOf(10)).map(MemoEntity::id),
        )
        assertEquals(
            setOf(1L, 2L, 3L),
            organize(
                values,
                tagIdsByMemo = relations,
                selectedTagIds = setOf(10, 20),
                tagMatchMode = TagMatchMode.ANY,
            ).mapTo(hashSetOf(), MemoEntity::id),
        )
        assertEquals(
            listOf(2L),
            organize(
                values,
                tagIdsByMemo = relations,
                selectedTagIds = setOf(10, 20),
                tagMatchMode = TagMatchMode.ALL,
            ).map(MemoEntity::id),
        )
    }

    @Test
    fun multiTagFilterComposesAfterSearchAndTheFilterBeforePinnedGrouping() {
        val values = listOf(
            memo(1, "企画 A", updatedAt = 500),
            memo(2, "企画 B", pinned = true, updatedAt = 100),
            memo(3, "企画 C", pinned = true, updatedAt = 900),
            memo(4, "日記", updatedAt = 800),
        )
        val relations = mapOf(1L to setOf(10L, 20L), 2L to setOf(10L, 20L), 3L to setOf(10L, 20L))

        val result = organize(
            values,
            query = "企画",
            filter = MemoFilterMode.PINNED,
            tagIdsByMemo = relations,
            selectedTagIds = setOf(10, 20),
            tagMatchMode = TagMatchMode.ALL,
        )

        assertEquals(listOf(3L, 2L), result.map(MemoEntity::id))
    }

    @Test
    fun thousandMemosTwoHundredTagsAndTenThousandRelationsRemainPractical() {
        val values = List(1_000) { memo(it + 1L, "メモ$it", pinned = it % 2 == 0) }
        val relations = values.associate { memo ->
            memo.id to (0 until 10).mapTo(hashSetOf()) { ((memo.id + it) % 200L) + 1L }
        }
        lateinit var result: List<MemoEntity>

        val elapsed = measureTimeMillis {
            repeat(10) {
                result = organize(
                    values,
                    filter = MemoFilterMode.PINNED,
                    tagIdsByMemo = relations,
                    selectedTagIds = setOf(10, 11),
                    tagMatchMode = TagMatchMode.ANY,
                )
            }
        }

        assertTrue(result.all { it.isPinned })
        assertTrue("10 large runs took ${elapsed}ms", elapsed < 2_000)
    }

    @Test
    fun fiveHundredMemosRemainPracticalWithoutIndexesOrBenchmarkDependency() {
        val values = List(600) { index ->
            memo(
                id = index.toLong() + 1,
                title = "メモ${index.toString().padStart(3, '0')}",
                body = if (index % 3 == 0) "対象本文" else "本文",
                updatedAt = index.toLong(),
                favorite = index % 2 == 0,
                pinned = index % 10 == 0,
            )
        }
        lateinit var result: List<MemoEntity>

        val elapsed = measureTimeMillis {
            repeat(20) {
                result = organize(
                    values,
                    query = "対象",
                    filter = MemoFilterMode.PINNED,
                    sort = MemoSortMode.TITLE_ASC,
                )
            }
        }

        assertEquals(20, result.size)
        assertTrue("20 runs took ${elapsed}ms", elapsed < 2_000)
    }

    @Test
    fun largeTagCompositionRemainsPractical() {
        val values = List(500) { index ->
            memo(
                id = index.toLong() + 1,
                title = "メモ$index",
                favorite = index % 2 == 0,
                pinned = index % 10 == 0,
                updatedAt = index.toLong(),
            )
        }
        val tags = (1L..100L).associateWith { tagId -> "タグ$tagId" }
        val relations = buildMap<Long, Set<Long>> {
            values.forEach { memo ->
                put(memo.id, (1L..10L).map { offset -> ((memo.id + offset) % tags.size) + 1 }.toSet())
            }
        }
        lateinit var result: List<MemoEntity>

        val elapsed = measureTimeMillis {
            repeat(20) {
                result = organize(
                    values = values,
                    filter = MemoFilterMode.PINNED,
                    tagIdsByMemo = relations,
                    selectedTagIds = setOf(10L),
                )
            }
        }

        assertTrue(result.all { it.isPinned && 10L in relations[it.id].orEmpty() })
        assertTrue("20 tag runs took ${elapsed}ms", elapsed < 2_000)
    }

    private fun organize(
        values: List<MemoEntity>,
        query: String = "",
        filter: MemoFilterMode = MemoFilterMode.ALL,
        sort: MemoSortMode = MemoSortMode.UPDATED_DESC,
        tagIdsByMemo: Map<Long, Set<Long>> = emptyMap(),
        selectedTagIds: Set<Long> = emptySet(),
        tagMatchMode: TagMatchMode = TagMatchMode.ANY,
    ): List<MemoEntity> = MemoOrganizationPolicy.organize(
        values,
        query,
        filter,
        sort,
        locale,
        tagIdsByMemo,
        selectedTagIds,
        tagMatchMode,
    )

    @Test
    fun previewKeepsTheWritersLineBreaks() {
        val written = memo(1, "買い出し", body = "牛乳、卵、パン。\n\nあと電池を買うこと。")
        assertEquals("牛乳、卵、パン。\nあと電池を買うこと。", written.previewText())
    }

    @Test
    fun previewReadsWordsNotNotation() {
        val outlined = memo(2, "段取り", body = "# 今週\n- [x] 不動産へ連絡")
        assertEquals("今週\n不動産へ連絡", outlined.previewText())
    }

    private fun memo(
        id: Long,
        title: String,
        body: String = "",
        createdAt: Long = 0,
        updatedAt: Long = 0,
        favorite: Boolean = false,
        pinned: Boolean = false,
    ) = MemoEntity(
        id = id,
        title = title,
        body = body,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isFavorite = favorite,
        isPinned = pinned,
    )
}

/** 並べた順: the order the hand gave the cards, pinned ones still first, new cards (index 0) newest first. */
class MemoManualOrderTest {
    private fun memo(id: Long, sortIndex: Int, updatedAt: Long = id, pinned: Boolean = false) =
        MemoEntity(id, "m$id", "", createdAt = id, updatedAt = updatedAt, isPinned = pinned, sortIndex = sortIndex)

    @org.junit.Test
    fun manualSortFollowsTheSortIndexThenTheNewestFirst() {
        val memos = listOf(memo(1, 3), memo(2, 1), memo(3, 0, updatedAt = 50), memo(4, 0, updatedAt = 60), memo(5, 2, pinned = true))
        val organized = MemoOrganizationPolicy.organize(memos, "", MemoFilterMode.ALL, MemoSortMode.MANUAL)
        org.junit.Assert.assertEquals(listOf(5L, 4L, 3L, 2L, 1L), organized.map(MemoEntity::id))
    }
}
