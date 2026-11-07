#!/system/bin/sh
# ============================================================================
# update-tavern.sh — 在 SillyDroid 运行时内更新 / 回退 SillyTavern
#
# 设计目标（对齐 gugu-st-tools 的体验）：
#   - 可指定版本（tag / 分支），留空则拉最新 release；选旧版即“回退”。
#   - 自动探测网络：先试 GitHub，失败则回退到国内镜像（ghproxy 等）。
#   - 数据安全：只覆盖服务源码目录（SERVER_DIR），保留 config.yaml 与用户数据目录。
#   - 进度通过 TAVERN_PROGRESS_FILE（JSON）对外上报，stdout 同步打印日志。
#
# 环境变量（由宿主 App 注入；终端手动运行时可用默认值）：
#   SERVER_DIR            酒馆服务源码目录（默认尝试标准路径）
#   BOOTSTRAP_ROOT       运行时根目录
#   APP_DATA_ROOT        用户数据目录（与 SERVER_DIR 分离，不会被覆盖）
#   TAVERN_TARGET_REF    目标版本：tag 名 / 分支名 / "latest"（默认 latest）
#   TAVERN_MIRROR        镜像策略：auto（默认）/ github / <自定义镜像前缀>
#   TAVERN_PROGRESS_FILE 进度文件（可选）
#   TAVERN_INFO_FILE     信息输出文件（probe / list 子命令写结果 JSON）
#   TAVERN_DNS_MAP       宿主预解析的 IP 映射，格式 "host=ip"（可多组，用逗号分隔）。
#                         国产 ROM 会限制 App UID 的 UDP/53 与裸 TCP 出站，
#                         脚本自身无法解析域名；由宿主用 Android InetAddress 解析后注入，
#                         curl 用 --resolve 绕过 DNS。
#
# 子命令（第一个参数）：
#   update（默认）  拉取并覆盖服务源码
#   probe           探测可用的下载线路，把结果写入 TAVERN_INFO_FILE
#   list            拉取最近版本列表，写入 TAVERN_INFO_FILE
# ============================================================================
set -eu

ACTION="${1:-update}"

# ---- 默认值（仅终端直跑时兜底；App 内调用由宿主注入 SERVER_DIR / APP_DATA_ROOT） ----
# debug 构建带 .debug 后缀，release 构建不带。按实际存在的目录探测，两个包都能用。
if [ -z "${APP_PACKAGE:-}" ]; then
    for pkg in com.jm.sillydroid.debug com.jm.sillydroid; do
        if [ -d "/data/data/$pkg/files/android-tavern" ]; then
            APP_PACKAGE="$pkg"
            break
        fi
    done
    APP_PACKAGE="${APP_PACKAGE:-com.jm.sillydroid}"
fi
if [ -z "${SERVER_DIR:-}" ]; then
    SERVER_DIR="/data/data/$APP_PACKAGE/files/android-tavern/bootstrap/server"
fi
if [ -z "${APP_DATA_ROOT:-}" ]; then
    APP_DATA_ROOT="/data/data/$APP_PACKAGE/files/android-tavern/data"
fi
REF="${TAVERN_TARGET_REF:-latest}"
MIRROR_MODE="${TAVERN_MIRROR:-auto}"
PROGRESS_FILE="${TAVERN_PROGRESS_FILE:-/dev/null}"
INFO_FILE="${TAVERN_INFO_FILE:-/dev/null}"
DNS_MAP="${TAVERN_DNS_MAP:-}"

# ---- 宿主预解析 IP 查询 ----
# TAVERN_DNS_MAP 形如 "api.github.com=1.2.3.4,codeload.github.com=5.6.7.8"
dns_lookup_pre() {
    # $1 = 主机名；输出 IP（未命中则输出空）
    _host="$1"
    _rest="$DNS_MAP"
    while [ -n "$_rest" ]; do
        _pair="${_rest%%,*}"
        if [ "$_pair" = "$_rest" ]; then
            _rest=""
        else
            _rest="${_rest#*,}"
        fi
        _k="${_pair%%=*}"
        _v="${_pair#*=}"
        if [ "$_k" = "$_host" ]; then
            printf '%s' "$_v"
            return 0
        fi
    done
    return 1
}

