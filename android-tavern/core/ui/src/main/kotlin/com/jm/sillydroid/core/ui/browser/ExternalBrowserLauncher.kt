package com.jm.sillydroid.core.ui.browser

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 外部浏览器的打开方式。
 *
 * 默认 ASK：每次都弹选择器。部分厂商系统（尤其已设置「默认浏览器」时）会把只有一个
 * 隐式候选的 createChooser 直接折叠成默认应用跳转，所以这里显式枚举候选并注入
 * EXTRA_INITIAL_INTENTS，保证选择器一定出现，同时把 Firefox 放在首位。
 */
enum class ExternalBrowserMode(val storageValue: String) {
    ASK("ask"),
    FIREFOX("firefox"),
    SYSTEM_DEFAULT("system_default");

    companion object {
        fun fromStorageValue(raw: String?): ExternalBrowserMode =
            entries.firstOrNull { it.storageValue == raw } ?: ASK
    }
}

object ExternalBrowserLauncher {
    private const val PREFERENCES_NAME = "sillydroid-external-browser"
    private const val MODE_KEY = "external-browser-mode"
    private const val FIREFOX_PACKAGE = "org.mozilla.firefox"
    private const val CHOOSER_TITLE = "选择浏览器打开酒馆"

    fun currentMode(context: Context): ExternalBrowserMode {
        val preferences = context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val stored = preferences.getString(MODE_KEY, null)
        if (stored != null) {
            return ExternalBrowserMode.fromStorageValue(stored)
        }
        // 默认策略：装了 Firefox 就直接定向 Firefox。部分厂商 ROM 会把 chooser 里所有
        // 浏览器合并成一个分组并沿用系统默认浏览器，导致「每次询问」实际选不到 Firefox。
        // 仍可在设置里手动改回「每次询问」或「跟随系统默认」。
        return if (isFirefoxAvailable(context)) {
            ExternalBrowserMode.FIREFOX
        } else {
            ExternalBrowserMode.ASK
        }
    }

    fun setMode(context: Context, mode: ExternalBrowserMode) {
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(MODE_KEY, mode.storageValue)
            .apply()
    }

    fun isFirefoxAvailable(context: Context): Boolean {
        return buildFirefoxIntent(context, Uri.parse("http://127.0.0.1/"))
            ?.resolveActivity(context.packageManager) != null
    }

    /**
     * 启动外部浏览器。返回 false 表示当前条件下没有可用的目标（例如选了 Firefox 但未安装），
     * 调用方据此给出失败提示；ActivityNotFoundException 仍由调用方捕获。
     */
    fun launch(context: Context, targetUri: Uri): Boolean {
        val baseIntent = Intent(Intent.ACTION_VIEW, targetUri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        val candidates = queryExplicitBrowserIntents(context, targetUri)
        val firefoxIntent = candidates.firstOrNull {
            it.component?.packageName == FIREFOX_PACKAGE
        }

        val launchIntent = when (currentMode(context)) {
            ExternalBrowserMode.FIREFOX -> {
                // setPackage 会绕过系统「默认浏览器」解析，直接定向到 Firefox。
                buildFirefoxIntent(context, targetUri) ?: return false
            }

            ExternalBrowserMode.SYSTEM_DEFAULT -> baseIntent

            ExternalBrowserMode.ASK -> {
                if (candidates.isEmpty()) {
                    baseIntent
                } else {
                    // Firefox 优先，其余按系统解析顺序排列；显式 component 让 chooser
                    // 至少拿到两个可选目标，厂商系统无法再折叠成默认跳转。
                    val ordered = buildList {
                        firefoxIntent?.let { add(it) }
                        candidates.filterNot { it === firefoxIntent }.forEach { add(it) }
                    }
                    Intent.createChooser(ordered.first(), CHOOSER_TITLE).apply {
                        if (ordered.size > 1) {
                            putExtra(
                                Intent.EXTRA_INITIAL_INTENTS,
                                ordered.drop(1).toTypedArray()
                            )
                        }
                    }
                }
            }
        }

        if (context !is Activity) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(launchIntent)
        return true
    }

    private fun buildFirefoxIntent(context: Context, targetUri: Uri): Intent? {
        val intent = Intent(Intent.ACTION_VIEW, targetUri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            setPackage(FIREFOX_PACKAGE)
        }
        return intent.takeIf { it.resolveActivity(context.packageManager) != null }
    }

    /** 枚举设备上真正能打开该 URL 的浏览器 Activity，并做成显式 intent。 */
    private fun queryExplicitBrowserIntents(context: Context, targetUri: Uri): List<Intent> {
        val probe = Intent(Intent.ACTION_VIEW, targetUri)
        val resolved = runCatching {
            context.packageManager.queryIntentActivities(probe, 0)
        }.getOrDefault(emptyList())
        return resolved
            .asSequence()
            .mapNotNull { info ->
                val activityInfo = info.activityInfo ?: return@mapNotNull null
                val packageName = activityInfo.packageName ?: return@mapNotNull null
                if (packageName == context.packageName) return@mapNotNull null
                Intent(Intent.ACTION_VIEW, targetUri).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    component = ComponentName(packageName, activityInfo.name)
                }
            }
            .distinctBy { it.component }
            .toList()
    }
}
