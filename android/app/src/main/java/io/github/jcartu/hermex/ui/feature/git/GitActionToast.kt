package io.github.jcartu.hermex.ui.feature.git

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Port of `GitActionToastOverlay.swift`, including Reduce Motion handling (#1.7.0). */
@Composable
internal fun GitActionToast(
    message: String?,
    isError: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val reduceMotion = remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
    LaunchedEffect(message) {
        if (message != null) {
            delay(if (isError) 5_000 else 3_000)
            onDismiss()
        }
    }
    val enter = if (reduceMotion) EnterTransition.None else fadeIn() + slideInVertically { it / 2 }
    val exit = if (reduceMotion) ExitTransition.None else fadeOut() + slideOutVertically { it / 2 }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(visible = message != null, enter = enter, exit = exit) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.inverseSurface,
                shadowElevation = if (reduceMotion) 0.dp else 6.dp,
                modifier = Modifier.padding(16.dp),
            ) {
                Text(
                    message.orEmpty(),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                    color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