# 生成 curl 的 --resolve 参数（命中预解析 IP 时使用）
dns_resolve_arg() {
    # $1 = URL；输出 "--resolve host:port:ip" 或空
    _host="$1"
    _ip="$(dns_lookup_pre "$_host" 2>/dev/null || true)"
    if [ -n "$_ip" ]; then
        printf -- "--resolve %s:443:%s" "$_host" "$_ip"
    fi
}

TMP_WORK="$APP_DATA_ROOT/.sillydroid-maintenance/tavern-update"
rm -rf "$TMP_WORK"
mkdir -p "$TMP_WORK"

progress() {
    local pct="$1"
    local msg="$2"
    if [ "$PROGRESS_FILE" != "/dev/null" ]; then
        printf '{"percent":%s,"message":"%s"}\n' "$pct" "$msg" > "$PROGRESS_FILE" 2>/dev/null || true
    fi
    echo "[update-tavern] ($pct%) $msg"
}

# 什么都没做就结束（目标版本已安装）时用这个：
# 多带一个 skipped 标记，宿主据此显示「已是最新版本」而不是「更新成功」。
progress_skip() {
    local msg="$1"
    if [ "$PROGRESS_FILE" != "/dev/null" ]; then
        printf '{"percent":100,"message":"%s","skipped":true}\n' "$msg" > "$PROGRESS_FILE" 2>/dev/null || true
    fi
    echo "[update-tavern] (100%) $msg"
}

# 当前已安装版本：取服务端 package.json 的 version 字段。
current_version() {
    if [ -f "$SERVER_DIR/package.json" ]; then
        grep -oE '"version"[[:space:]]*:[[:space:]]*"[^"]+"' "$SERVER_DIR/package.json" 2>/dev/null \
            | head -1 \
            | sed -E 's/.*"version"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/'
    fi
}

# 依赖安装失败时把 npm 输出留一份到日志目录，否则完全无从排查。
keep_npm_log() {
    if [ -f "${NPM_LOG:-/nonexistent}" ] && [ -n "${LOGS_DIR:-}" ] && [ -d "${LOGS_DIR:-}" ]; then
        cp -f "$NPM_LOG" "$LOGS_DIR/tavern-update-npm.log" 2>/dev/null || true
    fi
}

# 清理下载包与解压产物。
# 38MB 的 tar.gz 加上完整解压目录没有任何保留价值；
# 之前只在「下一次更新」开头才清，等于一直白占用户空间。
cleanup_work() {
    rm -rf "$TMP_WORK/extract" 2>/dev/null || true
    rm -f "$TMP_WORK/tavern.tar.gz" 2>/dev/null || true
}

# ---- 镜像候选：依次尝试，自动探测网络 ----
GITHUB_TARBASE="https://github.com/SillyTavern/SillyTavern/archive/refs/tags"
GITHUB_API_LATEST="https://api.github.com/repos/SillyTavern/SillyTavern/releases/latest"
GITHUB_API_TAGS="https://api.github.com/repos/SillyTavern/SillyTavern/tags?per_page=30"

# 把任意 GitHub URL 套上镜像前缀；auto 模式下按顺序尝试
mirror_wrap() {
    # $1 = 原始 github 绝对 URL；输出候选 URL 列表（每行一个）
    case "$MIRROR_MODE" in
        github) printf '%s\n' "$1" ;;
        auto)
            # 先原站，再两个常见国内镜像（ghproxy 全家桶）
            printf '%s\n' "$1"
            printf '%s\n' "https://ghproxy.com/$1"
            printf '%s\n' "https://mirror.ghproxy.com/$1"
            printf '%s\n' "https://gh.api.99988866.xyz/$1"
            ;;
        *)
            # 自定义镜像前缀（例如 https://ghproxy.com/ 或 https://mirror.ghproxy.com/）
            printf '%s\n' "$1"
            printf '%s\n' "${MIRROR_MODE%/}/$1"
            ;;
    esac
}

