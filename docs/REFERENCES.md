# 参考来源

本 Fork 的实现参照了以下项目与资料。许可沿用上游 **PolyForm Noncommercial 1.0.0**，本仓库仅为个人自用整理，非官方分支。

---

## 一、代码基线

| 来源 | 用途 |
| --- | --- |
| [jialmaster/SillyDroid](https://github.com/jialmaster/SillyDroid) `master` @ `725b5938ab` | 本 Fork 的代码基线，上游完整历史保留在 commit 链中 |
| 上游分支 `sillydroid-v1.0.1.68-1.19.0-release` | 命名规则：宿主版本 + 对应的 SillyTavern tag |
| 上游 `scripts/sync-tavern-android-bootstrap.sh` | **构建期**下载指定 tag，生成 stage 3 不可变 server source（含 npm 依赖），stage 4 打进 APK assets |
| [SillyTavern/SillyTavern](https://github.com/SillyTavern/SillyTavern) | 版本更新功能的 tag / tarball 来源 |

原版仓库**没有运行时更新酒馆**的能力，所有版本切换都发生在构建期——这正是 N01 要补上的能力。

---

## 二、设计参考

| 来源 | 借鉴了什么 |
| --- | --- |
| [qingjue723/gugu-st-tools](https://github.com/qingjue723/gugu-st-tools)（咕咕助手） | Termux 一键部署 / 更新酒馆的脚本集：`git clone` + 多镜像重试（GitHub / Gitee / jsdelivr），用 `git ls-remote --heads release` 校验分支存在，既能升级也能回退版本。本 Fork 的 `mirror_wrap()` 镜像优选与「点选旧版本即回退」的交互由此而来。核心是 **git + 镜像重试**，不是 DNS 层面的特殊处理 |
| [TauriTavern 官方文档](https://tauritavern.github.io/) | 数据目录结构、导入导出语义、安卓端设置拆分方式（指南目前只有 3 页，同步 / 迁移专题尚未成文） |
| [Darkatse/TT-Sync](https://github.com/Darkatse/TT-Sync) | 远程同步服务端，把同步端点从局域网扩展到 VPS / NAS；本 Fork 未使用，仅作数据边界参考 |
| [zread：多设备同步系统解析](https://zread.ai/Darkatse/TauriTavern/19-multi-device-sync-system) | 基于源码整理：LAN Sync（mDNS，仅局域网，只 pull）与 TT-Sync（跨网络 pull + push）共用 `ClientSyncEngine`；同步按 dataset 选择范围，`OverwritePolicy` 控制冲突；同一 Wi-Fi 下手机 ↔ 电脑不需要 TT-Sync |
| [st-doc 中文酒馆教程](https://st-doc.pages.dev/basic/) | 扩展、预设、模型与记忆的中文说明 |

---

## 三、上游运行时约束（解释了为什么这样改）

| 机制 | 位置 | 与本 Fork 的关系 |
| --- | --- | --- |
| `inspectServerAssetRefreshPlan()` | `data/runtime/.../HostRuntime.kt` | 启动时比对 `server-source-manifest.json`、`dependency-selection.json` 的 SHA-256，并检查另外 3 个文件是否存在；判定 `MISSING / OUTDATED` 就从 APK 重新解压 130MB 覆盖服务目录。这是 F06 / F08 的根因，更新脚本必须回拷这 5 个文件 |
| `serverDir` / `serverDataDir` 分离 | `filesDir/android-tavern/bootstrap/server` 与 `filesDir/android-tavern/data/server` | 前者是可替换的服务源码，后者是永不覆盖的用户数据。N01 的替换范围由此确定 |
| `termux-host-runtime.sh` | `assets/bootstrap/scripts/` | 导出 `LD_LIBRARY_PATH`、`GIT_EXEC_PATH`、`GIT_SSL_CAINFO` 等，是设备上 git / curl / node 的运行环境。本 Fork 保持官方原版，未注入任何 DNS 补丁 |
| `SettingsNavigationContract.resultShouldReloadTavernUiKey` | 宿主设置返回结果 | 承载「换版本后要刷新页面」的意图，F05 复用这条既有通路 |

---

## 四、许可与致谢

- 上游 SillyDroid：PolyForm Noncommercial 1.0.0
- SillyTavern 及各扩展：各自上游许可
- 感谢上述项目作者与资料整理者；本 Fork 的改动与文档不构成官方分支
