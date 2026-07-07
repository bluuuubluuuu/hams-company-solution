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

    @Test fun released_flush_revokes_only_after_301_uploaded() {
        assertTrue(BindingRevalidator.shouldRevokeAfterFlush(releasedFlush = true, row301Pushed = 1))
        assertFalse(BindingRevalidator.shouldRevokeAfterFlush(releasedFlush = true, row301Pushed = 0))
        assertFalse(BindingRevalidator.shouldRevokeAfterFlush(releasedFlush = true, row301Pushed = null))
        assertFalse(BindingRevalidator.shouldRevokeAfterFlush(releasedFlush = false, row301Pushed = 1))
    }
}
