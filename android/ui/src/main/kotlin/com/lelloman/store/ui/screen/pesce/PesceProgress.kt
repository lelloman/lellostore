package com.lelloman.store.ui.screen.pesce

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lelloman.store.domain.remote.*
import com.lelloman.store.ui.R

/** Shared wording keeps the screen and foreground notification in sync. */
fun remoteProgressText(context: Context, state: RemoteDeviceState): String {
    val label = if (state.operation == RemoteOperationPhase.IDLE || state.phase == RemoteConnectionPhase.RESTARTING) {
        connectionLabel(state.phase)
    } else operationLabel(state.operation)
    val text = context.getString(label)
    val progress = state.transferProgress ?: return text
    return text + " " + context.getString(R.string.pesce_transfer_progress, (progress * 100).toInt(),
        Formatter.formatShortFileSize(context, state.bytes.coerceIn(0, state.totalBytes)),
        Formatter.formatShortFileSize(context, state.totalBytes))
}

@Composable
internal fun PesceProgress(state: RemoteDeviceState, onStop: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.activeApp?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            Text(remoteProgressText(LocalContext.current, state), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            val progress = state.transferProgress
            if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.pendingCount > 0) Text(androidx.compose.ui.res.pluralStringResource(
                R.plurals.pesce_pending, state.pendingCount, state.pendingCount))
            TextButton(onClick = onStop) { Text(stringResource(R.string.pesce_stop)) }
        }
    }
}
