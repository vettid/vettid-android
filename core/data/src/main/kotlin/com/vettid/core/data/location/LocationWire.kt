package com.vettid.core.data.location

import com.vettid.core.vault.LocationSample
import com.vettid.core.vault.LocationWire as Words
import com.vettid.core.vault.VaultApi
import com.vettid.core.vault.VaultJson
import com.vettid.core.vault.locationShareList
import com.vettid.core.vault.locationShareStart
import com.vettid.core.vault.locationShareStop
import com.vettid.core.vault.locationUpdate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/** [LocationOps] over the vault client. */
internal class VaultLocationOps(private val api: VaultApi) : LocationOps {
    override suspend fun start(r: ShareRequest): JsonObject = api.locationShareStart(
        r.connectionId,
        if (r.once) Words.MODE_ONCE else Words.MODE_CONTINUOUS,
        r.precision.wire,
        r.durationSeconds,
        r.intervalSeconds,
        r.trail,
        r.requestId,
    )

    override suspend fun update(sample: LocationSample, ttl: Duration) = api.locationUpdate(sample, ttl)

    override suspend fun stop(shareId: String): JsonObject = api.locationShareStop(shareId)

    override suspend fun list(): JsonObject = api.locationShareList()
}

/** Reading the vault's answers of §10.16. An answer that does not read gives null; nothing is guessed at. */
internal object ShareWire {
    /** `{share_id, expires_at}` of `location.share.start`. */
    class Started(val shareId: String, val expiresAt: Instant)

    fun started(body: JsonObject): Started? {
        val id = VaultJson.str(body, "share_id")?.takeIf { it.isNotEmpty() } ?: return null
        val expires = instant(VaultJson.str(body, "expires_at")) ?: return null
        return Started(id, expires)
    }

    /**
     * The `outgoing` shares of `location.share.list`; [self] is this phone's device id (the source of a share when it matches),
     * [mine] the share ids this phone kept (a source whatever the device id).
     */
    fun outgoing(body: JsonObject, self: String?, mine: Set<String> = emptySet()): List<OutgoingShare>? {
        val list = body["outgoing"] as? JsonArray ?: return null
        return list.map { entry -> share(entry as? JsonObject ?: return null, self, mine) ?: return null }
    }

    private fun share(o: JsonObject, self: String?, mine: Set<String>): OutgoingShare? {
        val id = VaultJson.str(o, "share_id")?.takeIf { it.isNotEmpty() }
        val connection = VaultJson.str(o, "connection_id")?.takeIf { it.isNotEmpty() }
        val mode = VaultJson.str(o, "mode")
        val precision = SharePrecision.entries.firstOrNull { it.wire == VaultJson.str(o, "precision") }
        val expires = instant(VaultJson.str(o, "expires_at"))
        val once = mode == Words.MODE_ONCE
        return if (id == null || connection == null || precision == null || expires == null || (!once && mode != Words.MODE_CONTINUOUS)) {
            null
        } else {
            OutgoingShare(
                shareId = id,
                connectionId = connection,
                once = once,
                precision = precision,
                intervalSeconds = VaultJson.long(o, "interval_seconds"),
                trail = (o["history"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true",
                expiresAt = expires,
                lastSentAt = instant(VaultJson.str(o, "last_sent_at")),
                fromThisPhone = id in mine || (self != null && VaultJson.str(o, "device_id") == self),
            )
        }
    }

    private fun instant(s: String?): Instant? = try {
        s?.let { Instant.parse(it) }
    } catch (_: DateTimeParseException) {
        null
    }
}
