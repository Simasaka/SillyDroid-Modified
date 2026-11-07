# SillyDroid Android（个人 Fork）

> **这是 [jialmaster/SillyDroid](https://github.com/jialmaster/SillyDroid) 的个人 Fork**，基线 `master` @ `725b5938ab`。
> 保留上游完整构建链与许可（**PolyForm Noncommercial 1.0.0**），在此之上做宿主适配、功能新增与性能优化。上游 APK 自更新已禁用，避免整包覆盖本 Fork 的宿主适配。

## 本 Fork 下载

- **完整 APK**（安装即用，已内置 `linux-arm64` 运行时与服务端资产）：见 [APK_DOWNLOAD.md](APK_DOWNLOAD.md) 或 [Release 页面](https://github.com/Simasaka/SillyDroid-Modified/releases)。
- 当前版本：`v1.0.1.1-tavern.1.19.0`（versionCode `1810001001`，debug 签名，与此前安装包签名一致，可覆盖安装保留全部数据）。
- 逐版本更新说明见 [CHANGELOG.md](CHANGELOG.md)。

## 个人版本改动更新

相对原版的主要改动如下。基础批次的逐条文件级明细见 [改动与修复明细](docs/CHANGES.md)，最近批次的版本说明见 [CHANGELOG.md](CHANGELOG.md)。

### 新增功能

- **酒馆版本更新 / 任意版本回退**：设置页就地替换 SillyTavern 源码，指定 tag 即可升降级，用户数据（角色卡、对话、配置）不动。
- **下载线路探测与 DNS 预解析**：自动优选 GitHub 原站或镜像，断网重连后可重新检测。
- **后台服务状态悬浮球**：用外部浏览器时单击看运行日志、双击回应用，不打断当前页面。
- **外部浏览器选择**：Firefox / 系统默认 / 每次询问，切换无需重启服务。
- **彻底退出**：停服务 + 关悬浮球 + 清通知 + 退出，避免后台残留。
- **后台运行权限引导**：电池优化豁免状态与厂商「应用启动管理」直达入口。
- **GeckoView 长按文字操作菜单**：复制 / 剪切 / 粘贴 / 全选 / 删除，内置页面与悬浮浏览器均可用。

### 问题修复

- **GeckoView 输入框跟随键盘**；关闭「界面密度」滑块、只允许字号缩放，消除输入栏横向溢出与破版。
- **后台模式切换**：关闭后台模式后内置浏览器自动恢复；换版本后自动刷新到新版页面。
- **更新链路可靠性**：修复装旧版本重启又变回内置版、已是最新仍重复下载、更新卡在「重启服务」、无 npm 环境更新必然失败、进度解析崩溃中断等问题。
- **更新事务与并发加固**：临时工作区隔离、异常退出清理现场、按提交标记回滚、维护操作互斥串行化、更新任务迁移到应用级协程作用域。
- **其他**：外部浏览器被系统默认劫持、后台模式悬浮球被误关、GeckoView 输入法重复弹出、线路检测后不再显示原始诊断 JSON。

### 性能优化（2026-11-08）

- **后台发热大幅改善**：悬浮浏览器切到后台时改为快照冻结停帧，后台 GPU 占用与功耗显著下降。
- **内存与进程精简**：关闭 fission 站点隔离、扩展并入父进程运行（单站点本地应用，实测减少约 360MB 内存）；debug 诊断日志仅在设置页「调试模式」开启时启用。
- **滚动性能模式**：页面滚动期间临时禁用毛玻璃 / 滤镜 / 阴影并暂停 CSS 动画，停止后自动恢复。仅作用于内置 GeckoView，**不影响外部浏览器**，对后续新增的美化脚本同样通用生效。（重度美化叠加的滚动天花板在内容侧，大幅提升建议用精简版美化脚本。）
- **GPU 合成余量**：MSAA 抗锯齿 4x → 2x，启动时尽力请求最高显示刷新率。

| 文档 | 内容 |
| --- | --- |
| [改动与修复明细](docs/CHANGES.md) | 基础批次相对原版新增了什么、修好了什么；逐条列出对应文件、原因与实测结果 |
| [CHANGELOG.md](CHANGELOG.md) | 逐版本更新说明（含最近的性能优化批次） |
| [APK_DOWNLOAD.md](APK_DOWNLOAD.md) | 完整 APK 下载链接、大小与 SHA-256 |
| [参考来源](docs/REFERENCES.md) | 代码基线、上游构建链约束与设计参考项目 |

> **署名说明**：本 Fork 全部新增功能（代码、脚本、界面文案与文档）均由 AI 助手依据使用需求生成、调试与整理，用户本人未编写代码；需求描述、真机验证与最终决策由用户完成。上游原有代码、构建链与许可归原作者所有。
>
> **隐私说明**：本仓库不含设备运行时数据，自定义白名单 IP、浏览器 localStorage、角色卡与聊天记录均未纳入版本控制。

> 以下为上游原始 README，未经删改。

---


[![APK Action](https://img.shields.io/github/actions/workflow/status/jialmaster/SillyDroid/sillydroid-upstream-apk.yml?label=SillyDroid%20APK)](https://github.com/jialmaster/SillyDroid/actions/workflows/sillydroid-upstream-apk.yml)
[![Runtime Release Tag](https://img.shields.io/badge/Runtime%20Release%20Tag-tavern--runtime--linux--arm64-2563eb)](https://github.com/jialmaster/SillyDroid/releases/tag/tavern-runtime-linux-arm64)
[![Latest Release](https://img.shields.io/github/v/release/jialmaster/SillyDroid?display_name=tag&label=SillyDroid%20Version)](https://github.com/jialmaster/SillyDroid/releases)
[![Release Time](https://img.shields.io/github/release-date/jialmaster/SillyDroid?label=SillyDroid%20Release%20Time)](https://github.com/jialmaster/SillyDroid/releases)
[![License: PolyForm Noncommercial 1.0.0](https://img.shields.io/badge/License-PolyForm%20Noncommercial%201.0.0-red)](LICENSE)

这个仓库维护 SillyDroid Android 宿主、离线运行时打包链，以及基于指定 SillyTavern tag 的 APK 构建与发布流程。

上游 SillyTavern 源码不会长期作为主工程保存在仓库里；构建时会同步指定 tag，先生成 stage 3 的不可变 server source（源码与 npm 运行依赖），再由 stage 4 把该包、dependency packs、宿主脚本和 patch assets 分别写入 APK assets。Stage 3 只受上游 Tavern tag 与上游 npm lockfile 控制，不承载宿主启动脚本、runtime patch 或 Android 侧启动策略。

README 保留常用说明；更细的构建语义、阶段边界和脚本职责，以 scripts 下对应脚本开头的 Contract 注释为准。

## 这是啥

- SillyDroid 是一个把 Linux 运行环境、SillyTavern 和 WebView 打包进单个 APK 的项目；不用额外安装 Termux，也不需要手动敲命令，下载安装后即可运行。
- 首次启动会自动解压运行环境，通常约 1 分钟，具体取决于机型和存储性能。
- 应用内置 WebView，对外部浏览器依赖较低，流式聊天和小窗场景更稳定。
- 项目当前大量实现由 AI 协助完成，作者主要按实际使用需求持续整理和迭代。

## 功能介绍

### 可选扩展

- 首次启动时会提示安装可选扩展，安装过程需要设备可访问 GitHub。
- 当前常用扩展包括：酒馆助手、小白 X、提示词模板。

### 内置安卓宿主扩展

- 实时日志：查看酒馆运行日志，排查问题时不需要再额外抓日志。
- 跳转宿主设置：一键打开 APP 原生设置页。
- 新消息通知：酒馆收到新消息后可发送通知栏提醒，部分机型上可能存在不稳定情况。
- 下拉刷新开关：可关闭 WebView 下拉刷新，避免与悬浮小手机、菜单图标等交互冲突。
- 刷新按钮：不使用下拉刷新时，可直接通过按钮手动刷新。

### 宿主设置页

- 基础设置：可视化配置 SillyTavern 基础选项，例如端口、接口等。
- 扩展管理：安装、删除扩展，并支持批量删除。
- 数据迁移：导入、导出完整酒馆数据，包括角色卡、对话、配置和扩展。

## 从 Termux 迁移数据

之前如果在 Termux 里跑 SillyTavern，可以按下面步骤一键迁移：

1. 在 Termux 中运行下面的命令，把数据打包成 ZIP：

```bash
curl -fsSL https://raw.githubusercontent.com/jialmaster/SillyDroid/master/scripts/export-tavern-data.sh | bash
```

> 脚本会自动尝试找到 SillyTavern 目录，打包后保存到手机下载目录；如果缺少 `zip` 命令，也会自动尝试安装。

2. 打开 SillyDroid，等待环境解压完成后，点击右上角进入导入入口。
3. 选择第一步生成的 ZIP 文件并确认导入。
4. APP 会自动替换数据并重启服务，迁移完成。

> 迁移内容包含配置、角色卡与对话、插件和扩展配置。

更细的数据包结构、导入识别规则和扩展目录映射说明见下方“进阶说明”。

## 下载

- Wiki：<https://github.com/jialmaster/SillyDroid/wiki>
- 官网下载页 / 最新状态：<https://jialmaster.github.io/SillyDroid/download>
- Release 列表 / 源码仓库：<https://github.com/jialmaster/SillyDroid/releases>
- 安装说明：下载 `.apk` 后直接安装即可。APK 为自签名包，安装时会出现系统安全提示；根据机型允许安装未知来源应用后即可正常使用。

## 碎碎念

- 当前主要按个人自用场景持续测试和修复问题，有问题欢迎反馈。
- Wiki 已补充基础使用说明；如果还有想要的功能，欢迎评论留言或提 issue。
- 如果项目对你有帮助，欢迎点个 star。

## 开发和构建说明

以下内容面向需要自行构建、排查打包链路或理解阶段边界的开发者。

## 构建状态说明

- APK Action 徽章会显示 GitHub Actions 当前状态：成功（success）、失败（failure）和进行中（in progress）。
- Runtime Release Tag 徽章固定展示当前 runtime 发布标签：`tavern-runtime-linux-arm64`。
- 若后续需要展示固定 runtime 版本号，建议先把版本字段写入可公开访问的 release 元数据，再接入徽章。

## 进阶说明

## 当前项目包含

1. `android-tavern/`
   Android 宿主工程，应用包名为 `com.jm.sillydroid`。
2. `scripts/build-tavern-android-local.sh`
   本地一键入口，顺序执行 rootfs、dependency packs、server source、apk 四个阶段。
3. `scripts/resolve-tavern-build-plan.sh`
   统一解析宿主版本、上游 tag、变更判定、release 命名和 `artifacts/releases/...` 输出路径。
4. `scripts/build-tavern-android-runtime-image.sh`
   生成 Tavern 专用 runtime image 及 metadata。
5. `scripts/sync-android-rootfs.sh`
   生成离线 Linux rootfs、Termux host native 入口及相关运行时资产。
6. `scripts/build-tavern-dependency-packs.sh`
   单独构建 `node`、`git` 等 dependency pack zip。
7. `scripts/sync-tavern-android-bootstrap.sh`
   下载指定 SillyTavern tag，安装 npm 运行依赖并生成 `server-source.zip`；不包含宿主启动脚本、runtime patch、dependency packs，也不直接产出 APK server assets。
8. `scripts/build-tavern-android-apk.sh`
   当前是阶段 4 脚本，只消费现有 runtime image、server source、dependency packs 组装 debug 或 release APK；不会重新安装 server source 的 npm 运行依赖。
9. `scripts/android-build-common.sh`
   Android/WSL 构建公共环境脚本。

## 前置要求

- Windows + WSL，或直接 Linux bash 环境。
- `bash`、`curl`、`unzip`、`tar`、`sha256sum`、`realpath`。
- 当前链路只支持 `linux-arm64`。
- Android SDK/JDK 不要求先手工装齐；构建脚本会按当前实现自动补齐缺失部分。

## 快速开始

建议在 WSL 或 Linux bash 环境里直接执行：

```bash
git clone https://github.com/jialmaster/SillyDroid.git
cd SillyDroid
bash ./scripts/build-tavern-android-local.sh
```

如果需要固定上游版本或构建类型，可以显式传参：

```bash
bash ./scripts/build-tavern-android-local.sh --tavern-tag 1.18.0 --build-type debug
```

## 补充说明

- 默认构建配置见 `sillydroid-build-config.json`。
- 当前本地入口脚本为 `scripts/build-tavern-android-local.sh`。
- 默认 APK 输出目录为 `artifacts/releases/android-apk/`。

## 交流

- QQ交流群：748515688

## 免责声明

- 本项目仅供学习、研究、技术验证与交流使用。
- 使用者应自行确认本项目及相关上游项目、扩展、模型、数据与网络访问行为符合当地法律法规、平台规则及许可证要求。
- 因部署、二次开发、插件安装、数据迁移、第三方服务接入或其他实际使用行为产生的风险与责任，由使用者自行承担。
- 本项目不对任何生产环境、商业用途、合规适配或特定业务结果作明示或默示保证。


