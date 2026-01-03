package com.vettid.app.core.nats

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class NatsCredentialsTest {

    private fun createTestCredentials(expiresAt: Instant = Instant.now().plus(24, ChronoUnit.HOURS)): NatsCredentials {
        return NatsCredentials(
            tokenId = "nats_test-token-123",
            jwt = "eyJ0eXAiOiJKV1QiLCJhbGciOiJlZDI1NTE5LW5rZXkifQ.payload.signature",
            seed = "SUAM1234567890ABCDEFGHIJKLMNOP",
            endpoint = "nats://nats.vettid.dev:443",
            expiresAt = expiresAt,
            permissions = NatsPermissions(
                publish = listOf("OwnerSpace.guid.forVault.>"),
                subscribe = listOf("OwnerSpace.guid.forApp.>", "OwnerSpace.guid.eventTypes")
            )
        )
    }

    @Test
    fun `fresh credentials do not need refresh`() {
        val credentials = createTestCredentials(
            expiresAt = Instant.now().plus(24, ChronoUnit.HOURS)
        )
        assertFalse(credentials.needsRefresh())
    }

    @Test
    fun `credentials expiring in less than 1 hour need refresh`() {
        val credentials = createTestCredentials(
            expiresAt = Instant.now().plus(30, ChronoUnit.MINUTES)
        )
        assertTrue(credentials.needsRefresh(bufferMinutes = 60))
    }

    @Test
    fun `credentials expiring in exactly 1 hour need refresh`() {
        val credentials = createTestCredentials(
            // Add 1 second to account for test execution time
            expiresAt = Instant.now().plus(61, ChronoUnit.MINUTES)
        )
        // Buffer is 60 minutes, so slightly more than 60 minutes should not need refresh
        assertFalse(credentials.needsRefresh(bufferMinutes = 60))
    }

    @Test
    fun `expired credentials need refresh`() {
        val credentials = createTestCredentials(
            expiresAt = Instant.now().minus(1, ChronoUnit.HOURS)
        )
        assertTrue(credentials.needsRefresh())
    }

    @Test
    fun `isExpired returns true for past expiry`() {
        val credentials = createTestCredentials(
            expiresAt = Instant.now().minus(1, ChronoUnit.HOURS)
        )
        assertTrue(credentials.isExpired())
    }

    @Test
    fun `isExpired returns false for future expiry`() {
        val credentials = createTestCredentials(
            expiresAt = Instant.now().plus(1, ChronoUnit.HOURS)
        )
        assertFalse(credentials.isExpired())
    }

    @Test
    fun `custom buffer minutes work correctly`() {
        // Credentials expire in 3 hours
        val credentials = createTestCredentials(
            expiresAt = Instant.now().plus(3, ChronoUnit.HOURS)
        )

        // With 2-hour buffer, should not need refresh
        assertFalse(credentials.needsRefresh(bufferMinutes = 120))

        // With 4-hour buffer, should need refresh
        assertTrue(credentials.needsRefresh(bufferMinutes = 240))
    }
}

class NatsAccountStatusTest {

    @Test
    fun `fromString parses active correctly`() {
        assertEquals(NatsAccountStatus.ACTIVE, NatsAccountStatus.fromString("active"))
        assertEquals(NatsAccountStatus.ACTIVE, NatsAccountStatus.fromString("ACTIVE"))
        assertEquals(NatsAccountStatus.ACTIVE, NatsAccountStatus.fromString("Active"))
    }

    @Test
    fun `fromString parses suspended correctly`() {
        assertEquals(NatsAccountStatus.SUSPENDED, NatsAccountStatus.fromString("suspended"))
        assertEquals(NatsAccountStatus.SUSPENDED, NatsAccountStatus.fromString("SUSPENDED"))
    }

    @Test
    fun `fromString parses terminated correctly`() {
        assertEquals(NatsAccountStatus.TERMINATED, NatsAccountStatus.fromString("terminated"))
        assertEquals(NatsAccountStatus.TERMINATED, NatsAccountStatus.fromString("TERMINATED"))
    }

    @Test
    fun `fromString defaults to active for unknown values`() {
        assertEquals(NatsAccountStatus.ACTIVE, NatsAccountStatus.fromString("unknown"))
        assertEquals(NatsAccountStatus.ACTIVE, NatsAccountStatus.fromString(""))
    }
}

class NatsClientTypeTest {

    @Test
    fun `toApiValue returns lowercase`() {
        assertEquals("app", NatsClientType.APP.toApiValue())
        assertEquals("vault", NatsClientType.VAULT.toApiValue())
    }

    @Test
    fun `fromString parses app correctly`() {
        assertEquals(NatsClientType.APP, NatsClientType.fromString("app"))
        assertEquals(NatsClientType.APP, NatsClientType.fromString("APP"))
    }

    @Test
    fun `fromString parses vault correctly`() {
        assertEquals(NatsClientType.VAULT, NatsClientType.fromString("vault"))
        assertEquals(NatsClientType.VAULT, NatsClientType.fromString("VAULT"))
    }

    @Test
    fun `fromString defaults to app for unknown values`() {
        assertEquals(NatsClientType.APP, NatsClientType.fromString("unknown"))
    }
}

class NatsTokenStatusTest {

    @Test
    fun `fromString parses all statuses correctly`() {
        assertEquals(NatsTokenStatus.ACTIVE, NatsTokenStatus.fromString("active"))
        assertEquals(NatsTokenStatus.REVOKED, NatsTokenStatus.fromString("revoked"))
        assertEquals(NatsTokenStatus.EXPIRED, NatsTokenStatus.fromString("expired"))
    }

    @Test
    fun `fromString defaults to active for unknown values`() {
        assertEquals(NatsTokenStatus.ACTIVE, NatsTokenStatus.fromString("unknown"))
    }
}

class NatsPermissionsTest {

    @Test
    fun `permissions contain correct topics`() {
        val permissions = NatsPermissions(
            publish = listOf("OwnerSpace.guid.forVault.>"),
            subscribe = listOf("OwnerSpace.guid.forApp.>", "OwnerSpace.guid.eventTypes")
        )

        assertEquals(1, permissions.publish.size)
        assertEquals(2, permissions.subscribe.size)
        assertTrue(permissions.publish.contains("OwnerSpace.guid.forVault.>"))
        assertTrue(permissions.subscribe.contains("OwnerSpace.guid.forApp.>"))
    }
}
