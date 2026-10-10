package com.naeblis11.mealplanner.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

/** The repo's icon.png, scaled to 64 x 64 for the tray and the title bar (Task 3 of plan 3 made it with Pillow). */
const val ICON_RESOURCE = "meal-planner-icon.png"

fun appIconBitmap(): ImageBitmap {
    val bytes = WindowShell::class.java.getResourceAsStream("/$ICON_RESOURCE")?.use { it.readBytes() }
        ?: error("$ICON_RESOURCE is missing from the app")
    return Image.makeFromEncoded(bytes).toComposeImageBitmap()
}

fun appIcon(): Painter = BitmapPainter(appIconBitmap())
