package com.jm.sillydroid.data.runtime

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 宿主侧 API 域名预解析。
 *
 * 背景：
 *  部分国产 ROM（华为 EMUI / HarmonyOS）会限制 App 进程内 native getaddrinfo，
 *  Node 的 dns.lookup 因此恒 ENOTFOUND；宿主 Java 层的 `InetAddress`（走 netd binder）却正常。
 *
 * 做法：
 *  酒馆的 API 域名写在 settings.json 的 `custom_url` / `api_url` / `reverse_proxy` 里，
 *  这里用**正则**（不是 JSON 解析——settings.json 有 10MB，解析会拖垮进程）扫出这些域名，
 *  用 Java 层解析成 IP，注入 `SILLYDROID_DNS_STATIC_MAP` 交给 Node 侧 DNS 补丁。
 *
 *  Node 侧把它作为**初始缓存**：业务请求第一次查域名就能同步命中，
 *  避免异步解析期间 Node 的 socket 超时定时器先触发（那会表现为 connect ETIMEDOUT）。
 */
object HostApiDomainResolver {

    private const val TAG = "SillyDroidApiDomain"

    private val URL_PATTERN = Regex("\"(?:custom_url|api_url|reverse_proxy|api_url_custom)\"\\s*:\\s*\"(https?://[^\"]+)\"")
    private val HOST_PATTERN = Regex("https?://([^/:\"]+)")

    /** 从酒馆配置里提取所有 API 域名。 */
    fun apiDomains(paths: HostPaths): List<String> {
        val domains = LinkedHashSet<String>()
        listOf(
            File(paths.serverDataDir, "data/default-user/settings.json"),
            File(paths.serverDataDir, "data/default-user/secrets.json")
        ).forEach { file ->
            if (!file.exists()) {
                return@forEach
            }
            val content = runCatching { file.readText() }.getOrNull() ?: return@forEach
            URL_PATTERN.findAll(content).forEach { match ->
                val host = HOST_PATTERN.find(match.groupValues[1])?.groupValues?.getOrNull(1)
                if (!host.isNullOrBlank()) {
                    domains.add(host)
                }
            }
        }
        return domains.toList()
    }

    /**
     * 生成 `SILLYDROID_DNS_STATIC_MAP` 的值，形如 "api.example.com=1.2.3.4"。
     * 解析失败的域名直接跳过，交给运行时其它途径兜底。
     */
    fun buildStaticMap(context: Context, paths: HostPaths): String {
        val domains = apiDomains(paths)
        if (domains.isEmpty()) {
            return ""
        }
        val pairs = domains.mapNotNull { domain ->
            val address = HostDnsResolver.resolve(context, domain) ?: return@mapNotNull null
            "$domain=$address"
        }
        val map = pairs.joinToString(",")
        Log.i(TAG, "static_dns_map resolved=${pairs.size}/${domains.size} map=$map")
        return map
    }

    /**
     * 启动静态映射的定期刷新。
     *
     * 为什么要定期刷新：
     *  - 静态映射是 Node 侧**唯一能同步返回**的解析来源，一旦过期，
     *    业务请求就会走异步解析路径，而 Node 的 socket 超时定时器
     *    会在解析完成前触发，表现为「解析成功但 connect ETIMEDOUT」。
     *  - API 服务商的 IP 可能变化（CDN 轮转、线路切换），
     *    定期重解析能让 Node 侧自动跟上，不必重启服务。
     */
    fun startRefreshLoop(context: Context, paths: HostPaths) {
        Thread({
            // 首次延迟一点，避免和 App 启动期的其它初始化抢 CPU。
            Thread.sleep(30_000L)
            while (true) {
                try {
                    val map = buildStaticMap(context, paths)
                    if (map.isNotBlank()) {
                        HostDnsBridgeServer.ensureStarted(context)
                        HostDnsBridgeServer.currentInstance()?.updateStaticMap(map)
                    }
                } catch (exception: Exception) {
                    Log.w(TAG, "static_map_refresh_failed", exception)
                }
                try {
                    Thread.sleep(REFRESH_INTERVAL_MS)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
            }
        }, "sillydroid-api-domain-refresh").apply {
            isDaemon = true
            start()
        }
    }

    private const val REFRESH_INTERVAL_MS = 5 * 60 * 1000L
}
