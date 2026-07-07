package com.klk.hams.provisioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BindingRevalidatorTest {
    @Test fun bound_proceeds() {
        assertEquals(BindingDecision.PROCEED, BindingRevalidator.decide(VerifyResult.Bound))
    }

    @Test fun keep_proceeds() {
        assertEquals(BindingDecision.PROCEED, BindingRevalidator.decide(VerifyResult.Keep("not_found")))
    }

    @Test fun released_flushes() {
        assertEquals(BindingDecision.RELEASED_FLUSH, BindingRevalidator.decide(VerifyResult.Released))
    }

    @Test fun bound_other_is_boundOther() {
        assertEquals(BindingDecision.BOUND_OTHER, BindingRevalidator.decide(VerifyResult.BoundOther))
    }

    @Test fun released_flush_revokes_only_when_all_cuts_and_301_uploaded() {
        // Happy path: cuts flushed AND 301 acked.
        assertTrue(BindingRevalidator.shouldRevokeAfterFlush(releasedFlush = true, allCutsFlushed = true, row301Pushed = 1))
        // 301 not yet acked -> keep binding.
        assertFalse(BindingRevalidator.shouldRevokeAfterFlush(releasedFlush = true, allCutsFlushed = true, row301Pushed = 0))
        assertFalse(BindingRevalidator.shouldRevokeAfterFlush(releasedFlush = true, allCutsFlushed = true, row301Pushed = null))
        // 301 acked but task cuts still pending -> keep binding (no data loss).
        assertFalse(BindingRevalidator.shouldRevokeAfterFlush(releasedFlush = true, allCutsFlushed = false, row301Pushed = 1))
        // Not a released flush at all.
        assertFalse(BindingRevalidator.shouldRevokeAfterFlush(releasedFlush = false, allCutsFlushed = true, row301Pushed = 1))
    }
}
