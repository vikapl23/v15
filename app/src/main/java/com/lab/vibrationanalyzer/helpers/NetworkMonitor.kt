package com.lab.vibrationanalyzer.helpers

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** ЛР3. «Чи є інтернет?» — у вигляді StateFlow, щоб на зміну можна було реагувати. */
interface INetworkMonitor {
    val isOnline: StateFlow<Boolean>
}

/**
 * Реалізація на ConnectivityManager (Android API).
 * Android сам викликає NetworkCallback, коли мережа з'являється чи зникає
 * (наприклад, увімкнули режим польоту в емуляторі).
 */
class NetworkMonitor(context: Context) : INetworkMonitor {

    private val manager = context.getSystemService(ConnectivityManager::class.java)

    private val _isOnline = MutableStateFlow(checkNow())
    override val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    init {
        // Monitor живе весь час роботи додатка (створюється в SmartVibrationApp),
        // тому callback не знімаємо.
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                update(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            }

            override fun onLost(network: Network) = update(false)
            override fun onUnavailable() = update(false)
        })
    }

    private fun update(online: Boolean) {
        if (_isOnline.value != online) Log.d(TAG, "мережа: ${if (online) "є" else "немає"}")
        _isOnline.value = online
    }

    private fun checkNow(): Boolean {
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private companion object {
        const val TAG = "NETWORK"
    }
}
