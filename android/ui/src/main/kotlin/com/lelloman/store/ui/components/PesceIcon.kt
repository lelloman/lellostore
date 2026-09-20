package com.lelloman.store.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val PesceIcon: ImageVector = ImageVector.Builder("Pesce", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(2f, 4f); lineTo(6f, 7f)
        curveTo(10f, 2f, 16f, 3f, 21f, 7f)
        curveTo(16f, 11f, 10f, 12f, 6f, 8f)
        lineTo(2f, 11f); close()
        moveTo(22f, 13f); lineTo(18f, 16f)
        curveTo(14f, 11f, 8f, 12f, 3f, 16f)
        curveTo(8f, 20f, 14f, 21f, 18f, 17f)
        lineTo(22f, 20f); close()
    }
}.build()
