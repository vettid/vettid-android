package com.vettid.core.data.votes

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Whether the phone's network is unmetered now (#314 item 1, owner decision a: after 7 days a `gave_up` record downloads
 * rows.json on an unmetered network, and at most weekly on a metered one). Null when it cannot be told: counted as metered.
 */
object NetworkMeter {
    fun unmetered(context: Context): Boolean? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = runCatching { cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) } }.getOrNull()
        return caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
