package com.naeblis11.mealplanner.desktop.sync

import com.naeblis11.mealplanner.settings.QrModules
import io.nayuki.qrcodegen.QrCode

/** [text] as a QR code's modules (error correction M), for Settings to draw. Desktop only: the APK never carries qrcodegen. */
fun qrModules(text: String): QrModules {
    val code = QrCode.encodeText(text, QrCode.Ecc.MEDIUM)
    val size = code.size
    // getModule(x, y): x is the column, y the row, as QrModules.isDark has them.
    return QrModules(size, BooleanArray(size * size) { i -> code.getModule(i % size, i / size) })
}
