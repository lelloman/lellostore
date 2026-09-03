package com.lelloman.store.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lelloman.store.ui.model.ThemeMode
import com.lelloman.store.ui.theme.LelloStoreSpacing
import com.lelloman.store.ui.theme.LellostoreTheme

@Composable
fun LelloStoreBrandMark(
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
) {
    val foreground = MaterialTheme.colorScheme.onPrimaryContainer
    Box(
        modifier = modifier
            .size(size)
            .clip(MaterialTheme.shapes.large)
            .semantics(mergeDescendants = true) {
                if (contentDescription != null) this.contentDescription = contentDescription
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.matchParentSize(),
        ) {}
        Canvas(modifier = Modifier.size(size * 0.68f)) {
            val cell = this.size.width * 0.39f
            val gap = this.size.width * 0.12f
            val gridColor = foreground.copy(alpha = 0.18f)
            drawRoundRect(gridColor, size = Size(cell, cell), cornerRadius = androidx.compose.ui.geometry.CornerRadius(cell * 0.12f))
            drawRoundRect(gridColor, topLeft = Offset(cell + gap, 0f), size = Size(cell, cell), cornerRadius = androidx.compose.ui.geometry.CornerRadius(cell * 0.12f))
            drawRoundRect(gridColor, topLeft = Offset(0f, cell + gap), size = Size(cell, cell), cornerRadius = androidx.compose.ui.geometry.CornerRadius(cell * 0.12f))
            drawRoundRect(gridColor, topLeft = Offset(cell + gap, cell + gap), size = Size(cell, cell), cornerRadius = androidx.compose.ui.geometry.CornerRadius(cell * 0.12f))

            val mark = Path().apply {
                moveTo(this@Canvas.size.width * 0.26f, this@Canvas.size.height * 0.16f)
                lineTo(this@Canvas.size.width * 0.43f, this@Canvas.size.height * 0.16f)
                lineTo(this@Canvas.size.width * 0.43f, this@Canvas.size.height * 0.68f)
                lineTo(this@Canvas.size.width * 0.78f, this@Canvas.size.height * 0.68f)
                lineTo(this@Canvas.size.width * 0.78f, this@Canvas.size.height * 0.84f)
                lineTo(this@Canvas.size.width * 0.26f, this@Canvas.size.height * 0.84f)
                close()
            }
            drawPath(mark, foreground)
        }
    }
}

@Composable
fun LelloStoreStatusBadge(
    label: String,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    val container = if (emphasized) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val content = if (emphasized) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        modifier = modifier,
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
fun LelloStoreAppRow(
    name: String,
    iconUrl: String?,
    iconContentDescription: String,
    supportingText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    statusLabel: String? = null,
    statusEmphasized: Boolean = false,
    trailingContent: @Composable (() -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(LelloStoreSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = iconUrl,
                contentDescription = iconContentDescription,
                modifier = Modifier
                    .size(64.dp)
                    .clip(MaterialTheme.shapes.small),
            )
            Spacer(Modifier.width(LelloStoreSpacing.medium))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(LelloStoreSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    statusLabel?.let {
                        LelloStoreStatusBadge(label = it, emphasized = statusEmphasized)
                    }
                }
                Spacer(Modifier.height(LelloStoreSpacing.xSmall))
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                description?.let {
                    Spacer(Modifier.height(LelloStoreSpacing.xSmall))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailingContent?.let {
                Spacer(Modifier.width(LelloStoreSpacing.small))
                it()
            }
        }
    }
}

@Composable
fun LelloStoreStateContent(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.padding(LelloStoreSpacing.xLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = iconTint,
            shape = MaterialTheme.shapes.large,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.padding(LelloStoreSpacing.large).size(32.dp),
            )
        }
        Spacer(Modifier.height(LelloStoreSpacing.large))
        Text(text = title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(LelloStoreSpacing.small))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(LelloStoreSpacing.xLarge))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Preview(name = "Design system · light", showBackground = true, widthDp = 390)
@Preview(name = "Design system · dark", showBackground = true, widthDp = 390, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DesignSystemPreview() {
    val themeMode = if ((androidx.compose.ui.platform.LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) {
        ThemeMode.Dark
    } else {
        ThemeMode.Light
    }
    LellostoreTheme(themeMode = themeMode) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.padding(LelloStoreSpacing.large),
                verticalArrangement = Arrangement.spacedBy(LelloStoreSpacing.large),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LelloStoreBrandMark(contentDescription = null)
                    Spacer(Modifier.width(LelloStoreSpacing.large))
                    Column {
                        Text("LelloStore", style = MaterialTheme.typography.headlineSmall)
                        Text("Private apps, beautifully delivered", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(LelloStoreSpacing.small)) {
                    LelloStoreStatusBadge("Installed")
                    LelloStoreStatusBadge("Update available", emphasized = true)
                }
                LelloStoreAppRow(
                    name = "Example app",
                    iconUrl = null,
                    iconContentDescription = "Example app icon",
                    supportingText = "Version 2.4 · Stable",
                    description = "A representative catalogue description.",
                    statusLabel = "Update",
                    statusEmphasized = true,
                    onClick = {},
                )
                LelloStoreStateContent(
                    icon = Icons.Default.Refresh,
                    title = "Everything is up to date",
                    message = "Check again whenever you like.",
                    actionLabel = "Check now",
                    onAction = {},
                )
            }
        }
    }
}
