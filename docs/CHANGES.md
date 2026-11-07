# 改动与修复明细

基线：[jialmaster/SillyDroid](https://github.com/jialmaster/SillyDroid) `master` @ `725b5938ab`。

本文件只回答一件事：**相对原版，新增了什么功能、修好了什么问题**。每一条都给出对应源码文件，可用 `git diff 725b593 HEAD -- <文件>` 逐项核对。

- 改动规模：34 个文件，`+3424 / -45` 行，上游完整历史与构建链保持不变
- 构建验证：`:app:assembleDebug` → **BUILD SUCCESSFUL**
- 真机验证：华为 ALN-AL00
- 许可沿用上游 **PolyForm Noncommercial 1.0.0**（根目录 `LICENSE`）

> **署名**：本 Fork 全部新增功能——Kotlin 代码、维护脚本、界面文案、布局与文档——均由 AI 助手依据使用需求生成、调试与整理，**用户本人未编写代码**；需求描述、真机验证与最终取舍由用户完成。上游原有代码、构建链与许可归原作者所有。
>
> **隐私**：仓库不含任何设备运行时数据；`config.yaml` 白名单 IP、浏览器 localStorage、角色卡与聊天记录均未纳入版本控制。

---

## 一、总览

| 编号 | 类别 | 名称 | 一句话 |
| --- | --- | --- | --- |
| N01 | 新增 | 酒馆版本更新 / 任意版本回退 | 设置页就地替换 SillyTavern 源码，指定 tag 即可升降级，数据不动 |
| N02 | 新增 | 下载线路探测与 DNS 预解析 | 自动优选 GitHub 原站或镜像，断网重连后可重新检测 |
| N03 | 新增 | 后台服务状态悬浮球 | 用外部浏览器时单击看日志、双击回应用，不打断当前页面 |
| N04 | 新增 | 彻底退出 | 停服务 + 关悬浮球 + 清通知 + 退出，避免后台残留 |
| N05 | 新增 | 外部浏览器选择 | Firefox / 系统默认 / 每次询问，切换无需重启服务 |
| N06 | 新增 | 后台运行权限引导 | 电池优化状态与厂商「应用启动管理」直达入口 |
| N07 | 新增 | 禁用上游 APK 自更新 | 防止上游整包覆盖本 Fork 的宿主适配 |
| F01 | 修复 | GeckoView 输入框跟随键盘 | 键盘弹起时输入框不再被盖住 |
| F02 | 修复 | GeckoView 只允许字号缩放 | 关闭「界面密度」滑块，消除输入栏横向溢出与破版 |
| F03 | 修复 | 浏览器渲染与滚动 | 开启离屏预光栅、去掉过度滚动，不强制硬件层导致文字发虚 |
| F04 | 修复 | 关闭后台模式后内置浏览器不恢复 | 表面类型变更时重建 Activity |
| F05 | 修复 | 换版本后页面仍显示旧版 | 服务就绪后自动刷新，无需手动下拉 |
| F06 | 修复 | 装了旧版本、重启又变回内置版本 | 回拷 5 个 APK 托管清单文件 |
| F07 | 修复 | 已是最新版本仍重复下载 | 下载前比对版本，直接返回"无需更新" |
| F08 | 修复 | 更新卡在「重启服务」/ 极慢 | 依赖目录改用 rename，不再触发 130MB 重解压 |
| F09 | 修复 | 无 npm 环境导致更新必然失败 | 先探测，缺失时复用已有依赖；失败自动回滚并清理包体 |
| F10 | 修复 | 进度解析崩溃中断更新 | Android ICU 正则转义 + 全量兜底 |
| F11 | 修复 | 外部浏览器被系统默认劫持 | 显式枚举候选并注入选择器 |
| F12 | 修复 | 后台模式悬浮球被误关 | 后台模式不再受「内置浏览器开关 / overlay 权限撤销」影响 |

---

## 二、新增功能

### N01 酒馆版本更新与任意版本回退

原版只在**构建期**由 `sync-tavern-android-bootstrap.sh` 把某个 SillyTavern tag 打进 APK，装好后版本固定。本 Fork 增加运行时更新能力：

| 层 | 文件 |
| --- | --- |
| domain 契约 | `domain/src/main/kotlin/.../domain/tavernupdate/TavernUpdateRunner.kt` |
| runtime 实现 | `data/runtime/src/main/kotlin/.../TavernUpdateRunnerImpl.kt` |
| 设备端脚本 | `app/src/main/assets/bootstrap/scripts/update-tavern.sh` |
| 依赖装配 | `app/.../AppGraph.kt`、`domain/app/SillyDroidAppGraph.kt` |
| 设置页 UI | `feature/settings/.../BootstrapSettingsActivity.kt`、`res/layout/activity_bootstrap_settings.xml`、`res/values/strings.xml` |

- 契约经 `SillyDroidAppGraph.tavernUpdateRunner` 暴露，`feature:settings` 不直接依赖 `data:runtime`。
- 设置页卡片：当前版本、目标版本输入（留空 = 最新 tag）、检查并更新、拉取版本列表点选回退、重新检测线路、仅重装依赖。
- 脚本子命令 `update` / `probe` / `list` / `local`；进度以 JSON 写回，宿主实时显示百分比。
- **数据边界**：只替换 `bootstrap/server`（服务源码）；`data/server`（用户数据）永不触碰；`config.yaml` 与 `default/config.yaml` 从备份保留。
- 更新前 `stopForSettingsAndAwait()` 停服务，成功或失败都会 `restart()`。

### N02 下载线路探测与宿主侧 DNS 预解析

- `mirror_wrap()` 依次尝试 GitHub 原站、`ghproxy.com`、`mirror.ghproxy.com`、`gh.api.99988866.xyz`；可用 `TAVERN_MIRROR` 指定。
- `HostDnsResolver.kt` 用 Java `InetAddress` 预解析 IPv4，`TavernUpdateRunnerImpl.buildDnsMap()` 生成 `TAVERN_DNS_MAP`，脚本转成 `curl --resolve` 绕过 DNS。
- `HostNetworkHealer.kt` 监听 `registerDefaultNetworkCallback` 并 `reportNetworkConnectivity()`，Wi-Fi 断连重连后刷新解析通道，对应「重新检测线路」按钮。
- `HostDnsBridgeServer.kt` / `HostApiDomainResolver.kt` 提供宿主侧域名诊断（用正则扫描，不解析 10MB 的 `settings.json`）。

> 这些只服务于**下载与探测**，不注入 Node 运行时。此前尝试过一整套 Node DNS 劫持方案，实测反而破坏正常 API 请求，已全部移除；`termux-host-runtime.sh`、`SillyDroidApplication.kt`、`HostRuntime.kt` 保持官方原版。

### N03 后台服务状态悬浮球

`feature/main/.../floatingbrowser/FloatingBrowserService.kt`

- 新增 `statusOnlyMode = !launchWebViewOnReady`：后台模式下只显示图标、不挂 WebView 容器（容器改用 `BROWSER_CONTAINER_TAG` 查找）。
- **单击** → 就地展开不透明日志浮层（读 `sillydroid-server-*` 尾部 200 行，1.5 秒刷新），不离开正在使用的外部浏览器；**双击**（`DOUBLE_TAP_WINDOW_MS = 320ms`）→ `restoreMainActivity()` 回到应用。
- `MainActivity.EXTRA_OPEN_LOGS` 让从状态球进入主界面时自动展开日志面板。

### N04 彻底退出

`MainActivity.performFullExit()` + `FloatingLogsController` / `FloatingLogsHost` / `activity_main.xml`（`floatingLogsFullExitButton`）

按「悬浮球 → Node 会话（`stopForSettingsAndAwait`）→ 显式 `stopService` 两个服务 → `NotificationManager.cancelAll()` → `finishAndRemoveTask()` → 结束进程」的顺序收干净，带二次确认。单纯 `finish()` 会让前台服务与常驻通知留在后台；服务未确认停止时不强杀进程，避免被 `START_STICKY` 重新拉起。

### N05 外部浏览器选择

新增 `core/ui/.../browser/ExternalBrowserLauncher.kt`，`ExternalBrowserMode` 为 `ASK / FIREFOX / SYSTEM_DEFAULT`，偏好存独立 `SharedPreferences`，设置页 `setupExternalBrowserRow()` 切换即时生效。四个 `BrowserHost`（`DisabledBrowserHost`、`TavernWebViewHost`、`TavernGeckoViewHost`、`UnavailableBrowserHost`）统一改调它；`AndroidManifest.xml` 增加 `<queries>` 满足 Android 11+ 包可见性。

### N06 后台运行权限引导

`BootstrapSettingsQuickActionsController.kt` 新增卡片：显示电池优化豁免状态、拉起 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`、跳转厂商「应用启动管理」页。华为 / 荣耀的「自动管理」会切断后台 node 子进程联网，前台服务与电池白名单都压不住，**这一步必须由用户手动放行一次**，卡片只做状态展示与跳转，不对抗系统策略。

### N07 禁用上游 APK 自更新

`ui/update/.../AppUpdateCoordinator.kt` 增加 `upstreamUpdateEnabled: Boolean = false`：`handleUpdateAction()`、`checkForUpdates()` 提前返回，关于页显示「上游自更新已禁用（请用设置页「酒馆版本更新」）」。本 Fork 改为就地更新 SillyTavern，必须阻止上游整包覆盖宿主适配。

---

## 三、问题修复

### F01 GeckoView 输入框跟随键盘

**现象**：GeckoView 引擎下唤起输入法，底部输入框被键盘完全盖住。

**原因**：GeckoView 不跟随 Android 的 IME insets——窗口已被 `adjustResize` 压缩，但 Gecko 仍按原高度排版，页面里的 `100vh` 和底部定位元素没有重算。

**修法**：`TavernGeckoViewHost.kt` 的 `init` 里注册 `ViewCompat.setOnApplyWindowInsetsListener`，取 `WindowInsetsCompat.Type.ime().bottom` 传给 `geckoView.setVerticalClipping(imeBottom)`，让 Gecko 重新计算可视区。

### F02 GeckoView 关闭界面密度缩放，只允许字号缩放

**现象**：把「界面密度」调到 100% 以下，酒馆输入栏横向溢出、整页文字发虚、键盘跟随失效。

**原因**：GeckoView 的界面密度靠改写 layout viewport 宽度实现，与系统 WebView 的缩放语义不同，非 100% 会破坏移动端布局。

**修法**（三处一致）：
- `TavernGeckoViewHost.currentBrowserPageZoomPercent()` 与 `setBrowserPageZoomPercent()` 恒定返回 / 应用 `BrowserZoomOptions.MAX_VIEWPORT_DENSITY_PERCENT`（100%），日志里保留 `ignoredHostPercent` 便于诊断；
- `FloatingLogsController.updateBrowserPageZoomControlState()` 在 `BrowserEngine.GECKOVIEW` 下禁用滑块并置灰，文案改为「GeckoView 固定 100%，请用字体缩放」；
- 缩放需求统一由 `fontSizeFactor`（字号缩放）承担，系统 WebView 下的密度滑块保持原样。

### F03 浏览器渲染与滚动

`HomeWebViewController.kt`：开启 `offscreenPreRaster`、设 `OVER_SCROLL_NEVER`。**不**调用 `setLayerType(LAYER_TYPE_HARDWARE)`——WebView 自行管理绘制层，强制硬件层会把整页先渲成纹理，缩放时线性采样让文字明显发虚并多吃一屏显存。

### F04 关闭后台模式后内置浏览器不恢复

`MainActivity.webViewSurfaceAtCreate` 在 `onCreate` 记录 `shouldUseWebViewSurface()`；`onStart` 发现设置里的「后台模式 ↔ 内置浏览器」已变化就 `recreate()`。`browserHost` 只在 `onCreate` 配置一次，不重建会永远停在占位状态。同时后台模式下也调用 `FloatingBrowserService.prepare()`，保证状态球可用。

### F05 换版本后页面仍显示更新前的旧页面

设置页成功后 `updateResultFlags(shouldReloadTavernUi = true)` → `SettingsNavigationContract.resultShouldReloadTavernUiKey` → `HomeViewModel.pendingTavernUiReload`；`BootstrapOverlayHost.render()` 在 `snapshot.isReady` **且浏览器表面可见**时才 `reloadTavernWebView()` 并清除待办。服务重启期间用户可能已经退回主界面，直接刷会被"表面不可见"丢掉。

> 未采用 `shouldForceFreshWebViewLoad`：它把 mask=0 当成 `fullMask`，会连 Cookie / 站点数据一起清掉。

### F06 装了旧版本、重启后又变回内置版本（核心）

**根因**：宿主启动时的 `inspectServerAssetRefreshPlan()` 会比对 `server-source-manifest.json`、`dependency-selection.json` 的 SHA-256，并检查 `bootstrap-manifest.json`、`dependency-env.sh`、`dependency-post-extract.sh` 是否存在。GitHub tag 压缩包**不含这 5 个文件**，替换后一律被判定 `MISSING`，于是把 APK 内置的版本重新解压覆盖回来。

**修法**：`update-tavern.sh` 收尾阶段用 `cp -pf` 从 `$BAK` 把 5 个文件拷回新目录；同时用备份覆盖 `default/config.yaml`（上游原版缺少 Android 补丁 `browserLaunch.enabled=false`、`git.backend=builtin`）。成功后删除 `server_old`。

**实测**：1.19.0 → 1.17.0，重启后仍是 1.17.0。

### F07 已是最新版本仍重复下载

下载**之前**先读 `package.json` 的 `version` 与目标 tag 比对；一致则 `progress_skip` 输出 `{"percent":100,"message":"...","skipped":true}` 并退出。Kotlin 侧映射成 `TavernUpdateResult.alreadyUpToDate`，设置页显示「当前已是 X，无需更新」，不再下载 38MB 包体、也不改动任何文件。

### F08 更新卡在「重启服务」/ 耗时过长

两个原因一起修：`node_modules` 从 `cp -a`（复制 100MB+，几十秒）改为 `mv`（同分区 rename，瞬时），`restore_old()` 回滚时先把依赖搬回备份再整体换回；加上 F06 不再触发 130MB 的资产重解压。**服务就绪从 32 秒降到 11 秒**。

### F09 无 npm 环境导致更新必然失败

APK 只内置 `libtermux-node.so`，**没有 npm**（server-fast 命令集里 npm/npx 被移除）。原流程无条件 `npm install` 必然 `not found`，把一次成功的下载解压白白回滚。现在先 `command -v npm`：有则先 `--omit=dev`、失败再完整安装；没有则沿用旧 `node_modules`，只有连旧依赖都没有时才回滚。`trap on_exit EXIT` 保证任何失败都自动换回旧版本，`cleanup_work()` 删除 tar.gz 与解压产物，npm 输出留档到日志目录。

### F10 进度解析崩溃中断更新

Android 的 ICU 正则比桌面 JDK 严格，裸 `}` / `]` 会抛 `PatternSyntaxException`，宿主解析进度时异常会连带 `process.stop()` 把更新打断。`TavernUpdateRunnerImpl.parseProgress()` 现在转义 `\}`，整体包 `runCatching`，并提供不依赖正则的 `fallbackMessage()`；版本列表正则的 `]` 同样处理。

### F11 外部浏览器被系统默认劫持

厂商 ROM 会把只有一个隐式候选的 `createChooser` 折叠成默认应用跳转，导致「每次询问」形同虚设、选不到 Firefox。`ExternalBrowserLauncher` 用 `queryIntentActivities` 枚举候选并做成显式 component intent，Firefox 置顶，其余通过 `EXTRA_INITIAL_INTENTS` 注入选择器；`FIREFOX` 模式用 `setPackage` 绕过系统默认解析。

### F12 后台模式悬浮球被误关

`SillyDroidApplication` 原先要求 `launchWebViewOnReady && floatingBrowserEnabled` 同时为真才允许悬浮球，而后台模式下这个开关是被禁用的；overlay 权限被撤销时还会顺手把用户的 `floatingBrowserEnabled` 写成 false。现在后台模式独立保留状态球，且不再改写用户的内置浏览器开关。

---

## 四、实测结果

| 场景 | 结果 |
| --- | --- |
| 降级 1.19.0 → 1.17.0 并重启 | 重启后仍是 1.17.0，服务 11 秒就绪 |
| 已是最新版本点「检查并更新」 | 返回 `skipped:true`，不下载、不改文件 |
| 更新完成后进入酒馆 | 自动刷新到新版本页面 |
| 关闭后台模式 | 内置浏览器自动恢复 |
| 后台模式单击 / 双击悬浮球 | 单击出日志浮层，双击回应用 |
| GV 引擎唤起键盘 / 调整缩放 | 输入框跟随键盘，界面密度固定 100%，字号缩放可用 |
| 构建 | `:app:assembleDebug` BUILD SUCCESSFUL |

---

## 五、已知限制（设计取舍）

| 限制 | 说明 |
| --- | --- |
| APK 不含 npm | 升降级沿用旧版 `node_modules`；若目标版本依赖不兼容起不来，重新选一个版本安装即可恢复。脚本无法预判安装后能否启动 |
| 界面密度在 GV 下不可调 | 这是 F02 的主动取舍，缩放请用「字体缩放」 |
| 后台模式仍需手动放行 | 国产 ROM 的后台策略无法由应用绕过，见 N06 |
| 上游 APK 自更新已关闭 | 容器功能升级需自行编译安装，见 N07 |
| 扩展的更新能力依赖 `.git` | 按文件导入的扩展没有 git 元数据，酒馆自带「检测更新」会失败；与酒馆版本更新功能无关 |
