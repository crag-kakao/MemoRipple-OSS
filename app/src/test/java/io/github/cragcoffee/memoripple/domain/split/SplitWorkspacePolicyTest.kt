package io.github.cragcoffee.memoripple.domain.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitWorkspacePolicyTest {

    @Test
    fun theRatioStaysBetweenItsWalls() {
        assertEquals(0.35f, SplitWorkspacePolicy.clampRatio(0.10f))
        assertEquals(0.75f, SplitWorkspacePolicy.clampRatio(0.99f))
        assertEquals(0.60f, SplitWorkspacePolicy.clampRatio(0.60f))
    }

    @Test
    fun theDefaultGivesTheWriterALittleMoreThanHalf() {
        assertEquals(0.60f, SplitWorkspacePolicy.DEFAULT_RATIO)
        assertTrue(SplitWorkspacePolicy.DEFAULT_RATIO in SplitWorkspacePolicy.MIN_RATIO..SplitWorkspacePolicy.MAX_RATIO)
    }

    @Test
    fun portraitStacksAndLandscapeSitsSideBySide() {
        assertFalse(SplitWorkspacePolicy.isHorizontal(widthDp = 411, heightDp = 891))
        assertTrue(SplitWorkspacePolicy.isHorizontal(widthDp = 891, heightDp = 411))
    }

    @Test
    fun aWideWindowSitsSideBySideEvenHeldUpright() {
        // An unfolded foldable and an upright tablet are wider than the breakpoint, so the
        // reference earns a column of its own; a phone stays stacked.
        assertTrue(SplitWorkspacePolicy.isHorizontal(widthDp = 673, heightDp = 841))
        assertTrue(SplitWorkspacePolicy.isHorizontal(widthDp = 800, heightDp = 1280))
        assertFalse(SplitWorkspacePolicy.isHorizontal(widthDp = 599, heightDp = 900))
    }

    @Test
    fun aDragMovesTheDividerByItsShareOfTheAxis() {
        assertEquals(0.70f, SplitWorkspacePolicy.ratioAfterDrag(0.60f, 100f, 1000f), 0.0001f)
        assertEquals(0.35f, SplitWorkspacePolicy.ratioAfterDrag(0.40f, -300f, 1000f), 0.0001f)
        assertEquals(0.75f, SplitWorkspacePolicy.ratioAfterDrag(0.70f, 400f, 1000f), 0.0001f)
        // A zero axis cannot divide; the ratio just stays clamped where it was.
        assertEquals(0.60f, SplitWorkspacePolicy.ratioAfterDrag(0.60f, 50f, 0f), 0.0001f)
    }

    @Test
    fun aMemoNeverReferencesItself() {
        assertFalse(
            SplitWorkspacePolicy.canReference(7L, SplitReference(SplitReferenceKind.MEMO, 7L)),
        )
        assertFalse(
            SplitWorkspacePolicy.canReference(7L, SplitReference(SplitReferenceKind.OUTLINE, 7L)),
        )
        assertTrue(
            SplitWorkspacePolicy.canReference(7L, SplitReference(SplitReferenceKind.MEMO, 8L)),
        )
        assertTrue(
            SplitWorkspacePolicy.canReference(7L, SplitReference(SplitReferenceKind.NOTE, 7L)),
        )
    }

    @Test
    fun theSessionResumesOnlyForItsOwnPrimary() {
        val session = SplitWorkspaceSession()
        session.open(3L, SplitReference(SplitReferenceKind.NOTE, 9L), 0.5f)

        assertEquals(SplitReference(SplitReferenceKind.NOTE, 9L), session.resumeFor(3L))
        assertNull(session.resumeFor(4L))
        assertEquals(0.5f, session.ratio)
    }

    @Test
    fun theSwapHandsTheSessionToTheNewPrimaryWithTheOldMemoBehindIt() {
        val session = SplitWorkspaceSession()
        session.open(3L, SplitReference(SplitReferenceKind.MEMO, 9L), 0.45f)

        // こちらを編集: the reference becomes primary, the old primary becomes the reference.
        session.open(9L, SplitReference(SplitReferenceKind.MEMO, 3L), session.ratio)

        assertEquals(SplitReference(SplitReferenceKind.MEMO, 3L), session.resumeFor(9L))
        assertNull(session.resumeFor(3L))
        assertEquals(0.45f, session.ratio)
    }

    @Test
    fun closingTheSessionForgetsEverything() {
        val session = SplitWorkspaceSession()
        session.open(3L, SplitReference(SplitReferenceKind.MEMO, 9L), 0.7f)

        session.close()

        assertNull(session.resumeFor(3L))
        assertEquals(SplitWorkspacePolicy.DEFAULT_RATIO, session.ratio)
    }

    @Test
    fun ratiosOutsideTheWallsAreClampedOnOpen() {
        val session = SplitWorkspaceSession()
        session.open(1L, SplitReference(SplitReferenceKind.MEMO, 2L), 0.95f)
        assertEquals(0.75f, session.ratio)
        session.updateRatio(0.1f)
        assertEquals(0.35f, session.ratio)
    }
}
