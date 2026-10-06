package com.vettid.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.vettid.core.relay.NetworkGate

/**
 * The default network as the app may use it: present, with internet, and not blocked for this app. After Doze the
 * system lifts the app's network restrictions a moment after the app starts, which the first requests race
 * ([com.vettid.core.relay.TransportRetry] waits on this before it retries). [onNewNetwork] runs when the default
 * network changes or becomes usable again, so pooled connections from before are dropped rather than reused.
 */
class ConnectivityGate(context: Context, private val onNewNetwork: () -> Unit = {}) : NetworkGate {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    @Volatile
    private var network: Network? = cm.activeNetwork

    @Volatile
    private var blocked = false

    @Volatile
    private var internet = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false

    override fun usable(): Boolean = network != null && internet && !blocked

    init {
        cm.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: Network) {
                    if (n != network) {
                        network = n
                        onNewNetwork()
                    }
                }

                override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                    network = n
                    internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }

                override fun onBlockedStatusChanged(n: Network, isBlocked: Boolean) {
                    val was = blocked
                    network = n
                    blocked = isBlocked
                    if (was && !isBlocked) onNewNetwork()
                }

                override fun onLost(n: Network) {
                    if (n == network) network = null
                }
            },
        )
    }
}
