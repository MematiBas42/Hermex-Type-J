package io.github.jcartu.hermex.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Web links open in an in-app browser (1.8.0, the iOS `SFSafariViewController`
 * sheet): a full-screen WebView route drawn by [InAppBrowserHost] inside the
 * app window — above every screen, below the app lock — so the lock still
 * covers it and `FLAG_SECURE` still applies. [open] is the one entry point for
 * links across the app; anything that isn't `http(s)` (mailto:, tel:, custom
 * schemes) is handed to the system instead.
 */
object InAppBrowser {
    private val _current = MutableStateFlow<String?>(null)

    /** The URL on screen, or null while the browser is closed. */
    val current: StateFlow<String?> = _current.asStateFlow()

    fun open(context: Context, url: String) {
        val trimmed = url.trim()
        if (trimmed.toHttpUrlOrNull() == null) {
            openExternally(context, trimmed)
            return
        }
        _current.value = trimmed
    }

    fun close() {
        _current.value = null
    }

    /** The system browser (the "Open in Browser" action, and every non-web scheme). */
    fun openExternally(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Nothing handles the scheme; the link is simply inert, as on iOS.
        }
    }
}

/** Draws the browser route whenever a link is open; mount once at the window level. */
@Composable
fun InAppBrowserHost() {
    val url by InAppBrowser.current.collectAsStateWithLifecycle()
    val open = url ?: return
    InAppBrowserScreen(url = open, onClose = InAppBrowser::close)
}
