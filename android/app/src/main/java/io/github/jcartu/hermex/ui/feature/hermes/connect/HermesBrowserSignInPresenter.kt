package io.github.jcartu.hermex.ui.feature.hermes.connect

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import io.github.jcartu.hermex.MainActivity
import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.net.hermes.HermesBrowserSignIn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import java.lang.ref.WeakReference
import java.util.UUID

/**
 * Android presenter for `Networking/Hermes/HermesBrowserSignIn.swift` (#708): the transport
 * owns the loopback listener, state and PKCE. This Activity-backed presenter only opens the
 * authorize URL and returns when the browser closes or bounces to `hermexsignin://done`.
 * No connection headers or credentials enter the browser intent. A browser returning without
 * a code is rejected by `HermesBrowserSignIn.signIn` with BrowserSignInFailed.
 */
class HermesBrowserSignInPresenter(private val context: Context) : HermesBrowserSignIn.Presenter {
    override suspend fun present(url: HttpUrl) = withContext(Dispatchers.Main.immediate) {
        val id = UUID.randomUUID().toString()
        val request = BrowserPresentation()
        BrowserPresentations.requests[id] = request
        try {
            context.startActivity(Intent(context, HermesSignInActivity::class.java).apply {
                action = HermesSignInActivity.START
                putExtra(HermesSignInActivity.REQUEST, id)
                putExtra(HermesSignInActivity.URL, url.toString())
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            request.finished.await()
        } catch (error: android.content.ActivityNotFoundException) {
            throw BotFailure.BrowserSignInFailed
        } finally {
            BrowserPresentations.requests.remove(id)
            request.activity?.get()?.finish()
        }
    }
}

@Composable
fun rememberHermesBrowserSignInPresenter(): HermesBrowserSignIn.Presenter {
    val context = LocalContext.current
    return remember(context) { HermesBrowserSignInPresenter(context) }
}

private class BrowserPresentation {
    val finished = CompletableDeferred<Unit>()
    var activity: WeakReference<Activity>? = null
}

/** Main-thread-only rendezvous; authorize URLs, codes and tokens are never persisted here. */
private object BrowserPresentations {
    val requests = mutableMapOf<String, BrowserPresentation>()
}

/**
 * Android counterpart of the Swift authentication sheet. Explicit START launches ACTION_VIEW;
 * the exported callback accepts only `hermexsignin://done`, never a code. The loopback listener
 * has already checked state and retained the code before sending that bounce. The callback
 * brings the existing task forward; Back from a browser returns without a code and fails closed.
 */
class HermesSignInActivity : Activity() {
    private var launched = false
    private var requestID: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestID = savedInstanceState?.getString(REQUEST) ?: intent.getStringExtra(REQUEST)
        launched = savedInstanceState?.getBoolean(LAUNCHED) ?: false
        if (isCallback(intent)) {
            finishPresentation()
            return
        }
        val pending = requestID?.let(BrowserPresentations.requests::get)
        if (intent.action != START || pending == null) { finish(); return }
        pending.activity = WeakReference(this)
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        if (launched) { finishPresentation(); return }
        launched = true
        val url = intent.getStringExtra(URL) ?: run { finishPresentation(); return }
        try {
            // These standard Custom Tab extras are ignored by a plain browser. No androidx.browser
            // dependency is needed and devices without Custom Tabs still use ordinary ACTION_VIEW.
            val extras = Bundle().apply { putBinder("android.support.customtabs.extra.SESSION", null) }
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                putExtras(extras)
                putExtra("android.support.customtabs.extra.TITLE_VISIBILITY", 1)
                putExtra("android.support.customtabs.extra.ENABLE_URLBAR_HIDING", true)
            })
        } catch (error: android.content.ActivityNotFoundException) {
            requestID?.let(BrowserPresentations.requests::get)?.finished?.completeExceptionally(BotFailure.BrowserSignInFailed)
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (isCallback(intent)) finishPresentation()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(REQUEST, requestID)
        outState.putBoolean(LAUNCHED, launched)
        super.onSaveInstanceState(outState)
    }

    private fun finishPresentation() {
        requestID?.let(BrowserPresentations.requests::get)?.finished?.complete(Unit)
        // A callback after process death contains no credential: return to the normal app,
        // whose lost attempt can only be retried from its sign-in form.
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    private fun isCallback(intent: Intent): Boolean = intent.action == Intent.ACTION_VIEW &&
        intent.data?.scheme == HermesBrowserSignIn.CALLBACK_SCHEME && intent.data?.host == "done"

    companion object {
        internal const val START = "io.github.jcartu.hermex.HERMES_SIGN_IN"
        internal const val REQUEST = "hermes_sign_in_request"
        internal const val URL = "hermes_authorize_url"
        private const val LAUNCHED = "hermes_browser_launched"
    }
}
