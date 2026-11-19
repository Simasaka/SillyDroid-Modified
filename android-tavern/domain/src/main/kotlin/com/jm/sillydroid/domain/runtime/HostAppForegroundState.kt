package com.jm.sillydroid.domain.runtime

interface HostAppForegroundState {
    val isInForeground: Boolean

    fun addForegroundListener(listener: (Boolean) -> Unit) {}
}
