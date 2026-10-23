package com.ivor.ivormusic.ui.components

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.ivor.ivormusic.data.hasValidatedInternet
import com.ivor.ivormusic.data.hasValidatedNetwork
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Live "the device can reach the internet" state for a composable.
 *
 * The ViewModels carry their own copies, but the player controls and the
 * download sheets have no single ViewModel to ask, and a static read would go
 * stale the moment the phone loses signal. Same predicate as the Library's
 * offline panel, kept live from the default-network callback.
 */
@Composable
fun rememberIsOnline(): Boolean {
    val context = LocalContext.current
    val flow = remember(context) {
        callbackFlow {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities
                ) {
                    trySend(hasValidatedInternet(capabilities))
                }

                override fun onLost(network: Network) {
                    trySend(false)
                }
            }
            trySend(hasValidatedNetwork(context))
            val registered = manager != null &&
                runCatching { manager.registerDefaultNetworkCallback(callback) }.isSuccess
            awaitClose {
                if (registered) runCatching { manager?.unregisterNetworkCallback(callback) }
            }
        }.distinctUntilChanged()
    }
    return flow.collectAsState(initial = hasValidatedNetwork(context)).value
}
