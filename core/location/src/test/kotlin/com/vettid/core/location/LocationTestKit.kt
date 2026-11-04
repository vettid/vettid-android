package com.vettid.core.location

import com.vettid.core.data.location.Fix
import com.vettid.core.data.location.LocationOps
import com.vettid.core.data.location.LocationPlatform
import com.vettid.core.data.location.PositionProvider
import com.vettid.core.data.location.PositionRequest
import com.vettid.core.data.location.ShareRequest
import com.vettid.core.data.location.ShareStore
import com.vettid.core.data.location.StoredShare
import com.vettid.core.vault.LocationSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Duration
import java.time.Instant

/** The test route: positions that must be found nowhere on disk (the data directory scan). */
object Route {
    val points: List<Pair<Double, Double>> = listOf(
        52.520008 to 13.404954,
        52.521223 to 13.406871,
        52.523477 to 13.411733,
        52.516275 to 13.377704,
    )
}

fun obj(s: String): JsonObject = Json.parseToJsonElement(s) as JsonObject

/** A vault that takes everything and remembers what was sent (positions as their wire bodies). */
class SinkOps(private val t0: Instant) : LocationOps {
    val updates = mutableListOf<JsonObject>()
    val stops = mutableListOf<String>()
    private var n = 0

    override suspend fun start(r: ShareRequest): JsonObject {
        val seconds = if (r.once) 900L else r.durationSeconds!!
        return obj("""{"share_id":"s${++n}","expires_at":"${t0.plusSeconds(seconds)}"}""")
    }

    override suspend fun update(sample: LocationSample, ttl: Duration) {
        updates += sample.body()
    }

    override suspend fun stop(shareId: String): JsonObject {
        stops += shareId
        return obj("{}")
    }

    override suspend fun list(): JsonObject = obj("""{"outgoing":[],"incoming":[]}""")
}

class FlowProvider : PositionProvider {
    val fixes = MutableSharedFlow<Fix>()

    override fun fixes(request: PositionRequest): Flow<Fix> = fixes
}

class RecordingPlatform(override val positions: PositionProvider) : LocationPlatform {
    var starts = 0
    var stops = 0

    override fun startService(): Boolean {
        starts++
        return true
    }

    override fun stopService() {
        stops++
    }
}

class MemoryStore : ShareStore {
    var saved: List<StoredShare> = emptyList()

    override fun load(): List<StoredShare> = saved

    override fun save(shares: List<StoredShare>) {
        saved = shares
    }
}
