package com.jm.sillydroid.data.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log

/**
 * 网络切换自愈。
 *
 * 国产 ROM（华为 EMUI / HarmonyOS）在 Wi-Fi 断开重连后，App 进程通过 netd DNS proxy
 * 拿到的解析器会失效：表现为 `getaddrinfo ENOTFOUND`（连国内域名也一样），
 * 而手机浏览器 / shell 用同一网络却完全正常。
 *
 * 典型触发场景：应用长时间运行期间 Wi-Fi 断连重连（换房间、路由器重启、切 5G/2.4G），
 * netd 给 App 的 DNS 通道没有跟着重建，进程就一直解析不了域名。
 *
 * 这里监听默认网络的可用/丢失事件：
 *  - 网络「重新可用」时主动刷新一次 DNS（[ConnectivityManager.reportNetworkConnectivity] +
 *    重新读取 LinkProperties），让 netd 重建该 UID 的 DNS 通道；
 *  - 同时提供一个显式的 [refreshDnsChannel] 供「重新检测线路」按钮调用，
 *    万一仍解析失败，用户点一下就能恢复，不用重启 App。
 */
class HostNetworkHealer(private val context: Context) {

    private val connectivityManager: ConnectivityManager? =
        context.getSystemService(ConnectivityManager::class.java)

    private var callback: ConnectivityManager.NetworkCallback? = null

    /**
     * 注册默认网络监听。网络重新可用时自动做一次 DNS 刷新。
     * 幂等：重复调用只会注册一次。
     */
    fun start() {
        if (callback != null) return
        val manager = connectivityManager ?: return
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "network_available net=$network")
                refreshDnsChannel(network)
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "network_lost net=$network")
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                // DNS 服务器变更时同样刷新一次，确保 App 用上新的解析器。
                val servers = linkProperties.dnsServers
                    .mapNotNull { it.hostAddress }
                    .joinToString(",")
                Log.i(TAG, "network_link_properties net=$network dns=$servers")
                refreshDnsChannel(network)
            }
        }
        try {
            manager.registerDefaultNetworkCallback(networkCallback)
            callback = networkCallback
        } catch (exception: Exception) {
            Log.w(TAG, "register_default_network_callback_failed", exception)
        }
    }

    fun stop() {
        val networkCallback = callback ?: return
        callback = null
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (exception: Exception) {
            Log.w(TAG, "unregister_default_network_callback_failed", exception)
        }
    }

    /**
     * 刷新指定网络的 DNS 通道。
     *
     * 做法是让 netd 重新评估该网络的可达性，并重新读取链路属性；
     * 对华为这类「DNS 通道卡死」的情况通常能恢复解析能力。
     */
    fun refreshDnsChannel(network: Network? = null) {
        val manager = connectivityManager ?: return
        try {
            val target = network ?: manager.activeNetwork ?: return
            // 报告网络可达，触发 netd 重新校验并重建该 UID 的 DNS 通道。
            manager.reportNetworkConnectivity(target, true)
        } catch (exception: Exception) {
            Log.w(TAG, "report_network_connectivity_failed", exception)
        }
        try {
            // 重新读取链路属性，触发 netd 下发新的 DNS 配置。
            val activeNetwork = manager.activeNetwork ?: return
            val linkProperties = manager.getLinkProperties(activeNetwork) ?: return
            val servers = linkProperties.dnsServers.mapNotNull { it.hostAddress }
            Log.i(TAG, "dns_refresh_servers=$servers")
        } catch (exception: Exception) {
            Log.w(TAG, "read_link_properties_failed", exception)
        }
    }

    /**
     * 当前网络是否具备 INTERNET 能力。
     */
    fun hasInternet(): Boolean {
        val manager = connectivityManager ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private companion object {
        const val TAG = "SillyDroidNetwork"
    }
}