curl_fetch() {
    # $1 = 目标文件; 其余 = 候选 URL（按顺序）; 返回 0 表示成功
    local out="$1"; shift
    for u in "$@"; do
        echo "[update-tavern]   try: $u"
        # 宿主预解析命中时用 --resolve 绕过 App 进程内不可用的 DNS。
        local resolve_arg
        resolve_arg="$(dns_resolve_arg "$u")"
        if [ -n "$resolve_arg" ]; then
            if curl -fL --retry 2 --connect-timeout 12 --max-time 180 -o "$out" $resolve_arg "$u" 2>/dev/null; then
                if [ -s "$out" ]; then
                    return 0
                fi
            fi
        else
            if curl -fL --retry 2 --connect-timeout 12 --max-time 180 -o "$out" "$u" 2>/dev/null; then
                if [ -s "$out" ]; then
                    return 0
                fi
            fi
        fi
        rm -f "$out"
    done
    return 1
}

# 线路探测：逐条试连，输出可用线路名
probe_line() {
    # $1 = 线路名; $2 = URL; 成功返回 0
    local name="$1"; local url="$2"
    local resolve_arg
    resolve_arg="$(dns_resolve_arg "$url")"
    if [ -n "$resolve_arg" ]; then
        if curl -fL -s --connect-timeout 8 --max-time 20 $resolve_arg -o /dev/null "$url" 2>/dev/null; then
            echo "[update-tavern] probe ok: $name"
            return 0
        fi
    else
        if curl -fL -s --connect-timeout 8 --max-time 20 -o /dev/null "$url" 2>/dev/null; then
            echo "[update-tavern] probe ok: $name"
            return 0
        fi
    fi
    echo "[update-tavern] probe fail: $name"
    return 1
}

write_info() {
    # $1 = 完整 JSON 字符串
    if [ "$INFO_FILE" != "/dev/null" ]; then
        printf '%s\n' "$1" > "$INFO_FILE" 2>/dev/null || true
    fi
}

json_escape() {
    # 转义 JSON 字符串里的引号与反斜杠
    printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'
}

# ---- Node 侧 DNS 能力诊断 ----
# 背景：部分国产 ROM 会让 App 进程内 native getaddrinfo（Node 的 dns.lookup）失败，
# 但 Android Java 层 InetAddress 与 TCP 出站都正常。
# 这里逐项测试 Node 可用的解析途径，供宿主决定是否需要注入 DNS 补丁。
diag_node_dns() {
    DIAG_JS="$TMP_WORK/diag-node-dns.js"
    cat > "$DIAG_JS" <<'JSEOF'
const dns = require('dns');
const https = require('https');
const results = {};

function step(name, fn) {
    return new Promise((resolve) => {
        let done = false;
        const timer = setTimeout(() => {
            if (done) { return; }
            done = true;
            results[name] = 'TIMEOUT';
            resolve();
        }, 12000);
        fn((value) => {
            if (done) { return; }
            done = true;
            clearTimeout(timer);
            results[name] = value;
            resolve();
        });
    });
}

function doh(host, servername, path) {
    return (cb) => {
        const request = https.request({
            host: host,
            port: 443,
            path: path,
            method: 'GET',
            servername: servername,
            headers: { 'Host': servername, 'Accept': 'application/dns-json' },
            timeout: 12000
        }, (res) => {
            let body = '';
            res.on('data', (chunk) => { body += chunk; });
            res.on('end', () => cb('HTTP' + res.statusCode + ' ' + body.slice(0, 160)));
        });
        request.on('error', (error) => cb('ERR:' + (error.code || error.message)));
        request.on('timeout', () => { request.destroy(); cb('TIMEOUT'); });
        request.end();
    };
}

(async () => {
    await step('lookup_github', (cb) => dns.lookup('api.github.com', (e, a) => cb(e ? ('ERR:' + e.code) : a)));
    await step('lookup_api_cn', (cb) => dns.lookup('api.qiyiguoai.com', (e, a) => cb(e ? ('ERR:' + e.code) : a)));
    await step('resolve4_alidns', (cb) => {
        const resolver = new dns.Resolver();
        resolver.setServers(['223.5.5.5']);
        resolver.resolve4('api.github.com', (e, a) => cb(e ? ('ERR:' + e.code) : a));
    });
    await step('doh_alidns_ip', doh('223.5.5.5', 'dns.alidns.com', '/dns-query?name=api.github.com&type=A'));
    await step('doh_cloudflare_ip', doh('1.1.1.1', 'cloudflare-dns.com', '/dns-query?name=api.github.com&type=A'));
    await step('doh_dohpub_ip', doh('120.53.53.53', 'doh.pub', '/dns-query?name=api.github.com&type=A'));
    console.log(JSON.stringify(results));
})();
JSEOF
    NODE_BIN="${SILLYDROID_HOST_COMMAND_PATH:-}/node"
    if [ ! -x "$NODE_BIN" ]; then
        NODE_BIN="$(command -v node 2>/dev/null || true)"
    fi
    if [ -z "$NODE_BIN" ] || [ ! -x "$NODE_BIN" ]; then
        printf '{"error":"node_not_found"}'
        return 0
    fi
    "$NODE_BIN" "$DIAG_JS" 2>&1 | tr -d '\n'
    return 0
}

