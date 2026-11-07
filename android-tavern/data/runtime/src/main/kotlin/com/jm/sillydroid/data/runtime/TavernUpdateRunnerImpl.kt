package com.jm.sillydroid.data.runtime

import android.content.Context
import android.util.Log
import com.jm.sillydroid.domain.runtime.RuntimeLogManager
import com.jm.sillydroid.domain.tavernupdate.TavernNetworkProbe
import com.jm.sillydroid.domain.tavernupdate.TavernUpdateResult
import com.jm.sillydroid.domain.tavernupdate.TavernUpdateRunner
import com.jm.sillydroid.domain.tavernupdate.TavernVersionList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 在 Termux host runtime 内执行酒馆更新的实现。
 *
 * 两类动作共用 [LinuxRuntimeLauncher] 与同一份 host runtime 启动脚本：
 *  - [runUpdate] 从 APK 取出 [update-tavern.sh] 拉取 GitHub 指定版本并就地覆盖服务源码；
 *  - [reinstallDependencies] 仅对当前 SERVER_DIR 重新执行 npm install，用于修复依赖损坏。
 *
 * 调用方必须先 stopForSettingsAndAwait 停止服务，否则覆盖 SERVER_DIR 会与运行中的 Node 抢文件。
 */
class TavernUpdateRunnerImpl(
    context: Context,
    private val runtimeLogs: RuntimeLogManager,
    private val networkHealer: HostNetworkHealer? = null
) : TavernUpdateRunner {

    private val appContext = context.applicationContext

    override suspend fun runUpdate(
        targetRef: String?,
        mirror: String,
        onProgress: (percent: Int, message: String) -> Unit
    ): TavernUpdateResult = withContext(Dispatchers.IO) {
        val paths = HostPaths.from(appContext)
        val launcher = LinuxRuntimeLauncher(paths)
        AssetExtractor(appContext).prepareHostExtensionAssets(paths)

        val normalizedRef = targetRef?.trim()?.takeIf { it.isNotBlank() } ?: "latest"

        val maintenanceRoot = File(paths.serverDataDir, ".sillydroid-maintenance").also { it.mkdirs() }

        val updateScript = File(maintenanceRoot, "update-tavern.sh")
        appContext.assets.open("bootstrap/scripts/update-tavern.sh").use { input ->
            updateScript.outputStream().use { output -> input.copyTo(output) }
        }

        val environment = buildUpdateEnvironment(paths, updateScript, normalizedRef, mirror)
        val launchScriptText = buildHostRuntimeLaunchScript("UPDATE_SCRIPT" to updateScript.absolutePath)
        execute("tavern-update", launchScriptText, environment, onProgress, UPDATE_TIMEOUT_MINUTES)
    }

    override suspend fun reinstallDependencies(
        onProgress: (percent: Int, message: String) -> Unit
    ): TavernUpdateResult = withContext(Dispatchers.IO) {
        val paths = HostPaths.from(appContext)
        AssetExtractor(appContext).prepareHostExtensionAssets(paths)

        val maintenanceRoot = File(paths.serverDataDir, ".sillydroid-maintenance").also { it.mkdirs() }
        val reinstallScript = File(maintenanceRoot, "reinstall-deps.sh")
        reinstallScript.writeText(buildReinstallScript())

        val environment = buildUpdateEnvironment(paths, reinstallScript, "local", "auto")
        val launchScriptText = buildHostRuntimeLaunchScript(
            "REINSTALL_SCRIPT" to reinstallScript.absolutePath,
            "UPDATE_ACTION" to "reinstall"
        )
        execute("tavern-reinstall", launchScriptText, environment, onProgress, REINSTALL_TIMEOUT_MINUTES)
    }

    override suspend fun probeNetwork(): TavernNetworkProbe = withContext(Dispatchers.IO) {
        val paths = HostPaths.from(appContext)
        AssetExtractor(appContext).prepareHostExtensionAssets(paths)

        val maintenanceRoot = File(paths.serverDataDir, ".sillydroid-maintenance").also { it.mkdirs() }
        val updateScript = File(maintenanceRoot, "update-tavern.sh")
        appContext.assets.open("bootstrap/scripts/update-tavern.sh").use { input ->
            updateScript.outputStream().use { output -> input.copyTo(output) }
        }

        val infoFile = File(maintenanceRoot, "tavern-network-probe.json")
        infoFile.delete()

        // 探测前先刷新一次 DNS 通道：Wi-Fi 断连重连后 netd 给本 UID 的解析器可能已失效。
        val dnsMap = prepareDnsMap()
        val environment = buildUpdateEnvironment(paths, updateScript, "latest", "auto") +
            ("TAVERN_INFO_FILE" to infoFile.absolutePath) +
            ("TAVERN_DNS_MAP" to dnsMap)
        val launchScriptText = buildHostRuntimeLaunchScript(
            "UPDATE_SCRIPT" to updateScript.absolutePath,
            "UPDATE_ACTION" to "probe"
        )
        execute("tavern-probe", launchScriptText, environment, { _, _ -> }, PROBE_TIMEOUT_MINUTES)

        val payload = readTextOrNull(infoFile) ?: return@withContext TavernNetworkProbe(
            reachable = false,
            workingLine = "",
            workingUrl = "",
            latestTag = "",
            dnsResolved = dnsMap.isNotBlank(),
            nodeDiagnostic = ""
        )
        TavernNetworkProbe(
            reachable = extractJsonString(payload, "reachable") == "true",
            workingLine = extractJsonString(payload, "working_line"),
            workingUrl = extractJsonString(payload, "working_url"),
            latestTag = extractJsonString(payload, "latest_tag"),
            dnsResolved = dnsMap.isNotBlank(),
            nodeDiagnostic = extractJsonString(payload, "node_diag")
        )
    }

    override suspend fun listVersions(): TavernVersionList = withContext(Dispatchers.IO) {
        val paths = HostPaths.from(appContext)
        AssetExtractor(appContext).prepareHostExtensionAssets(paths)

        val maintenanceRoot = File(paths.serverDataDir, ".sillydroid-maintenance").also { it.mkdirs() }
        val updateScript = File(maintenanceRoot, "update-tavern.sh")
        appContext.assets.open("bootstrap/scripts/update-tavern.sh").use { input ->
            updateScript.outputStream().use { output -> input.copyTo(output) }
        }

        val infoFile = File(maintenanceRoot, "tavern-version-list.json")
        infoFile.delete()

        val dnsMap = prepareDnsMap()
        val environment = buildUpdateEnvironment(paths, updateScript, "latest", "auto") +
            ("TAVERN_INFO_FILE" to infoFile.absolutePath) +
            ("TAVERN_DNS_MAP" to dnsMap)
        val launchScriptText = buildHostRuntimeLaunchScript(
            "UPDATE_SCRIPT" to updateScript.absolutePath,
            "UPDATE_ACTION" to "list"
        )
        execute("tavern-list", launchScriptText, environment, { _, _ -> }, PROBE_TIMEOUT_MINUTES)

        val payload = readTextOrNull(infoFile) ?: return@withContext TavernVersionList(false, emptyList())
        // `]` 同样要转义：Android 的 ICU 正则不允许未配对的裸 `]`。
        val versions = runCatching {
            Regex("\"versions\"\\s*:\\s*\\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL)
                .find(payload)
                ?.groupValues?.getOrNull(1)
                ?.let { body ->
                    Regex("\"(.*?)\"").findAll(body)
                        .map { it.groupValues[1] }
                        .filter { it.isNotBlank() }
                        .toList()
                }
                .orEmpty()
        }.getOrDefault(emptyList())
        TavernVersionList(success = extractJsonString(payload, "ok") == "true", versions = versions)
    }

    override fun currentVersion(): String? {
        val paths = HostPaths.from(appContext)
        val packageJson = File(paths.serverDir, "package.json")
        if (!packageJson.exists()) return null
        val content = runCatching { packageJson.readText() }.getOrNull() ?: return null
        return Regex("\"version\"\\s*:\\s*\"(.*?)\"").find(content)?.groupValues?.getOrNull(1)?.trim()
    }

    private fun readTextOrNull(file: File): String? {
        if (!file.exists()) return null
        return runCatching { file.readText().trim().takeIf { it.isNotBlank() } }.getOrNull()
    }

    /** 从扁平 JSON 中取字符串字段值；布尔值也按 "true"/"false" 文本返回。 */
    private fun extractJsonString(payload: String, key: String): String {
        val match = Regex("\"$key\"\\s*:\\s*\"(.*?)\"").find(payload)
        if (match != null) return match.groupValues[1]
        val bare = Regex("\"$key\"\\s*:\\s*(true|false)").find(payload)
        return bare?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun buildUpdateEnvironment(
        paths: HostPaths,
        script: File,
        targetRef: String,
        mirror: String
    ): Map<String, String> {
        return mapOf(
            "APP_DATA_ROOT" to paths.serverDataDir.absolutePath,
            "UPDATE_SCRIPT" to script.absolutePath,
            "TAVERN_TARGET_REF" to targetRef,
            "TAVERN_MIRROR" to mirror,
            "TAVERN_PROGRESS_FILE" to File(paths.serverDataDir, ".sillydroid-maintenance/tavern-update.progress.json").absolutePath,
            // 宿主预解析的 IP 映射：绕开 App 进程内不可用的 DNS（国产 ROM 限制 UDP/53）。
            "TAVERN_DNS_MAP" to buildDnsMap()
        )
    }

    /**
     * 用宿主侧 InetAddress（走 netd DNS proxy）预先解析更新所需域名，
     * 拼成 "host=ip,host2=ip2" 交给脚本，curl 用 --resolve 直接连 IP。
     */
    private fun buildDnsMap(): String {
        val hosts = listOf(
            "api.github.com",
            "codeload.github.com",
            "github.com",
            "objects.githubusercontent.com",
            "ghproxy.com",
            "mirror.ghproxy.com",
            "gh.api.99988866.xyz",
            "registry.npmmirror.com"
        )
        return hosts.mapNotNull { host ->
            val ip = HostDnsResolver.resolve(appContext, host) ?: return@mapNotNull null
            "$host=$ip"
        }.joinToString(",")
    }

    /**
     * 准备 DNS 映射。
     *
     * 先刷新一次 netd 的 DNS 通道（Wi-Fi 断连重连后本 UID 的解析器会失效），
     * 再解析域名。解析不出来时返回空串，脚本会退回到自身的 DNS 逻辑。
     */
    private fun prepareDnsMap(): String {
        networkHealer?.refreshDnsChannel()
        val map = buildDnsMap()
        val resolvedCount = if (map.isBlank()) 0 else map.split(",").count { it.contains("=") }
        Log.i(TAG, "dns_map prepared resolved=$resolvedCount map=$map")
        return map
    }

    private fun execute(
        name: String,
        launchScriptText: String,
        environment: Map<String, String>,
        onProgress: (percent: Int, message: String) -> Unit,
        timeoutMinutes: Long
    ): TavernUpdateResult {
        val paths = HostPaths.from(appContext)
        val launcher = LinuxRuntimeLauncher(paths)
        val maintenanceRoot = File(paths.serverDataDir, ".sillydroid-maintenance").also { it.mkdirs() }

        val launchScript = File(maintenanceRoot, "$name.sh")
        launchScript.writeText(launchScriptText)

        val resolvedLogFileName = runtimeLogs.runtimeLogFileName(name)
        val logPath = File(paths.logsDir, resolvedLogFileName).absolutePath
        val progressFilePath = environment["TAVERN_PROGRESS_FILE"] ?: File(maintenanceRoot, "$name.progress.json").absolutePath
        val progressFile = File(progressFilePath)
        progressFile.delete()

        val launchRequest = LaunchRequest(
            name = name,
            scriptFile = launchScript,
            workingDirectory = paths.bootstrapRoot,
            environment = environment,
            logFileName = resolvedLogFileName
        )

        val process = launcher.start(launchRequest)
        var lastPayload: String? = null
        val timeoutAtNanos = System.nanoTime() + TimeUnit.MINUTES.toNanos(timeoutMinutes)
        var lastMessage = ""
        var skipped = false

        // 读一次进度文件并上报。
        // 脚本退出得很快时（例如判定「已是最新版本」直接返回），轮询循环可能一次都没轮到，
        // 所以循环结束后还必须再调一次，否则用户只会看到一条空白结果。
        fun consumeProgress() {
            val payload = readProgressPayload(progressFile) ?: return
            val (percent, message) = parseProgress(payload)
            lastMessage = message
            if (payload.contains("\"skipped\":true")) {
                skipped = true
            }
            if (payload != lastPayload) {
                lastPayload = payload
                onProgress(percent, message)
            }
        }

        try {
            while (!process.waitFor(PROGRESS_POLL_MS, TimeUnit.MILLISECONDS)) {
                consumeProgress()
                if (System.nanoTime() >= timeoutAtNanos) {
                    process.stop()
                    return TavernUpdateResult(
                        success = false,
                        message = "操作超时（超过 ${timeoutMinutes} 分钟），已终止。可重试或检查网络。",
                        logPath = logPath
                    )
                }
            }
            consumeProgress()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                return TavernUpdateResult(
                    success = false,
                    message = lastMessage.ifBlank { "更新脚本异常退出（exit=$exitCode）。" },
                    logPath = logPath
                )
            }
        } catch (exception: Exception) {
            if (process.isAlive()) {
                process.stop()
            }
            return TavernUpdateResult(
                success = false,
                message = exception.message ?: "更新过程中发生异常。",
                logPath = logPath
            )
        } finally {
            progressFile.delete()
        }

        return TavernUpdateResult(
            success = true,
            message = lastMessage.ifBlank { "操作完成。" },
            logPath = logPath,
            alreadyUpToDate = skipped
        )
    }

    private fun readProgressPayload(progressFile: File): String? {
        if (!progressFile.exists()) return null
        return runCatching {
            progressFile.readText().takeIf { payload -> payload.isNotBlank() }
        }.getOrNull()
    }

    private fun parseProgress(payload: String): Pair<Int, String> {
        // 进度解析纯属「显示」用途，**绝不能**因为解析失败就中断整个更新流程。
        // 之前这里直接 new Regex(...)，一旦抛异常就会被外层 catch 捕获并 stop 掉更新进程。
        val percent = runCatching {
            Regex("\"percent\"\\s*:\\s*(\\d+)").find(payload)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
        }.getOrNull() ?: 0

        val message = runCatching {
            // ⚠️ Android 的 java.util.regex 基于 ICU，比桌面 JDK 严格得多：
            // 未被 `{` 配对的 `}` 会直接抛 PatternSyntaxException，
            // 必须写成 `\\}`（同一个模式在桌面上是合法的，容易被本地测试放过）。
            Regex("\"message\"\\s*:\\s*\"(.*?)\"\\s*\\}\\s*$").find(payload)
                ?.groupValues?.getOrNull(1)?.trim()
        }.getOrNull().orEmpty()

        val safeMessage = message.ifBlank { fallbackMessage(payload) }
        return Pair(percent.coerceIn(0, 100), safeMessage)
    }

    /** 不依赖正则的兜底提取：按 "message" 标记切分，保证任何情况下都能拿到进度文案。 */
    private fun fallbackMessage(payload: String): String {
        val marker = "\"message\""
        val index = payload.lastIndexOf(marker)
        if (index < 0) {
            return ""
        }
        return payload.substring(index + marker.length)
            .trimStart()
            .removePrefix(":")
            .trimStart()
            .trimStart('"')
            .substringBeforeLast('"', "")
            .replace("\\n", " ")
            .take(300)
    }

    /**
     * 生成启动脚本：source 共享的 termux-host-runtime.sh 注入 node/npm/curl 到 PATH，
     * 然后 exec 目标脚本（UPDATE_SCRIPT 或 REINSTALL_SCRIPT）。
     *
     * [extraExports] 的值是**字面量**（路径 / 子命令名），直接写进脚本的赋值语句；
     * 不能写成 "${KEY:?...}" 那种引用形式——那些变量并不在进程环境里，
     * 结果是脚本在第一行就因 "KEY is required" 退出。
     */
    private fun buildHostRuntimeLaunchScript(vararg extraExports: Pair<String, String>): String {
        val exportLines = extraExports.joinToString("\n") { (key, value) ->
            "    $key=\"${value.replace("\"", "\\\"")}\""
        }
        val execTarget = extraExports.first().first
        return """
            #!/system/bin/sh
            set -eu

            BOOTSTRAP_ROOT="${'$'}{BOOTSTRAP_ROOT:?BOOTSTRAP_ROOT is required}"
            SERVER_DIR="${'$'}{SERVER_DIR:?SERVER_DIR is required}"
            APP_DATA_ROOT="${'$'}{APP_DATA_ROOT:?APP_DATA_ROOT is required}"
            LOGS_DIR="${'$'}{LOGS_DIR:?LOGS_DIR is required}"
            HOST_PREFIX_DIR="${'$'}{HOST_PREFIX_DIR:?HOST_PREFIX_DIR is required}"
            HOST_TMP_DIR="${'$'}{HOST_TMP_DIR:?HOST_TMP_DIR is required}"
            TERMUX_NODE_BIN="${'$'}{TERMUX_NODE_BIN:?TERMUX_NODE_BIN is required}"
            TERMUX_GIT_BIN="${'$'}{TERMUX_GIT_BIN:?TERMUX_GIT_BIN is required}"
            TERMUX_GIT_REMOTE_HTTP_BIN="${'$'}{TERMUX_GIT_REMOTE_HTTP_BIN:?TERMUX_GIT_REMOTE_HTTP_BIN is required}"
            TERMUX_CURL_BIN="${'$'}{TERMUX_CURL_BIN:?TERMUX_CURL_BIN is required}"
            TERMUX_SH_BIN="${'$'}{TERMUX_SH_BIN:?TERMUX_SH_BIN is required}"
            TERMUX_BASH_BIN="${'$'}{TERMUX_BASH_BIN:-}"
            HOST_NATIVE_LIB_DIR="${'$'}{HOST_NATIVE_LIB_DIR:?HOST_NATIVE_LIB_DIR is required}"
$exportLines

            . "${'$'}BOOTSTRAP_ROOT/scripts/termux-host-runtime.sh"

            assert_dir "${'$'}HOST_PREFIX_DIR" "缺少 host prefix 目录：${'$'}HOST_PREFIX_DIR"
            assert_dir "${'$'}SERVER_DIR" "缺少 Tavern 服务目录：${'$'}SERVER_DIR"
            assert_dir "${'$'}HOST_NATIVE_LIB_DIR" "缺少 host native lib 目录：${'$'}HOST_NATIVE_LIB_DIR"
            assert_file "${'$'}TERMUX_NODE_BIN" "缺少 Termux Node 入口：${'$'}TERMUX_NODE_BIN"
            assert_file "${'$'}TERMUX_GIT_BIN" "缺少 Termux Git 入口：${'$'}TERMUX_GIT_BIN"
            assert_file "${'$'}TERMUX_GIT_REMOTE_HTTP_BIN" "缺少 Termux Git HTTPS helper 入口：${'$'}TERMUX_GIT_REMOTE_HTTP_BIN"
            assert_file "${'$'}TERMUX_CURL_BIN" "缺少 Termux curl 入口：${'$'}TERMUX_CURL_BIN"
            assert_file "${'$'}TERMUX_SH_BIN" "缺少 Termux shell 入口：${'$'}TERMUX_SH_BIN"
            assert_file "${'$'}{$execTarget}" "缺少酒馆脚本：${'$'}{$execTarget}"
            mkdir -p "${'$'}APP_DATA_ROOT" "${'$'}LOGS_DIR" "${'$'}HOST_TMP_DIR"
            prepare_termux_host_runtime

            export HOME="${'$'}APP_DATA_ROOT/.termux-home"
            export GIT_TERMINAL_PROMPT=0
            mkdir -p "${'$'}HOME" "${'$'}TMPDIR"
            cd "${'$'}SERVER_DIR"
            exec "${'$'}TERMUX_SH_BIN" "${'$'}{$execTarget}" "${'$'}{UPDATE_ACTION:-update}"        """.trimIndent()
    }

    private fun buildReinstallScript(): String {
        return """
            #!/system/bin/sh
            set -e
            PROGRESS_FILE="${'$'}{TAVERN_PROGRESS_FILE:-/dev/null}"
            progress() {
                local pct="${'$'}1"; local msg="${'$'}2"
                if [ "${'$'}PROGRESS_FILE" != "/dev/null" ]; then
                    printf '{"percent":%s,"message":"%s"}\n' "${'$'}pct" "${'$'}msg" > "${'$'}PROGRESS_FILE" 2>/dev/null || true
                fi
                echo "[tavern-reinstall] (${'$'}pct%) ${'$'}msg"
            }
            cd "${'$'}{SERVER_DIR:?SERVER_DIR is required}"
            # 本构建的 APK 只内置 node，没有 npm；这里必须给出明确原因，
            # 否则用户只会看到一条语焉不详的 "npm install 失败"。
            if ! command -v npm >/dev/null 2>&1; then
                progress 40 "当前构建未内置 npm，无法重装依赖（依赖随 APK 一起提供）"
                exit 1
            fi
            progress 10 "开始重装依赖（npm install）…"
            if npm install --no-audit --no-fund --omit=dev >/dev/null 2>&1; then
                progress 90 "依赖重装完成"
            else
                progress 40 "尝试完整依赖安装…"
                if ! npm install --no-audit --no-fund >/dev/null 2>&1; then
                    progress 40 "npm install 失败"
                    exit 1
                fi
                progress 90 "依赖重装完成"
            fi
            progress 100 "依赖已重装完成"
            echo "[tavern-reinstall] done"
        """.trimIndent()
    }

    private companion object {
        const val TAG = "SillyDroidTavernUpdate"
        const val PROGRESS_POLL_MS = 250L
        const val UPDATE_TIMEOUT_MINUTES = 20L
        const val REINSTALL_TIMEOUT_MINUTES = 15L
        const val PROBE_TIMEOUT_MINUTES = 3L
    }
}
