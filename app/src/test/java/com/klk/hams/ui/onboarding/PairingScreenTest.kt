package com.klk.hams.ui.onboarding

import com.klk.hams.provisioning.BindResult
import com.klk.hams.provisioning.ReleaseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

class PairingScreenTest {
    @Test fun bind_failures_map_to_messages() {
        assertEquals("Unknown unit ID. Check and retry.", bindFailureMessage(BindResult.NotFound))
        assertEquals("This device is already bound to HAMS_TEST_001. Reset pairing first, then bind.", bindFailureMessage(BindResult.FingerprintInUse("HAMS_TEST_001")))
        assertEquals("This device is already bound to another unit. Reset pairing first, then bind.", bindFailureMessage(BindResult.FingerprintInUse(null)))
        assertEquals("That unit is bound to another device. Use the admin dashboard to move it.", bindFailureMessage(BindResult.AlreadyBound))
        assertEquals("This unit is finishing release from another device. Try again in a few minutes.", bindFailureMessage(BindResult.Draining))
        assertEquals("Setup error (auth). Contact your supervisor.", bindFailureMessage(BindResult.Unauthorized))
        assertEquals("Supervisor code rejected. Check and retry.", bindFailureMessage(BindResult.AdminAuthFailed))
        assertEquals("Supervisor code is not configured. Contact office admin.", bindFailureMessage(BindResult.AdminNotConfigured))
        assertEquals("No connection. Connect and retry.", bindFailureMessage(BindResult.Error("timeout")))
        assertNull(bindFailureMessage(BindResult.Success("HAMS_TEST_003")))
    }

    @Test fun release_failures_map_to_messages() {
        assertEquals("This device no longer owns that unit. Pair again or contact office admin.", releaseFailureMessage(ReleaseResult.NotFound))
        assertEquals("Setup error (auth). Contact your supervisor.", releaseFailureMessage(ReleaseResult.Unauthorized))
        assertEquals("Supervisor code rejected. Check and retry.", releaseFailureMessage(ReleaseResult.AdminAuthFailed))
        assertEquals("Supervisor code is not configured. Contact office admin.", releaseFailureMessage(ReleaseResult.AdminNotConfigured))
        assertEquals("No connection. Connect and retry.", releaseFailureMessage(ReleaseResult.Error("timeout")))
        assertNull(releaseFailureMessage(ReleaseResult.Success))
    }

    @Test fun admin_code_lockout_starts_after_five_failures_and_expires_after_60_seconds() {
        val now = 1_000L
        var state = AdminCodeLockout()

        repeat(4) { state = state.recordFailure(now) }
        assertFalse(state.isLocked(now))

        state = state.recordFailure(now)
        assertTrue(state.isLocked(now))
        assertEquals(60, state.remainingSeconds(now))
        assertEquals(1, state.remainingSeconds(now + 59_001L))
        assertFalse(state.isLocked(now + 60_000L))
    }

    @Test fun pairing_screen_accepts_and_renders_revocation_notice() {
        val source = sourceText("src/main/java/com/klk/hams/ui/onboarding/PairingScreen.kt")

        assertTrue(source.contains("notice: String? = null"))
        assertTrue(source.contains("text = notice"))
    }

    @Test fun main_activity_observes_and_clears_revocation() {
        val source = sourceText("src/main/java/com/klk/hams/MainActivity.kt")

        assertTrue(source.contains("provisioningRevocation.collectAsState()"))
        assertTrue(source.contains("app.provisioningRevocation.value = null"))
    }

    private fun sourceText(modulePath: String): String {
        val moduleFile = Paths.get(modulePath)
        val rootFile = Paths.get("app").resolve(modulePath)
        val path = when {
            Files.exists(moduleFile) -> moduleFile
            else -> rootFile
        }
        return String(Files.readAllBytes(path))
    }
}