# ---- 子命令：probe（探测线路）----
if [ "$ACTION" = "probe" ]; then
    mkdir -p "$APP_DATA_ROOT/.sillydroid-maintenance"
    TMP_PROBE="$APP_DATA_ROOT/.sillydroid-maintenance/probe"
    rm -rf "$TMP_PROBE"
    mkdir -p "$TMP_PROBE"

    WORKING=""
    WORKING_NAME=""
    if probe_line "github" "$GITHUB_API_LATEST"; then
        WORKING="$GITHUB_API_LATEST"
        WORKING_NAME="github"
    elif probe_line "ghproxy" "https://ghproxy.com/$GITHUB_API_LATEST"; then
        WORKING="https://ghproxy.com/$GITHUB_API_LATEST"
        WORKING_NAME="ghproxy"
    elif probe_line "mirror.ghproxy" "https://mirror.ghproxy.com/$GITHUB_API_LATEST"; then
        WORKING="https://mirror.ghproxy.com/$GITHUB_API_LATEST"
        WORKING_NAME="mirror.ghproxy"
    elif probe_line "gh.api.99988866" "https://gh.api.99988866.xyz/$GITHUB_API_LATEST"; then
        WORKING="https://gh.api.99988866.xyz/$GITHUB_API_LATEST"
        WORKING_NAME="gh.api.99988866"
    fi

    if [ -z "$WORKING_NAME" ]; then
        NODE_DIAG="$(diag_node_dns)"
        write_info "{\"reachable\":false,\"working_line\":\"\",\"working_url\":\"\",\"dns_map\":\"$(json_escape "$DNS_MAP")\",\"node_diag\":\"$(json_escape "$NODE_DIAG")\"}"
        echo "[update-tavern] probe: no reachable line"
        exit 0
    fi

    # 顺便取回最新版本号，供 UI 直接展示
    LATEST_TAG=""
    LATEST_RESOLVE="$(dns_resolve_arg "$WORKING" 2>/dev/null || true)"
    if [ -n "$LATEST_RESOLVE" ]; then
        curl -fL -s --connect-timeout 8 --max-time 30 $LATEST_RESOLVE -o "$TMP_PROBE/latest.json" "$WORKING" 2>/dev/null || true
    else
        curl -fL -s --connect-timeout 8 --max-time 30 -o "$TMP_PROBE/latest.json" "$WORKING" 2>/dev/null || true
    fi
    if [ -f "$TMP_PROBE/latest.json" ]; then
        LATEST_TAG="$(grep -oE '"tag_name"[[:space:]]*:[[:space:]]*"[0-9A-Za-z._-]+"' "$TMP_PROBE/latest.json" 2>/dev/null | head -1 | sed -E 's/.*"tag_name"[[:space:]]*:[[:space:]]*"([0-9A-Za-z._-]+)".*/\1/')"
    fi

    NODE_DIAG="$(diag_node_dns)"
    write_info "{\"reachable\":true,\"working_line\":\"$(json_escape "$WORKING_NAME")\",\"working_url\":\"$(json_escape "$WORKING")\",\"latest_tag\":\"$(json_escape "$LATEST_TAG")\",\"dns_map\":\"$(json_escape "$DNS_MAP")\",\"node_diag\":\"$(json_escape "$NODE_DIAG")\"}"
    echo "[update-tavern] probe: $WORKING_NAME"
    exit 0
