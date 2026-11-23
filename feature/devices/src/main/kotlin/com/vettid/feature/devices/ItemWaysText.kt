package com.vettid.feature.devices

import com.vettid.core.data.devices.ItemWay

/** The ways an item allows, in the order the agent's page lists them (`use` always first). */
fun shownWays(ways: Collection<String>): List<ItemWay> =
    ItemWay.entries.filter { it == ItemWay.USE || it.wire in ways }

/** The short labels of the agent's page rows. */
object WaysText {
    fun shortRes(w: ItemWay): Int = when (w) {
        ItemWay.USE -> R.string.ways_short_use
        ItemWay.RUN -> R.string.ways_short_run
        ItemWay.PROXY -> R.string.ways_short_proxy
        ItemWay.REVEAL -> R.string.ways_short_reveal
    }
}

/**
 * A `leash.item.set` `bad_request`'s `error.message` as ANDROID-PLAN 0.1.138 AA7 "Saving" shows it (VAULT-MESSAGING
 * 0.93.2 §10.11 "Why a setting is refused"): the message itself when it is 1 to 200 bytes of printable ASCII (0x20-0x7E),
 * never parsed; else null, and the page shows the fallback text. Pure.
 */
object VaultReason {
    private const val MAX_BYTES = 200
    private const val FIRST = 0x20
    private const val LAST = 0x7E

    fun shown(message: String?): String? =
        message?.takeIf { m -> m.isNotEmpty() && m.length <= MAX_BYTES && m.all { it.code in FIRST..LAST } }
}
