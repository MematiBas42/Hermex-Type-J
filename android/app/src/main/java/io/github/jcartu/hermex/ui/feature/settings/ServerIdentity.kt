package io.github.jcartu.hermex.ui.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.jcartu.hermex.ui.feature.onboarding.ConnectFormStyle
import io.github.jcartu.hermex.ui.feature.onboarding.ConnectTextField
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jcartu.hermex.model.ServerAccount
import java.net.URI

/**
 * Port of `SessionIdentitySettings` (`Features/SessionList/SessionRowView.swift`):
 * the pure initials rules the server avatar uses.
 */
object ServerIdentity {
    /** `normalizedInitials`: letters and digits only, at most three, uppercased. */
    fun normalizedInitials(rawValue: String): String =
        rawValue.filter { it.isLetterOrDigit() }.take(3).uppercase()

    /** `displayInitials`: stored initials, else the display name's, else the fallback's, else "UZ". */
    fun displayInitials(displayName: String, storedInitials: String, fallbackFullName: String): String {
        normalizedInitials(storedInitials).takeIf { it.isNotEmpty() }?.let { return it }
        initialsFrom(displayName).takeIf { it.isNotEmpty() }?.let { return it }
        return initialsFrom(fallbackFullName).ifEmpty { "UZ" }
    }

    /** `initials(from:)` — first letter of each word, max 2. */
    private fun initialsFrom(rawName: String): String =
        rawName.split(SEPARATORS)
            .filter { it.isNotEmpty() }
            .mapNotNull { it.firstOrNull() }
            .take(2)
            .joinToString("") { it.toString().uppercase() }

    private val SEPARATORS = Regex("(?U)[\\s\\-_]+")

    /** `URL(string:)?.host ?? urlString` — the server page's and rows' fallback name. */
    fun hostFallback(account: ServerAccount): String =
        runCatching { URI(account.urlString).host }.getOrNull()?.takeIf { it.isNotEmpty() } ?: account.urlString

    fun displayName(account: ServerAccount): String =
        account.displayName.ifEmpty { hostFallback(account) }

    fun previewInitials(account: ServerAccount): String = displayInitials(
        displayName = account.displayName,
        storedInitials = account.initials,
        fallbackFullName = hostFallback(account),
    )
}

/** Port of `ServerAvatarBadge`: a tinted circle with the server's initials. */
@Composable
fun ServerAvatarBadge(initials: String, colorHex: String, size: Dp = 32.dp, modifier: Modifier = Modifier) {
    val background = HeaderLogoColor.color(colorHex)
    val foreground = if (HeaderLogoColor.prefersDarkForeground(colorHex)) Color.Black else Color.White
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(size).background(background, CircleShape),
    ) {
        Text(
            initials,
            color = foreground,
            fontSize = (size.value * 0.38f).sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/**
 * Port of `ServerIdentityEditor` (`Features/Settings/AddServerView.swift`, #17):
 * display name, initials and the Header Logo Color, with a live avatar preview.
 * Used by the add-server flow and the server page.
 */
@Composable
fun ServerIdentityEditor(
    displayName: String,
    initials: String,
    colorHex: String,
    fallbackName: String,
    onDisplayNameChange: (String) -> Unit,
    onInitialsChange: (String) -> Unit,
    onColorHexChange: (String) -> Unit,
    enabled: Boolean = true,
) {
    val previewInitials = ServerIdentity.displayInitials(
        displayName = displayName.ifEmpty { fallbackName },
        storedInitials = initials,
        fallbackFullName = fallbackName,
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ServerAvatarBadge(initials = previewInitials, colorHex = colorHex, size = 36.dp)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Server Avatar", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    "Stored on this device only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text("Display Name", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ConnectTextField(
            value = displayName,
            onValueChange = onDisplayNameChange,
            placeholder = fallbackName.ifEmpty { "Server" },
            style = ConnectFormStyle.SETTINGS,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Initials", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ConnectTextField(
            value = initials,
            onValueChange = { onInitialsChange(ServerIdentity.normalizedInitials(it)) },
            placeholder = previewInitials,
            style = ConnectFormStyle.SETTINGS,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Header Logo Color", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                HeaderLogoColor.displayName(colorHex),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            val selectedHex = HeaderLogoColor.normalizedHex(colorHex)
            HeaderLogoColor.presets.forEach { preset ->
                HeaderColorSwatch(
                    preset = preset,
                    isSelected = preset.hex == selectedHex,
                    onClick = { if (enabled) onColorHexChange(preset.hex) },
                )
            }
        }
    }
}
