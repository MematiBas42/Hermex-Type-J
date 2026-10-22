package io.github.jcartu.hermex.core.security

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/** The glyph beside Unlock and on the Settings toggle (`AppLockCapability.Method.systemImage`). */
val AppLockCapability.Method.icon: ImageVector
    get() = when (this) {
        AppLockCapability.Method.FACE -> Icons.Filled.Face
        AppLockCapability.Method.FINGERPRINT -> Icons.Filled.Fingerprint
        AppLockCapability.Method.DEVICE_CREDENTIAL -> Icons.Filled.Lock
    }

/**
 * Port of `AppLockView` (`Auth/AppLockWindow.swift`): the lock, or the "can't
 * lock" note, while the activity is active, and the app mark alone whenever it's
 * covered, so a paused window never shows the Unlock button. Drawn by
 * `MainActivity` above the whole app when [AppLock.State.showsLockOverlay] is
 * true; it covers sheets and dialogs because it sits in the same window above
 * them. Static: nothing animates. Works at any width — the content column is
 * capped, never full-bleed, on tablets and the unfolded Trifold.
 */
@Composable
fun AppLockOverlay(lock: AppLock, modifier: Modifier = Modifier) {
    val state by lock.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp)
                .padding(top = 160.dp, bottom = 24.dp),
        ) {
            AppLockMark()

            // Covered, it's the mark alone.
            if (!state.isCovered) {
                when {
                    state.isPasscodeMissing -> PasscodeMissing(onContinue = lock::continueWithoutPasscode)
                    state.isLocked -> Locked(
                        method = state.capability.method,
                        enabled = !state.isAuthenticating,
                        onUnlock = { scope.launch { lock.unlock() } },
                    )
                }
            }
        }
    }
}

@Composable
private fun AppLockMark() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(84.dp)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), RoundedCornerShape(22.dp)),
    ) {
        Icon(
            Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(36.dp),
        )
    }
}

@Composable
private fun Locked(method: AppLockCapability.Method, enabled: Boolean, onUnlock: () -> Unit) {
    LockTitle("Hermex is locked")
    Spacer(Modifier.height(20.dp))
    Button(onClick = onUnlock, enabled = enabled) {
        Icon(method.icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(method.unlockTitle)
    }
}

@Composable
private fun PasscodeMissing(onContinue: () -> Unit) {
    LockTitle("Hermex can’t lock")
    Spacer(Modifier.height(8.dp))
    Text(
        "This device no longer has a screen lock, so the lock is off. Set a screen lock, then turn the lock back on in Settings.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = 310.dp),
    )
    Spacer(Modifier.height(20.dp))
    Button(onClick = onContinue) { Text("Continue") }
}

@Composable
private fun LockTitle(text: String) {
    Column(verticalArrangement = Arrangement.Top, horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(20.dp))
        Text(
            text,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
    }
}
