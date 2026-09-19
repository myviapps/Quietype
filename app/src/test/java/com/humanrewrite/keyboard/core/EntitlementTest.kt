package com.humanrewrite.keyboard.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntitlementTest {
    private val now = 1_000_000L
    private fun entitlement(status: EntitlementStatus, validUntil: Long = now + 100, offlineUntil: Long = now + 100) =
        Entitlement(status, validUntil, lastVerifiedAtMillis = now, offlineExpiresAtMillis = offlineUntil)

    @Test
    fun activeAndGraceUnlock() {
        assertTrue(entitlement(EntitlementStatus.ACTIVE).isPaidFeatureActive(now))
        assertTrue(entitlement(EntitlementStatus.GRACE).isPaidFeatureActive(now))
    }

    @Test
    fun expiredStatusLocks() {
        assertFalse(entitlement(EntitlementStatus.EXPIRED).isPaidFeatureActive(now))
    }

    @Test
    fun pastSubscriptionEndLocks() {
        assertFalse(entitlement(EntitlementStatus.ACTIVE, validUntil = now - 1).isPaidFeatureActive(now))
    }

    @Test
    fun pastOfflineWindowLocks() {
        assertFalse(entitlement(EntitlementStatus.ACTIVE, offlineUntil = now - 1).isPaidFeatureActive(now))
    }
}