fi

# ---- 子命令：list（拉取最近版本列表）----
if [ "$ACTION" = "list" ]; then
    mkdir -p "$APP_DATA_ROOT/.sillydroid-maintenance"
    TMP_LIST="$APP_DATA_ROOT/.sillydroid-maintenance/list"
    rm -rf "$TMP_LIST"
    mkdir -p "$TMP_LIST"

    TAGS_JSON=""
    for base in "$GITHUB_API_TAGS" "https://ghproxy.com/$GITHUB_API_TAGS" "https://mirror.ghproxy.com/$GITHUB_API_TAGS" "https://gh.api.99988866.xyz/$GITHUB_API_TAGS"; do
        echo "[update-tavern]   try: $base"
        TAGS_RESOLVE="$(dns_resolve_arg "$base" 2>/dev/null || true)"
        if [ -n "$TAGS_RESOLVE" ]; then
            curl -fL -s --connect-timeout 8 --max-time 40 $TAGS_RESOLVE -o "$TMP_LIST/tags.json" "$base" 2>/dev/null || true
        else
            curl -fL -s --connect-timeout 8 --max-time 40 -o "$TMP_LIST/tags.json" "$base" 2>/dev/null || true
        fi
        if [ -s "$TMP_LIST/tags.json" ]; then
            TAGS_JSON="$TMP_LIST/tags.json"
            break
        fi
    done

    if [ -z "$TAGS_JSON" ]; then
        write_info '{"ok":false,"versions":[]}'
        echo "[update-tavern] list: failed to fetch tags"
        exit 0
    fi

    # 抽取 name 字段，拼成 JSON 数组
    TAGS_TXT="$TMP_LIST/tags.txt"
    grep -oE '"name"[[:space:]]*:[[:space:]]*"[0-9A-Za-z._-]+"' "$TAGS_JSON" \
        | sed -E 's/.*"name"[[:space:]]*:[[:space:]]*"([0-9A-Za-z._-]+)".*/\1/' \
        > "$TAGS_TXT" || true

    VERSIONS=""
    COUNT=0
    while IFS= read -r tag; do
        [ -z "$tag" ] && continue
        if [ -n "$VERSIONS" ]; then
            VERSIONS="$VERSIONS,"
        fi
        VERSIONS="$VERSIONS\"$(json_escape "$tag")\""
        COUNT=$((COUNT + 1))
        if [ "$COUNT" -ge 30 ]; then
            break
        fi
    done < "$TAGS_TXT"

    write_info "{\"ok\":true,\"versions\":[$VERSIONS]}"
    echo "[update-tavern] list: $COUNT versions"
    exit 0
fi

# ---- 1. 解析目标 tag ----
progress 5 "解析目标版本: $REF"
if [ "$REF" = "latest" ] || [ -z "$REF" ]; then
    LATEST_JSON="$TMP_WORK/latest.json"
    if ! curl_fetch "$LATEST_JSON" $(mirror_wrap "$GITHUB_API_LATEST"); then
        progress 5 "无法访问 GitHub 获取最新版本（含镜像兜底均失败）"
        exit 1
    fi
    TAG="$(grep -oE '"tag_name"[[:space:]]*:[[:space:]]*"[0-9A-Za-z._-]+"' "$LATEST_JSON" | head -1 | sed -E 's/.*"tag_name"[[:space:]]*:[[:space:]]*"([0-9A-Za-z._-]+)".*/\1/')"
    if [ -z "$TAG" ]; then
        progress 5 "解析最新版本 tag 失败"
        exit 1
    fi
else
    TAG="$REF"
