package com.klk.hams.provisioning

import com.klk.hams.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisioningStoreTest {
    private class FakeStore : ProvisioningStore.KeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String) = map[key]
        override fun putString(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    @Test fun unprovisioned_by_default() {
        val s = ProvisioningStore(FakeStore())
        assertFalse(s.isProvisioned())
        assertNull(s.uniqueIdOrNull())
    }

    @Test fun resolve_falls_back_to_buildconfig_when_unset() {
        val s = ProvisioningStore(FakeStore())
        assertEquals(AppConfig.DEVICE_UNIQUE_ID, s.resolveUniqueId())
    }

    @Test fun save_then_resolve_returns_saved() {
        val s = ProvisioningStore(FakeStore())
        s.save("OC154_H042")
        assertTrue(s.isProvisioned())
        assertEquals("OC154_H042", s.uniqueIdOrNull())
        assertEquals("OC154_H042", s.resolveUniqueId())
    }

    @Test fun unique_id_provider_reads_latest_saved_value() {
        val s = ProvisioningStore(FakeStore())
        val provider = s.uniqueIdProvider()
        assertEquals(AppConfig.DEVICE_UNIQUE_ID, provider())

        s.save("OC154_H099")

        assertEquals("OC154_H099", provider())
    }

    @Test fun should_auto_push_requires_provisioned_device_and_pending_tasks() {
        assertFalse(shouldAutoPush(isProvisioned = false, pending = 0))
        assertFalse(shouldAutoPush(isProvisioned = false, pending = 3))
        assertFalse(shouldAutoPush(isProvisioned = true, pending = 0))
        assertTrue(shouldAutoPush(isProvisioned = true, pending = 3))
    }

    @Test fun blank_is_treated_as_unprovisioned() {
        val store = FakeStore().also { it.putString("device_unique_id", "") }
        val s = ProvisioningStore(store)
        assertFalse(s.isProvisioned())
        assertEquals(AppConfig.DEVICE_UNIQUE_ID, s.resolveUniqueId())
    }
}
