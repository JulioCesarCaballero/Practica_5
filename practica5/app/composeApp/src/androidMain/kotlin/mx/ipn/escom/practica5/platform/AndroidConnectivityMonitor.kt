package mx.ipn.escom.practica5.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Observa la red por defecto en tiempo real (modo avión, Wi-Fi, datos). */
class AndroidConnectivityMonitor(context: Context) : ConnectivityMonitor {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val _isOnline = MutableStateFlow(currentlyOnline())
    override val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    // Se usa NET_CAPABILITY_INTERNET (y no VALIDATED) porque algunos emuladores detrás de proxy
    // nunca validan la red; los fallos reales de conexión se manejan igualmente como NetworkError.
    private fun NetworkCapabilities?.hasInternet(): Boolean =
        this != null && hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

    private fun currentlyOnline(): Boolean = cm.getNetworkCapabilities(cm.activeNetwork).hasInternet()

    init {
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                _isOnline.value = caps.hasInternet()
            }

            override fun onLost(network: Network) {
                // Con el callback de la red por defecto, si existe otra red llegará onCapabilitiesChanged.
                _isOnline.value = false
            }

            override fun onUnavailable() {
                _isOnline.value = false
            }
        })
    }
}
