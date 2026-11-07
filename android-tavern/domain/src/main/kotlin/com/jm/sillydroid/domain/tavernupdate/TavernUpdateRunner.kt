package com.jm.sillydroid.domain.tavernupdate

/**
 * 在 App 运行时内更新 / 回退 SillyTavern 服务源码的契约。
 *
 * 实现（data:runtime 模块）会把 APK 内置的 [update-tavern.sh] 放到运行时目录，
 * 复用 Termux host runtime（node / npm / curl）拉取 GitHub 仓库的指定版本并就地覆盖
 * 服务源码目录，过程中保留用户数据目录与 config.yaml。
 *
 * 该接口放在 domain 层，是为了让 feature:settings 在不直接依赖 data:runtime 的前提下
 * 也能触发更新；具体实现由 AppGraph 在 app 模块装配。
 */
interface TavernUpdateRunner {

    /**
     * 执行一次酒馆版本更新。
     *
     * @param targetRef 目标版本：tag / 分支名；为空或 "latest" 表示拉取最新 release。
     * @param mirror    镜像策略：auto（默认，依次尝试 GitHub 与国内镜像）/ github / 自定义镜像前缀。
     * @param onProgress 进度回调，[percent] 为 0..100 的整数，[message] 为可读状态。
     * @return 更新结果，含是否成功、最终消息与日志路径。
     */
    suspend fun runUpdate(
        targetRef: String?,
        mirror: String = "auto",
        onProgress: (percent: Int, message: String) -> Unit
    ): TavernUpdateResult

    /**
     * 仅对当前服务源码目录重新执行 npm install，用于修复依赖损坏。
     * 不会下载或覆盖源码，因此 [targetRef] 不适用。
     */
    suspend fun reinstallDependencies(
        onProgress: (percent: Int, message: String) -> Unit
    ): TavernUpdateResult

    /**
     * 探测当前网络下可用的下载线路（GitHub 原站 / 国内镜像），用于在设置页展示实际走的线路。
     * 该操作只发网络请求，不触碰服务目录，因此无需停止服务。
     */
    suspend fun probeNetwork(): TavernNetworkProbe

    /**
     * 拉取最近若干个可安装版本，供用户选择回退目标。只读网络，不触碰服务目录。
     */
    suspend fun listVersions(): TavernVersionList

    /**
     * 读取当前已安装的版本号（取自服务端 package.json 的 version 字段）。
     * 纯本地文件读取，不涉及网络，也不会停止服务。
     */
    fun currentVersion(): String?
}

/**
 * 网络线路探测结果。
 *
 * @param reachable 是否探测到可用线路
 * @param workingLine 可用线路名：github / ghproxy / mirror.ghproxy / gh.api.99988866
 * @param workingUrl 该线路实际使用的 URL
 * @param latestTag 该线路上看到的最新版本号（可能为空）
 * @param dnsResolved 宿主侧 DNS 是否成功解析出 IP。false 表示宿主 DNS 也不可用，
 *                    此时大概率是网络切换后 netd 的 DNS 通道卡死，需要重启 App 或刷新网络。
 * @param nodeDiagnostic Node 侧 DNS 能力诊断结果（原始 JSON 文本），用于排查
 *                       「宿主能解析、但酒馆服务里的 Node 解析不了」这类分叉问题。
 */
data class TavernNetworkProbe(
    val reachable: Boolean,
    val workingLine: String,
    val workingUrl: String,
    val latestTag: String,
    val dnsResolved: Boolean = true,
    val nodeDiagnostic: String = ""
)

/**
 * 可安装版本列表。
 *
 * @param success 是否成功拉到列表
 * @param versions 版本号列表，按新到旧排列
 */
data class TavernVersionList(
    val success: Boolean,
    val versions: List<String>
)

data class TavernUpdateResult(
    val success: Boolean,
    val message: String,
    val logPath: String? = null,
    /**
     * true 表示脚本判断「目标版本与当前已安装版本一致」，
     * 因此没有下载、也没有改动任何文件。UI 应显示「已是最新版本」而不是「更新成功」。
     */
    val alreadyUpToDate: Boolean = false
)
