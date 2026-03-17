package ru.foric27.cluster

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log

internal data class StreamNetworkPreparation(
    val bindIp: String?,
    val network: Network?,
    val ifacePresent: Boolean,
    val rootRequired: Boolean,
    val ifaceName: String?,
)

internal class StreamNetworkCoordinator(
    private val context: Context,
    private val logTag: String,
    private val onNetworkAvailable: () -> Unit,
    private val onNetworkLost: (Network) -> Unit,
) {

    private var boundNetwork: Network? = null
    private var lastSeenEthNetwork: Network? = null
    private var linkCallback: ConnectivityManager.NetworkCallback? = null

    fun prepareNetwork(cfg: StreamConfig): StreamNetworkPreparation {
        RootNetUtil.logSelectedIface(logTag, "Подготовка сети")
        if (!cfg.useRootNet) {
            ensureEthCallbackRegistered()
            val network = lastSeenEthNetwork ?: findEthernetNetwork()
            val bindIp = cfg.bindIp
                ?.takeIf { it.isNotBlank() }
                ?.takeUnless { it == RuntimeConfig.Network.BIND_IP }
            if (network != null) {
                bindProcessToNetworkBestEffort(network)
            } else {
                Log.i(logTag, "Root-сеть отключена; ethernet Network не найден, продолжаю без привязки к интерфейсу")
            }
            if (bindIp == null && !cfg.bindIp.isNullOrBlank()) {
                Log.i(logTag, "Root-сеть отключена; статический bindIp ${cfg.bindIp} пропускаю")
            }
            return StreamNetworkPreparation(bindIp, network, network != null, false, null)
        }

        ensureEthCallbackRegistered()
        val localCidr = cfg.localCidr?.takeIf { it.isNotBlank() } ?: DEF_USB_LOCAL_CIDR
        val gateway = cfg.gateway?.takeIf { it.isNotBlank() } ?: DEF_USB_GATEWAY
        val applyResult = RootNetUtil.applyStaticIfaceNetwork(localCidr, gateway)
        if (applyResult.rootRequired) {
            Log.w(logTag, "Нет доступа к su/root — сетевой root-режим недоступен")
            return StreamNetworkPreparation(null, null, true, true, applyResult.iface)
        }

        val defaultBindIp = ipFromCidr(localCidr)
        var bindIp = cfg.bindIp?.takeIf { it.isNotBlank() } ?: defaultBindIp
        if (!applyResult.ok && (cfg.bindIp.isNullOrBlank() || cfg.bindIp == defaultBindIp)) {
            bindIp = null
        }

        val probeState = RootNetUtil.getIfaceProbeState()
        if (probeState.rootRequired) {
            Log.w(logTag, "Нет доступа к su/root — проверка ${probeState.iface} недоступна")
            return StreamNetworkPreparation(null, null, true, true, probeState.iface)
        }

        val ifacePresent = probeState.exists
        if (!applyResult.ok) {
            if (ifacePresent) {
                Log.w(logTag, "Не удалось применить статический IP для ${applyResult.iface} (продолжаю): ${applyResult.details}")
            } else {
                Log.i(logTag, "${probeState.iface} отсутствует — статическая настройка сети пропущена")
            }
        }

        var network: Network? = null
        if (ifacePresent) {
            network = lastSeenEthNetwork ?: findEthernetNetwork()
            if (network != null) {
                bindProcessToNetworkBestEffort(network)
            } else {
                Log.i(logTag, "ConnectivityManager не дал ethernet Network; продолжаю по bindIp и route")
            }
        } else {
            Log.i(logTag, "${probeState.iface} отсутствует на устройстве — биндинг к ethernet Network пропущен")
        }

        return StreamNetworkPreparation(bindIp, network, ifacePresent, false, probeState.iface)
    }

    fun unbindProcessNetworkBestEffort() {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= 23) {
                connectivityManager.bindProcessToNetwork(null)
            } else {
                @Suppress("DEPRECATION")
                ConnectivityManager.setProcessDefaultNetwork(null)
            }
        } catch (t: Throwable) {
            Log.w(logTag, "Не удалось снять привязку процесса к Network", t)
        }
        boundNetwork = null
    }

    fun unregisterEthCallbackBestEffort() {
        val callback = linkCallback ?: return
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            connectivityManager.unregisterNetworkCallback(callback)
        } catch (t: Throwable) {
            Log.w(logTag, "Не удалось снять NetworkCallback", t)
        } finally {
            linkCallback = null
            lastSeenEthNetwork = null
        }
    }

    private fun ensureEthCallbackRegistered() {
        if (linkCallback != null) return

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                lastSeenEthNetwork = network
                onNetworkAvailable()
            }

            override fun onLost(network: Network) {
                if (lastSeenEthNetwork == network) {
                    lastSeenEthNetwork = null
                }
                onNetworkLost(network)
            }
        }

        try {
            connectivityManager.registerNetworkCallback(request, callback)
            linkCallback = callback
        } catch (t: Throwable) {
            Log.w(logTag, "Не удалось зарегистрировать NetworkCallback", t)
            linkCallback = null
        }
    }

    private fun findEthernetNetwork(): Network? {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networks = connectivityManager.allNetworks
        for (network in networks) {
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: continue
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                lastSeenEthNetwork = network
                return network
            }
        }
        return null
    }

    private fun bindProcessToNetworkBestEffort(network: Network) {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= 23) {
                connectivityManager.bindProcessToNetwork(network)
            } else {
                @Suppress("DEPRECATION")
                ConnectivityManager.setProcessDefaultNetwork(network)
            }
            boundNetwork = network
            Log.i(logTag, "Процесс привязан к Network=$network")
        } catch (t: Throwable) {
            Log.w(logTag, "Не удалось привязать процесс к Network (продолжаю без привязки): $t")
        }
    }

    private fun ipFromCidr(cidr: String): String? {
        return try {
            cidr.substringBefore('/').trim().takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            Log.w(logTag, "Не удалось извлечь IP из CIDR: $cidr", t)
            null
        }
    }

    private companion object {
        private const val DEF_USB_LOCAL_CIDR = "192.168.40.1/24"
        private const val DEF_USB_GATEWAY = "192.168.40.2"
    }
}
