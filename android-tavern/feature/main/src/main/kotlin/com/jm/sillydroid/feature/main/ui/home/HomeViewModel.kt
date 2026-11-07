package com.jm.sillydroid.feature.main.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jm.sillydroid.core.model.bootstrap.BootstrapSessionSnapshot
import com.jm.sillydroid.core.model.settings.BrowserDataClearOptions
import com.jm.sillydroid.domain.bootstrap.BootstrapController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 把 MainActivity 上跨重建仍要保留的会话级状态集中托管，
 * 避免 Activity 自己持有大量裸字段同时混杂 UI 操作与 BootstrapController 订阅。
 *
 * - bootstrapSnapshot: 由 ViewModel 在 viewModelScope 内持续订阅 processManager.snapshot；
 *   Activity 通过 repeatOnLifecycle 重新接收。
 * - loadedUrl 是进程内的当前 WebView URL 记录，只用于同一次 Activity 生命周期内的诊断和重试。
 *   WebView URL 和 Chromium state 都不进入 Activity saved state，避免 Activity stop 时放大状态包。
 * - pendingLocalRetryAttempts 是进程内本地重试节流；Activity 重建后回到默认值。
 * - shouldForceFreshWebViewLoad / browserDataClearMask 用来承接“清空宿主数据并重新初始化”或
 *   “按选择清理浏览器数据”这类必须丢弃旧 WebView 状态的单次请求；只在下一次 ready 后消费一次，
 *   避免影响普通重启/刷新路径。
 * - 其余字段是进程生命期内的轻量瞬态状态，不接入 Android saved state。
 */
class HomeViewModel(
    private val bootstrapController: BootstrapController
) : ViewModel() {

    private val mutableBootstrapSnapshot = MutableStateFlow(bootstrapController.currentSnapshot())
    val bootstrapSnapshot: StateFlow<BootstrapSessionSnapshot> = mutableBootstrapSnapshot.asStateFlow()

    var loadedUrl: String = ""

    var pendingLocalRetryAttempts: Int = 0

    var shouldForceFreshWebViewLoad: Boolean = false
    var browserDataClearMask: Int = 0
        set(value) {
            field = BrowserDataClearOptions.normalize(value)
        }
    var isOpeningBootstrapSettings: Boolean = false
    var isPullGestureRefreshing: Boolean = false
    var isImeVisible: Boolean = false
    /**
     * 从设置页换了酒馆版本（或改了会换前端资源的东西）后置为 true。
     *
     * 服务重启期间用户可能已经退回主界面，此时 snapshot 还没 ready，无法立刻刷新；
     * 这里记一个待办，等 bootstrap 真正 ready、浏览器表面重新可见时再统一刷新一次，
     * 否则用户会一直看到更新前的旧页面，必须手动下拉刷新才会切过去。
     */
    var pendingTavernUiReload: Boolean = false
    var backgroundOnlyExternalBrowserAttemptKey: String = ""
    private var pendingWebViewRendererCrashBrowserEngineHint: Boolean = false
    private var pendingWebViewDocumentStartUnsupportedHint: Boolean = false

    init {
        viewModelScope.launch {
            bootstrapController.snapshot.collect { snapshot ->
                mutableBootstrapSnapshot.value = snapshot
            }
        }
    }

    fun resetForBootstrapRestart() {
        loadedUrl = ""
        pendingLocalRetryAttempts = 0
    }

    fun requestWebViewRendererCrashBrowserEngineHint() {
        pendingWebViewRendererCrashBrowserEngineHint = true
    }

    fun consumeWebViewRendererCrashBrowserEngineHint(): Boolean {
        if (!pendingWebViewRendererCrashBrowserEngineHint) {
            return false
        }
        pendingWebViewRendererCrashBrowserEngineHint = false
        return true
    }

    fun requestWebViewDocumentStartUnsupportedHint() {
        pendingWebViewDocumentStartUnsupportedHint = true
    }

    fun consumeWebViewDocumentStartUnsupportedHint(): Boolean {
        if (!pendingWebViewDocumentStartUnsupportedHint) {
            return false
        }
        pendingWebViewDocumentStartUnsupportedHint = false
        return true
    }

    class Factory(
        private val bootstrapController: BootstrapController
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(
            modelClass: Class<T>
        ): T {
            require(modelClass.isAssignableFrom(HomeViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            return HomeViewModel(bootstrapController) as T
        }
    }
}
