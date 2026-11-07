package com.jm.sillydroid.data.runtime

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本地 DNS 桥接服务。
 *
 * 为什么需要：
 *  部分国产 ROM（华为 EMUI / HarmonyOS）会限制 App 进程内 **native getaddrinfo**
 *  （走 netd 的 dnsproxyd socket），导致 Node 的 dns.lookup 恒 ENOTFOUND；
 *  但同一进程里 **Android Java 层 InetAddress 走 netd binder 是正常的**。
 *  于是出现「宿主能解析、Node 不能」���分叉。
 *
 * 做法：
 *  在 App 进程内开一个只监听 127.0.0.1 的极简 HTTP 服务，
 *  Node 侧的 DNS 补丁（node-dns-doh-shim.cjs）用普通 HTTP 请求来问它要 A 记录。
 *  走回环不涉及任何外部 DNS，也不受 ROM 的出站限制影响；解析由 Java 层完成，稳定可靠。
 *
 * 协议：
 *  - GET /dns?host=<hostname> → 200 {"host":"...","addresses":["1.2.3.4"],"error":""}
 *  - GET /map               → 200 {"map":"host=ip,host2=ip2","error":""}
 *    /map 返回宿主侧**定期刷新的**静态映射。Node 侧用它更新自己的静态表，
 *    这样即使 API 域名的 IP 变了（CDN 轮转、线路切换），Node 也能自动跟上，
 *    不必重启服务。
 */
class HostDnsBridgeServer(context: Context) {

    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var workerPool: java.util.concurrent.ExecutorService? = null

    /**
     * 宿主侧定期刷新的静态映射（host=ip,host2=ip2）。
     * 由 [HostApiDomainResolver] 的刷新任务写入。
     */
    @Volatile
    var staticMap: String = ""
        private set

    /** 更新静态映射并返回是否发生变化。 */
    fun updateStaticMap(value: String): Boolean {
        if (value == staticMap) {
            return false
        }
        staticMap = value
        Log.i(TAG, "static_map_updated map=$value")
        return true
    }

    /** 实际监听端口；未启动时为 0。 */
    @Volatile
    var port: Int = 0
        private set

    fun start(): Int {
        if (!running.compareAndSet(false, true)) {
            return port
        }
        return try {
            val socket = ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
            serverSocket = socket
            port = socket.localPort
            val pool = Executors.newFixedThreadPool(2) { runnable ->
                Thread(runnable, "sillydroid-dns-bridge").apply { isDaemon = true }
            }
            workerPool = pool
            Thread({ acceptLoop(socket) }, "sillydroid-dns-bridge-accept").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "dns_bridge_started port=$port")
            port
        } catch (exception: Exception) {
            running.set(false)
            Log.w(TAG, "dns_bridge_start_failed", exception)
            0
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) {
            return
        }
        runCatching { serverSocket?.close() }
        runCatching { workerPool?.shutdownNow() }
        serverSocket = null
        workerPool = null
        port = 0
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (exception: Exception) {
                if (running.get()) {
                    Log.w(TAG, "dns_bridge_accept_failed", exception)
                }
                return
            }
            workerPool?.execute { handle(client) }
        }
    }

    private fun handle(client: java.net.Socket) {
        runCatching {
            client.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            // 读完请求头
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) {
                    break
                }
            }

            val target = requestLine.split(" ").getOrNull(1) ?: "/"
            val query = target.substringAfter('?', "")
            val host = query.split("&")
                .firstOrNull { it.startsWith("host=") }
                ?.substringAfter("host=")
                ?.let { URLDecoder.decode(it, "UTF-8") }
                .orEmpty()

            val body = if (target.startsWith("/map")) {
                mapPayload()
            } else {
                resolvePayload(host)
            }
            val response = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: application/json; charset=utf-8\r\n")
                append("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n")
                append("Connection: close\r\n")
                append("\r\n")
                append(body)
            }
            client.getOutputStream().write(response.toByteArray(Charsets.UTF_8))
            client.getOutputStream().flush()
        }.onFailure { error ->
            Log.w(TAG, "dns_bridge_handle_failed", error)
        }
        runCatching { client.close() }
    }

    private fun resolvePayload(host: String): String {
        if (host.isBlank()) {
            return "{\"host\":\"\",\"addresses\":[],\"error\":\"empty_host\"}"
        }
        return try {
            val addresses = InetAddress.getAllByName(host)
                .filter { it.address.size == 4 }
                .map { it.hostAddress }
                .distinct()
            val error = if (addresses.isEmpty()) "no_a_record" else ""
            "{\"host\":\"${escape(host)}\",\"addresses\":[${addresses.joinToString(",") { "\"${escape(it)}\"" }}],\"error\":\"$error\"}"
        } catch (exception: Exception) {
            "{\"host\":\"${escape(host)}\",\"addresses\":[],\"error\":\"${escape(exception.javaClass.simpleName)}\"}"
        }
    }

    /** 返回全量静态映射，供 Node 侧刷新自己的静态表。 */
    private fun mapPayload(): String =
        "{\"map\":\"${escape(staticMap)}\",\"error\":\"\"}"

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

    companion object {
        private const val TAG = "SillyDroidDnsBridge"

        /**
         * 进程级单例：Node 侧的 DNS 补丁通过环境变量拿到端口，
         * 这里保证整个 App 生命周期内只有一个桥接服务在跑。
         */
        @Volatile
        private var instance: HostDnsBridgeServer? = null

        @Volatile
        private var currentPort: Int = 0

        /** 当前桥接服务端口；未启动时为 0。 */
        fun currentPort(): Int = currentPort

        /** 当前桥接服务实例；未启动时为 null。 */
        fun currentInstance(): HostDnsBridgeServer? = instance

        fun ensureStarted(context: Context): Int {
            val existing = instance
            if (existing != null && existing.port > 0) {
                currentPort = existing.port
                return existing.port
            }
            synchronized(this) {
                val current = instance
                if (current != null && current.port > 0) {
                    currentPort = current.port
                    return current.port
                }
                val created = HostDnsBridgeServer(context)
                val started = created.start()
                instance = created
                currentPort = started
                return started
            }
        }
    }
}
