package com.lelloman.store.ui.screen.catalog

sealed interface CatalogScreenEvent {
    data class NavigateToAppDetail(val packageName: String) : CatalogScreenEvent
}
