package com.cncverse.stremiobridge.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource

@Composable
actual fun logoPainter(): Painter {
    val context = LocalContext.current
    val resId = context.resources.getIdentifier("logo", "drawable", context.packageName)
    return painterResource(if (resId != 0) resId else android.R.drawable.sym_def_app_icon)
}
