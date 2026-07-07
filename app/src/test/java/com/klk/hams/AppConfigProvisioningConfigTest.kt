package com.klk.hams

import org.junit.Assert.assertEquals
import org.junit.Test

class AppConfigProvisioningConfigTest {
    @Test fun provisioningConfigMirrorsBuildConfig() {
        assertEquals(BuildConfig.HAMS_CLAIM_SECRET, AppConfig.HAMS_CLAIM_SECRET)
        assertEquals(BuildConfig.MANUAL_CLAIM_URL, AppConfig.MANUAL_CLAIM_URL)
        assertEquals(BuildConfig.RELEASE_URL, AppConfig.RELEASE_URL)
        assertEquals(BuildConfig.VERIFY_URL, AppConfig.VERIFY_URL)
        assertEquals(15L, AppConfig.BINDING_CHECK_INTERVAL_MINUTES)
    }
}
