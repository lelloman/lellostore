package com.lelloman.store.ui.screen.detail

sealed interface AppDetailScreenEvent {
    data class OpenApp(val packageName: String) : AppDetailScreenEvent
}
