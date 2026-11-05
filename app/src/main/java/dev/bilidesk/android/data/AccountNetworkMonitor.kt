// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Collected only while the Activity is started. Connectivity is a retry hint;
 * only the actual platform account response determines whether login expired.
 */
fun accountNetworkAvailability(context: Context) = callbackFlow {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    var current: Network? = null
    var validated = false
    val callback = object: ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            trySend(false) // A Wi-Fi/mobile handover can replace the default without onLost.
            current = network
            validated = false
            trySend(true)
        }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (network != current) return
            val ready = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (ready && !validated) { trySend(false); trySend(true) }
            validated = ready
        }
        override fun onLost(network: Network) {
            if (current == network) { current = null; trySend(false) }
        }
    }
    manager.registerDefaultNetworkCallback(callback)
    awaitClose { manager.unregisterNetworkCallback(callback) }
}.distinctUntilChanged()
