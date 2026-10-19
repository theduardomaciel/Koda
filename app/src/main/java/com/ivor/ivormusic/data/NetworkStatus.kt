package com.ivor.ivormusic.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Whether [capabilities] describe a network that can actually reach the
 * internet: an active network that is both connected and validated.
 *
 * One predicate for a live [NetworkCapabilities] callback and for a one-shot
 * query ([hasValidatedNetwork]), so the `isOnline` flow and the callers that
 * check before a request cannot drift apart.
 */
fun hasValidatedInternet(capabilities: NetworkCapabilities?): Boolean =
    capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

/**
 * Whether the device can reach the network right now.
 *
 * Unknown reads as connected: a permissions or API oddity must not turn a
 * working screen into a bogus "check your connection". A request that really
 * cannot run fails through its caller's normal fallback. No active network is
 * an offline answer, not an unknown one.
 */
fun hasValidatedNetwork(context: Context): Boolean {
    return try {
        val cm = context.applicationContext
            .getSystemService(ConnectivityManager::class.java) ?: return true
        hasValidatedInternet(cm.getNetworkCapabilities(cm.activeNetwork))
    } catch (e: Exception) {
        true
    }
}
