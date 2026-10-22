package io.github.jcartu.hermex.ui.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jcartu.hermex.HermexApplication
import io.github.jcartu.hermex.ui.feature.onboarding.account
import kotlinx.coroutines.launch

/**
 * The avatar's long-press switcher (#283): every configured server with the
 * active one checked, then "Add Server…". Mount inside the Box that holds the
 * avatar and flip [expanded] on long-press; a tap switches the active server
 * (`AuthManager.switchActiveServer`, the Settings switcher's path) and the
 * signed-in shell is rebuilt for it.
 */
@Composable
fun ServerSwitcherMenu(expanded: Boolean, onDismiss: () -> Unit, onAddServer: () -> Unit) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as HermexApplication).container }
    val servers by container.authManager.servers.collectAsStateWithLifecycle()
    val authState by container.authManager.state.collectAsStateWithLifecycle()
    val activeId = authState.account?.id
    val scope = rememberCoroutineScope()

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        servers.forEach { account ->
            val isActive = account.id == activeId
            DropdownMenuItem(
                text = {
                    Column {
                        Text(ServerIdentity.displayName(account), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            ServerIdentity.hostFallback(account),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                leadingIcon = {
                    ServerAvatarBadge(
                        initials = ServerIdentity.previewInitials(account),
                        colorHex = account.headerLogoColorHex,
                        size = 28.dp,
                    )
                },
                trailingIcon = if (isActive) {
                    { Icon(Icons.Filled.Check, contentDescription = "Active server", modifier = Modifier.size(18.dp)) }
                } else {
                    null
                },
                onClick = {
                    onDismiss()
                    if (!isActive) scope.launch { container.authManager.switchActiveServer(account) }
                },
            )
        }
        if (servers.isNotEmpty()) HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Add Server…") },
            leadingIcon = { Icon(Icons.Filled.AddCircleOutline, contentDescription = null, modifier = Modifier.size(22.dp)) },
            onClick = {
                onDismiss()
                onAddServer()
            },
        )
    }
}
