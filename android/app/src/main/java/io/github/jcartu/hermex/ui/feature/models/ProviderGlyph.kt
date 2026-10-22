package io.github.jcartu.hermex.ui.feature.models

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Compact provider identity shared by the composer and model picker.
 * Port of the provider glyph treatment used by
 * `ChatComposerSelectorControls.swift` and `ModelPickerSheet.swift`.
 */
@Composable
fun ProviderGlyph(
    providerId: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val spec = providerGlyphSpec(providerId)
    Surface(
        modifier = modifier.size(24.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (spec.icon != null) {
                Icon(
                    imageVector = spec.icon,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(15.dp),
                )
            } else {
                Text(
                    text = spec.fallbackLetter,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

private data class ProviderGlyphSpec(
    val icon: ImageVector?,
    val fallbackLetter: String,
)

private fun providerGlyphSpec(providerId: String?): ProviderGlyphSpec {
    val normalized = providerId?.trim()?.lowercase().orEmpty()
    val icon = when {
        normalized.contains("anthropic") || normalized.contains("claude") -> Icons.Filled.Psychology
        normalized.contains("google") || normalized.contains("gemini") -> Icons.Filled.AutoAwesome
        normalized.contains("openai") || normalized.contains("gpt") -> Icons.Filled.Hub
        normalized.contains("local") || normalized.contains("ollama") || normalized.contains("lmstudio") -> Icons.Filled.Memory
        normalized.contains("azure") || normalized.contains("bedrock") || normalized.contains("vertex") -> Icons.Filled.Cloud
        else -> null
    }
    val letter = normalized.firstOrNull()?.uppercase() ?: "M"
    return ProviderGlyphSpec(icon = icon, fallbackLetter = letter)
}
