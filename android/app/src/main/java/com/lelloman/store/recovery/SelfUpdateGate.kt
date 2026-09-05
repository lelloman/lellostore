package com.lelloman.store.recovery

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class SelfUpdateGate @Inject constructor(@ApplicationContext private val context: Context) {
    val packageName: String get() = context.packageName
    fun enabled(): Boolean = RecoveryCompanionClient(context).selfUpdatesEnabled()
}
