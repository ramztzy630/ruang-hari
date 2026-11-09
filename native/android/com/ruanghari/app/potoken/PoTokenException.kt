// Diadaptasi dari Metrolist (app/.../utils/potoken), GPL-3.0. Diubah untuk Ruang Hari (9 Nov 2026):
// package diganti; ketergantungan ke YouTube.proxy dan BuildConfig Metrolist dihapus.
package com.ruanghari.app.potoken

class PoTokenException(message: String) : Exception(message)

// to be thrown if the WebView provided by the system is broken
class BadWebViewException(message: String) : Exception(message)

fun buildExceptionForJsError(error: String): Exception {
    return if (error.contains("SyntaxError"))
        BadWebViewException(error)
    else
        PoTokenException(error)
}
