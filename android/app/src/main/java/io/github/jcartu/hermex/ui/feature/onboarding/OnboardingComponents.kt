package io.github.jcartu.hermex.ui.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Port of `OnboardingComponents.swift`, adapted to the user's palette and light/dark theme. */
internal val OnboardingAccent: Color @Composable get() = MaterialTheme.colorScheme.primary
internal val OnboardingSuccess: Color @Composable get() = MaterialTheme.colorScheme.tertiary
internal val OnboardingError: Color @Composable get() = MaterialTheme.colorScheme.error

@Composable
internal fun HeroBadge(icon: ImageVector, title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f), CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.68f), modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            title,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.68f),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
internal fun OnboardingStepHeader(
    stepNumber: Int,
    icon: ImageVector,
    title: String,
    description: String,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(80.dp)
                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f), RoundedCornerShape(22.dp))
                .border(1.dp, OnboardingAccent.copy(alpha = 0.35f), RoundedCornerShape(22.dp)),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(30.dp))
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "STEP $stepNumber",
                color = OnboardingAccent.copy(alpha = 0.8f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
            )
            Text(
                title,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                description,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
internal fun OnboardingStatusBanner(
    text: String,
    icon: ImageVector,
    tint: Color,
    showsProgress: Boolean = false,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(tint.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
            .border(1.dp, tint.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        if (showsProgress) {
            CircularProgressIndicator(color = tint, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
        } else {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        }
        Text(
            text,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.76f),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
internal fun OnboardingField(
    icon: ImageVector,
    title: String,
    content: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f), RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
            .padding(horizontal = 13.dp, vertical = 12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = OnboardingAccent, modifier = Modifier.size(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            Text(
                title,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                style = MaterialTheme.typography.labelMedium,
            )
            content()
        }
    }
}

@Composable
internal fun OnboardingPageIndicator(pageCount: Int, currentPage: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(pageCount) { index ->
            val selected = index == currentPage
            Box(
                Modifier
                    .width(if (selected) 24.dp else 8.dp)
                    .height(8.dp)
                    .background(
                        MaterialTheme.colorScheme.onBackground.copy(alpha = if (selected) 1f else 0.18f),
                        CircleShape,
                    ),
            )
        }
    }
}

@Composable
internal fun OnboardingPrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = OnboardingAccent,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = OnboardingAccent.copy(alpha = 0.4f),
            disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f),
        ),
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
internal fun OnboardingSecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.065f),
            contentColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.84f),
            disabledContainerColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.04f),
            disabledContentColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
        ),
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
internal fun OnboardingFeatureRow(
    icon: ImageVector,
    color: Color,
    title: String,
    subtitle: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .background(color.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
internal fun OnboardingNumberedStep(number: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(23.dp).background(OnboardingAccent, CircleShape),
        ) {
            Text(number, color = MaterialTheme.colorScheme.onPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            text,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Port of `OnboardingAgentPromptCard`: scrollable monospaced prompt + copy button. */
@Composable
internal fun OnboardingAgentPromptCard(
    prompt: String,
    onCopied: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var didCopyRecently by remember { mutableStateOf(false) }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.055f), RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        Box(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
            Text(
                prompt,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.82f),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }

        OnboardingPrimaryButton(
            text = if (didCopyRecently) "Copied" else "Copy prompt",
            icon = if (didCopyRecently) Icons.Filled.Check else Icons.Filled.ContentCopy,
        ) {
            clipboard.setText(AnnotatedString(prompt))
            didCopyRecently = true
            onCopied()
        }
    }
}
