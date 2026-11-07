package com.jm.sillydroid.data.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import java.net.InetAddress

/**
 * 宿主侧 DNS 解析辅助。
 *
 * 某些国产 ROM（华为 EMUI / HarmonyOS）会限制普通 App UID 的 UDP/53 与裸 TCP 出站，
 * 导致 Termux 前缀里的 curl / Node（自带 c-ares 解析器）全部拿不到 IP，
 * 表现为 `Could not resolve host` / `getaddrinfo ENOTFOUND`。
 *
 * Android 自身提供了 [InetAddress]，它在 App 进程内走 netd 的 DNS proxy（unix socket），
 * 不受上述限制。更新脚本因此改为：先用本类解析出 IP，再把 IP 交给 curl 的 `--resolve`，
 * 彻底绕开脚本内的 DNS 查询。
 */
object HostDnsResolver {

    /** 解析失败时返回 null。 */
    fun resolve(context: Context, host: String): String? {
        return try {
            InetAddress.getAllByName(host)
                .firstOrNull { it.address.size == 4 }
                ?.hostAddress
                ?.takeIf { it.isNotBlank() }
        } catch (exception: Exception) {
            null
        }
    }

    /**
     * 解析出 [host] 对应的多个 A 记录，按顺序返回。
     * 某些 CDN 对单一 IP 的线路质量不佳，多 IP 重试可提高成功率。
     */
    fun resolveAll(context: Context, host: String): List<String> {
        return try {
            InetAddress.getAllByName(host)
                .filter { it.address.size == 4 }
                .map { it.hostAddress }
                .distinct()
        } catch (exception: Exception) {
            emptyList()
        }
    }

    /**
     * 读取当前默认网络的 DNS 服务器列表（来自 ConnectivityManager）。
     * 用于在脚本无法解析时，提示用户可用的备用 DNS。
     */
    fun systemDnsServers(context: Context): List<String> {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val activeNetwork = connectivityManager.activeNetwork ?: return emptyList()
        val linkProperties: LinkProperties = connectivityManager.getLinkProperties(activeNetwork) ?: return emptyList()
        return linkProperties.dnsServers
            .mapNotNull { it.hostAddress }
            .distinct()
    }

    /**
     * 诊断信息：宿主 DNS 解析是否可用、解析到了什么。
     * 写入运行时日志，方便排查「App 能联网但脚本不能」这类问题。
     */
    fun diagnostics(context: Context): String {
        val hosts = listOf("api.github.com", "registry.npmmirror.com")
        val resolved = hosts.map { host ->
            val ip = resolve(context, host)
            "$host=${ip ?: "FAIL"}"
        }.joinToString(" ")
        val systemDns = systemDnsServers(context).joinToString(",").ifEmpty { "none" }
        return "host_dns_diag resolve[$resolved] systemDns[$systemDns]"
    }
}
