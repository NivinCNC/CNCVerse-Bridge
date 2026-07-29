package com.cncverse.stremiobridge.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource

@Composable
actual fun logoPainter(): Painter {
    return painterResource("logo.png")
}
