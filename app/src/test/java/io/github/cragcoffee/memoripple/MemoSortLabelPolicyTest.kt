package io.github.cragcoffee.memoripple

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The wall's sort (2026-09-21 23:15): 「手動で並べた順」 is the manual order's name; a chosen order is a way of looking, not a narrowing — no 「作成が新しい順　クリア」 strip. */
class MemoSortLabelPolicyTest {
    private fun text(path: String) = File(path).let { if (it.isFile) it else File("app/$path") }.readText()

    @Test
    fun theManualOrderIsNamedAndNoSortBannersItself() {
        val screen = text("src/main/java/io/github/cragcoffee/memoripple/ui/memos/MemoListScreen.kt")
        assertTrue(screen.contains("MemoSortMode.MANUAL -> \"手動で並べた順\""))
        val strip = screen.substringAfter("val activeDisplayParts = buildList {").substringBefore("\n    }")
        assertFalse("no sort in the strip", strip.contains("state.sort"))
        val clear = screen.substringAfter("val clearNarrowing: () -> Unit = {").substringBefore("\n    }")
        assertFalse("クリア leaves the order alone, as it leaves the view", clear.contains("onSortChange"))
        assertTrue(text("src/main/java/io/github/cragcoffee/memoripple/ui/memos/MemoListViewModel.kt").contains("手動で並べた順に切り替えました"))
    }
}