fi
progress 12 "目标版本: $TAG"

# 目标版本与已安装版本一致时直接结束。
# 之前这里不看现状：即使已经是最新版，也会照旧下载 38MB 压缩包、整目录替换一遍，
# 用户看到的却是「更新成功」——既浪费时间又让人怀疑版本到底有没有变。
CURRENT_VERSION="$(current_version)"
if [ -n "$CURRENT_VERSION" ] && [ "$CURRENT_VERSION" = "$TAG" ]; then
    if [ "$REF" = "latest" ] || [ -z "$REF" ]; then
        progress_skip "当前已是最新版本 $TAG，无需更新"
    else
        progress_skip "当前已是 $TAG，无需重复安装"
    fi
    rm -rf "$TMP_WORK" 2>/dev/null || true
    echo "[update-tavern] skip: already $TAG"
    exit 0
fi

# ---- 2. 下载 tarball ----
progress 18 "下载酒馆 $TAG 压缩包..."
TARBALL="$TMP_WORK/tavern.tar.gz"
# github 的 tag archive 实际落在 codeload，ghproxy 等会跟随重定向
TAR_URL="$GITHUB_TARBASE/$TAG.tar.gz"
if ! curl_fetch "$TARBALL" $(mirror_wrap "$TAR_URL"); then
    progress 18 "下载 $TAG 失败（GitHub 与镜像均不可用）"
    exit 1
fi

# ---- 3. 校验并解压 ----
progress 40 "校验压缩包..."
if ! tar -tzf "$TARBALL" >/dev/null 2>&1; then
    progress 40 "压缩包损坏或不是合法 tar.gz"
    exit 1
fi
EXTRACT="$TMP_WORK/extract"
mkdir -p "$EXTRACT"
tar -xzf "$TARBALL" -C "$EXTRACT"
# github tag archive 顶层目录为 SillyTavern-<tag>
SRC="$(find "$EXTRACT" -maxdepth 1 -mindepth 1 -type d | head -1)"
if [ -z "$SRC" ] || [ ! -f "$SRC/package.json" ]; then
    progress 40 "解压结果异常（未找到 package.json）"
    exit 1
fi
progress 50 "解压完成"

# ---- 4. 安全覆盖 SERVER_DIR：保留 config.yaml 与依赖 ----
# 用户数据位于独立的 APP_DATA_ROOT（serverDataDir），本目录仅服务源码，清空无碍数据。
progress 60 "备份并替换服务目录（保留 config.yaml）..."
BAK="$TMP_WORK/server_old"
rm -rf "$BAK"
mv "$SERVER_DIR" "$BAK"

# 回滚：先把依赖还回备份，再整体换回。
# node_modules 是从备份里 rename 出来的（同分区，瞬时完成），
# 所以回滚时必须先搬回去，否则换回来的旧版本会缺依赖。
restore_old() {
    if [ -d "$SERVER_DIR/node_modules" ] && [ ! -d "$BAK/node_modules" ]; then
        mv "$SERVER_DIR/node_modules" "$BAK/node_modules" 2>/dev/null || true
    fi
    rm -rf "$SERVER_DIR"
    mv "$BAK" "$SERVER_DIR"
}

# 任何步骤失败（含被中途终止）且新源码未就位时，自动把旧版本搬回，避免服务端目录缺失。
# 同时无论成功失败都清掉下载包与解压产物。
on_exit() {
    if [ ! -f "$SERVER_DIR/package.json" ] && [ -d "$BAK" ]; then
        echo "[update-tavern] 回滚到旧版本"
        restore_old
    fi
    cleanup_work
}
trap on_exit EXIT

mkdir -p "$SERVER_DIR"
cp -a "$SRC"/. "$SERVER_DIR"/

# 优先保留用户原有 config.yaml；若原来没有则沿用新版的
if [ -f "$BAK/config.yaml" ]; then
    cp -f "$BAK/config.yaml" "$SERVER_DIR/config.yaml"
fi

