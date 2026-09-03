package com.lelloman.store.ui.screen.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lelloman.store.ui.R
import com.lelloman.store.ui.components.LelloStoreBrandMark
import com.lelloman.store.ui.components.lelloStoreButtonColors
import com.lelloman.store.ui.theme.LelloStoreSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileBottomSheet(
    userEmail: String,
    onLogout: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LelloStoreBrandMark(contentDescription = null, size = 72.dp)
            Spacer(Modifier.height(LelloStoreSpacing.large))
            Text(
                text = stringResource(R.string.profile_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(LelloStoreSpacing.small))
            Text(
                text = stringResource(R.string.profile_signed_in_as),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(LelloStoreSpacing.xSmall))
            Text(
                text = userEmail,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(LelloStoreSpacing.xLarge))
            HorizontalDivider()
            Spacer(Modifier.height(LelloStoreSpacing.xLarge))
            Button(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                colors = lelloStoreButtonColors(),
            ) {
                Text(stringResource(R.string.logout))
            }
            Spacer(Modifier.height(LelloStoreSpacing.large))
        }
    }
}
