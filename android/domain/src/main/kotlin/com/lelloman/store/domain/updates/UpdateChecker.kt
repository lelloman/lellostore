package com.lelloman.store.domain.updates

import com.lelloman.store.domain.model.AvailableUpdate
import kotlinx.coroutines.flow.StateFlow

interface UpdateChecker {
    val availableUpdates: StateFlow<List<AvailableUpdate>>
    suspend fun checkForUpdates(): Result<List<AvailableUpdate>>
}