# default/config.yaml 里含 Android 宿主打的兼容补丁
# （browserLaunch.enabled=false、git.backend=builtin）。
# 上游压缩包给的是原版配置，必须用备份里的覆盖，
# 否则运行时会尝试在 Android 上拉起外部浏览器、并把 git 后端切走。
if [ -f "$BAK/default/config.yaml" ]; then
    mkdir -p "$SERVER_DIR/default"
    cp -f "$BAK/default/config.yaml" "$SERVER_DIR/default/config.yaml"
fi

# 复用旧版本已安装的 node_modules。
# 用 mv 而不是 cp -a：两者在同一分区，rename 是瞬时的。
# 之前 cp -a 整个 100MB+ 依赖目录，每次更新都要几十秒到几分钟，还把手机拖得很卡。
if [ -d "$BAK/node_modules" ] && [ ! -d "$SERVER_DIR/node_modules" ]; then
    progress 64 "接管旧版依赖 node_modules…"
    mv "$BAK/node_modules" "$SERVER_DIR/node_modules" 2>/dev/null \
        || cp -a "$BAK/node_modules" "$SERVER_DIR/node_modules"
fi

# ---- 5. 调和依赖 ----
# ⚠️ 本构建的 APK 只内置 node 二进制，**没有 npm**（原生库只有 libtermux-node.so，
# 且 server-fast 命令集里 npm/npx 被显式移除）。所以必须先探测再决定，
# 盲目执行 npm install 只会 "not found" 然后白白回滚掉一次成功的下载与解压。
NPM_LOG="$TMP_WORK/npm.log"
cd "$SERVER_DIR"
if command -v npm >/dev/null 2>&1; then
    progress 78 "调和依赖 (npm install)…"
    if npm install --no-audit --no-fund --omit=dev >"$NPM_LOG" 2>&1; then
        progress 95 "依赖调和完成"
    else
        # 部分版本需要包含 dev 依赖；回退到完整安装
        progress 85 "尝试完整依赖安装…"
        if ! npm install --no-audit --no-fund >>"$NPM_LOG" 2>&1; then
            keep_npm_log
            progress 85 "npm install 失败，回滚到旧版本"
            restore_old
            exit 1
        fi
        progress 95 "依赖调和完成"
    fi
else
    # 没有 npm：沿用第 4 步接管的旧版 node_modules。
    # 酒馆的小版本升级多数情况下依赖是兼容的；万一新版起不来，
    # 用户可以再选一次别的版本回退。
    if [ -d "$SERVER_DIR/node_modules" ]; then
        progress 88 "当前环境未内置 npm，沿用现有依赖"
    else
        progress 88 "未内置 npm 且没有可复用的依赖，无法完成更新，回滚"
        restore_old
        exit 1
    fi
fi

# ---- 6. 收尾 ----
# ⚠️ 必须把 APK 托管的元数据带回新目录，否则降级/升级会在下次启动时被静默撤销。
#
# 运行时用 AssetExtractor.inspectServerAssetRefreshPlan 判断"要不要把 APK 内置的源码
# 重新解压一遍"：它比对 server-source-manifest.json / dependency-selection.json，
# 并检查 bootstrap-manifest.json、dependency-env.sh、dependency-post-extract.sh 是否存在。
# 上游 tag 压缩包里没有这些文件 → 一律被判成 MISSING → 下次启动时
# 内置版本（比如 1.19.0）会被重新解压覆盖回来。
# 这正是"装了 1.17、重启后还是 1.19"的原因，也是那次卡在重启服务（要重新解压 130MB）的原因。
for meta in bootstrap-manifest.json server-source-manifest.json dependency-selection.json \
    dependency-env.sh dependency-post-extract.sh; do
    if [ -f "$BAK/$meta" ]; then
        cp -pf "$BAK/$meta" "$SERVER_DIR/$meta"
    fi
done

# 依赖已被接管，旧备份只剩源码（约 20MB），但留着也没有恢复价值：
# 真出问题时用同一张卡片重装任意版本即可。直接删掉，避免长期占用空间。
rm -rf "$BAK" 2>/dev/null || true
progress 100 "更新完成：$TAG"
echo "[update-tavern] done: $TAG"
