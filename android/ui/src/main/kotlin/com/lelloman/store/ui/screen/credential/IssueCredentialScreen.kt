package com.lelloman.store.ui.screen.credential

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lelloman.store.ui.R
import com.lelloman.store.ui.components.LelloStoreAppIcon
import com.lelloman.store.ui.components.LelloStoreBrandMark
import com.lelloman.store.ui.components.lelloStoreButtonColors
import com.lelloman.store.ui.theme.LelloStoreSpacing

/** Who is asking, as resolved by the activity from the calling package. */
data class CredentialCaller(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap? = null,
)

/**
 * The single confirmation screen of a credential request. It shows the caller and what it
 * receives; the credential itself is never displayed.
 */
@Composable
fun IssueCredentialScreen(
    state: IssueCredentialScreenState,
    caller: CredentialCaller?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onOpenStore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onCancel)
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(LelloStoreSpacing.xLarge),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (state) {
                    IssueCredentialScreenState.Loading, is IssueCredentialScreenState.Finished ->
                        CircularProgressIndicator()
                    IssueCredentialScreenState.NeedsLogin -> NeedsLoginContent(caller, onOpenStore, onCancel)
                    is IssueCredentialScreenState.Confirm -> ConfirmContent(state, caller, onConfirm, onCancel)
                }
            }
        }
    }
}

@Composable
private fun ConfirmContent(
    state: IssueCredentialScreenState.Confirm,
    caller: CredentialCaller?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    CallerHeader(caller)
    Spacer(Modifier.height(LelloStoreSpacing.xLarge))
    Text(
        text = state.description.title,
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(LelloStoreSpacing.medium))
    Text(
        text = state.description.message,
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(LelloStoreSpacing.medium))
    Text(
        text = stringResource(R.string.credential_issued_by, state.account),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(LelloStoreSpacing.xLarge))
    Row(horizontalArrangement = Arrangement.spacedBy(LelloStoreSpacing.medium)) {
        OutlinedButton(onClick = onCancel, enabled = !state.isIssuing) {
            Text(stringResource(R.string.cancel))
        }
        Button(onClick = onConfirm, enabled = !state.isIssuing, colors = lelloStoreButtonColors()) {
            if (state.isIssuing) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(LelloStoreSpacing.small))
            }
            Text(stringResource(R.string.credential_confirm))
        }
    }
}

@Composable
private fun NeedsLoginContent(caller: CredentialCaller?, onOpenStore: () -> Unit, onCancel: () -> Unit) {
    CallerHeader(caller)
    Spacer(Modifier.height(LelloStoreSpacing.xLarge))
    Text(
        text = stringResource(R.string.credential_login_title),
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(LelloStoreSpacing.medium))
    Text(
        text = stringResource(R.string.credential_login_message, caller?.label.orEmpty()),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(LelloStoreSpacing.xLarge))
    Row(horizontalArrangement = Arrangement.spacedBy(LelloStoreSpacing.medium)) {
        OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        Button(onClick = onOpenStore, colors = lelloStoreButtonColors()) {
            Text(stringResource(R.string.credential_open_store))
        }
    }
}

@Composable
internal fun CallerHeader(caller: CredentialCaller?) {
    if (caller == null) {
        LelloStoreBrandMark(contentDescription = null, size = 72.dp)
        return
    }
    val iconModifier = Modifier.size(72.dp).clip(MaterialTheme.shapes.medium)
    if (caller.icon != null) {
        Image(bitmap = caller.icon, contentDescription = null, modifier = iconModifier)
    } else {
        LelloStoreAppIcon(name = caller.label, iconUrl = null, contentDescription = caller.label, modifier = iconModifier)
    }
    Spacer(Modifier.height(LelloStoreSpacing.medium))
    Text(text = caller.label, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    // The package name is what identifies the caller; any app can choose its label.
    if (caller.label != caller.packageName) {
        Text(
            text = caller.packageName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
