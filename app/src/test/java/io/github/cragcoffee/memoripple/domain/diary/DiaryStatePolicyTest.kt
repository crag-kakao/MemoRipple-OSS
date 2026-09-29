package io.github.cragcoffee.memoripple.domain.diary

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiaryStatePolicyTest {
    @Test
    fun allowedTransitionsFollowTheSingleCorrectionLifecycle() {
        assertTrue(DiaryStatePolicy.canTransition(DiaryState.DRAFT, DiaryState.FINALIZED))
        assertTrue(DiaryStatePolicy.canTransition(DiaryState.FINALIZED, DiaryState.CORRECTING))
        assertTrue(DiaryStatePolicy.canTransition(DiaryState.CORRECTING, DiaryState.LOCKED))
    }

    @Test
    fun reverseSkippedAndLockedTransitionsAreRejected() {
        assertFalse(DiaryStatePolicy.canTransition(DiaryState.DRAFT, DiaryState.CORRECTING))
        assertFalse(DiaryStatePolicy.canTransition(DiaryState.FINALIZED, DiaryState.DRAFT))
        assertFalse(DiaryStatePolicy.canTransition(DiaryState.CORRECTING, DiaryState.FINALIZED))
        assertFalse(DiaryStatePolicy.canTransition(DiaryState.LOCKED, DiaryState.CORRECTING))
        assertFalse(DiaryStatePolicy.canTransition(DiaryState.LOCKED, DiaryState.DRAFT))
    }
}

/**
 * Since the journal redesign (HANDOFF §16.18) the four stored states are read as two: LOCKED
 * is read-only, everything else is an ordinary editable journal. The old names stay on disk.
 */
class JournalEditPolicyTest {
    @Test
    fun onlyLockedRefusesEditing() {
        assertTrue(DiaryStatePolicy.canEdit(DiaryState.DRAFT))
        assertTrue(DiaryStatePolicy.canEdit(DiaryState.FINALIZED))
        assertTrue(DiaryStatePolicy.canEdit(DiaryState.CORRECTING))
        assertFalse(DiaryStatePolicy.canEdit(DiaryState.LOCKED))
    }
}
