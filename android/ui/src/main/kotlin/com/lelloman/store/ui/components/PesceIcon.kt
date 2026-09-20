package com.lelloman.store.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

// Approved "Staggered" design; matches docs/design/p2p-staggered.svg and ic_pesce.xml.
val PesceIcon: ImageVector = ImageVector.Builder("P2P", 24.dp, 24.dp, 24f, 24f).apply {
    path(
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(2f, 3.5f)
        curveTo(7.4f, 11.5f, 14.1f, 12f, 20f, 7f)
        curveTo(14.1f, 2f, 7.4f, 2.5f, 2f, 10.5f)
        moveTo(22f, 20.5f)
        curveTo(16.6f, 12.5f, 9.9f, 12f, 4f, 17f)
        curveTo(9.9f, 22f, 16.6f, 21.5f, 22f, 13.5f)
    }
}.build()
