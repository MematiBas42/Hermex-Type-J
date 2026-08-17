package io.github.jcartu.hermex.ui.feature.onboarding

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Port of the five onboarding pages (`OnboardingWelcomePage`,
 * `OnboardingFeaturesPage`, `OnboardingAgentPromptPage`,
 * `OnboardingTailscalePage`, `OnboardingConnectPage`). iOS-only copy
 * ("iPhone", App Store) is adapted to Android; everything else is verbatim.
 */

@Composable
internal fun OnboardingWelcomePage(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(220.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                OnboardingAccent.copy(alpha = 0.35f),
                                Color(0xFFFF9E14).copy(alpha = 0.12f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(124.dp)
                        .background(OnboardingAccent, RoundedCornerShape(27.dp))
                        .border(
                            1.dp,
                            Brush.linearGradient(
                                listOf(Color.White.copy(alpha = 0.25f), Color.White.copy(alpha = 0.04f)),
                            ),
                            RoundedCornerShape(27.dp),
                        ),
                ) {
                    Icon(
                        Icons.Filled.Bolt,
                        contentDescription = "Hermex",
                        tint = Color.Black,
                        modifier = Modifier.size(64.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Control your Hermes agent from your phone or tablet.",
                color = Color.White,
                fontSize = 31.sp,
                lineHeight = 37.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Connect to your self-hosted Web UI over Tailscale.",
                color = Color.White.copy(alpha = 0.58f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroBadge(Icons.Filled.Lock, "Password protected")
                HeroBadge(Icons.Filled.Lan, "Tailscale ready")
            }
        }
    }
}

@Composable
internal fun OnboardingFeaturesPage(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp)
            .padding(top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(36.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("What you get", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(
                "Your Hermes agent, reachable from your phone over Tailscale.",
                color = Color.White.copy(alpha = 0.45f),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OnboardingFeatureList()
        }
    }
}

@Composable
internal fun OnboardingFeatureList() {
    OnboardingFeatureRow(
        Icons.AutoMirrored.Filled.Chat,
        OnboardingAccent,
        "Chat with your Hermes agent from your phone",
        "Drive conversations from anywhere on your tailnet.",
    )
    OnboardingFeatureRow(
        Icons.Filled.Checklist,
        Color(0xFF34C759),
        "Manage sessions, tasks, and files remotely",
        "Browse workspaces and stay on top of agent work.",
    )
    OnboardingFeatureRow(
        Icons.Filled.Mic,
        Color(0xFFAF52DE),
        "Voice input and mobile-friendly composer controls",
        "Compose naturally with touch-first controls.",
    )
    OnboardingFeatureRow(
        Icons.Filled.VerifiedUser,
        Color(0xFF32ADE6),
        "Review approvals and clarifications inline",
        "Respond to agent prompts without switching apps.",
    )
    OnboardingFeatureRow(
        Icons.Filled.Dns,
        Color(0xFFFF9500),
        "Self-hosted: your machine, your tailnet",
        "Your Hermes Web UI stays on hardware you control.",
    )
}

@Composable
internal fun OnboardingAgentPromptPage(
    onPromptCopied: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp)
            .padding(top = 24.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        OnboardingStepHeader(
            stepNumber = 1,
            icon = Icons.Filled.Terminal,
            title = "Set up Hermes Web UI",
            description = "Send this prompt to your Hermes Agent. It audits existing state, keeps Hermes Web UI on localhost, and configures private HTTPS with Tailscale Serve.",
        )

        OnboardingAgentPromptCard(
            prompt = OnboardingFlowPolicy.agentSetupPrompt,
            onCopied = onPromptCopied,
        )
    }
}

@Composable
internal fun OnboardingTailscalePage(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp)
            .padding(top = 24.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        OnboardingStepHeader(
            stepNumber = 2,
            icon = Icons.Filled.PhoneAndroid,
            title = "Install Tailscale on your phone",
            description = "Install Tailscale on this device and sign into the same tailnet as your server. Your agent will reply with the exact URL to use on the next screen.",
        )

        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OnboardingNumberedStep("1", "Install Tailscale from the Play Store.")
            OnboardingNumberedStep("2", "Sign in with the same account you used on your server.")
            OnboardingNumberedStep("3", "Keep Tailscale connected while using Hermex.")

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                    .clickable { openTailscaleInPlayStore(context) }
                    .padding(horizontal = 14.dp, vertical = 13.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    tint = OnboardingAccent,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    "Get Tailscale on the Play Store",
                    color = OnboardingAccent,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

private fun openTailscaleInPlayStore(context: android.content.Context) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, OnboardingFlowPolicy.tailscalePlayStoreUri.toUri()),
        )
    } catch (_: ActivityNotFoundException) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, OnboardingFlowPolicy.tailscalePlayStoreFallbackUrl.toUri()),
        )
    }
}

@Composable
internal fun OnboardingConnectPage(
    uiState: OnboardingUiState,
    onServerUrlChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp)
            .padding(top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Connect", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Enter the exact HTTPS Tailscale Serve URL your agent returned, for example https://server.tailnet-name.ts.net.",
                color = Color.White.copy(alpha = 0.5f),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OnboardingField(icon = Icons.Filled.Link, title = "Server URL") {
                OnboardingTextField(
                    value = uiState.serverUrl,
                    onValueChange = onServerUrlChange,
                    placeholder = "https://server.tailnet-name.ts.net",
                    keyboardType = KeyboardType.Uri,
                    onSubmit = onSubmit,
                )
            }

            if (uiState.isPasswordRequired) {
                OnboardingField(icon = Icons.Filled.Key, title = "Password") {
                    OnboardingTextField(
                        value = uiState.password,
                        onValueChange = onPasswordChange,
                        placeholder = "Server password",
                        keyboardType = KeyboardType.Password,
                        secure = true,
                        onSubmit = onSubmit,
                    )
                }
            }
        }

        if (uiState.isWorking) {
            OnboardingStatusBanner(
                text = "Checking server...",
                icon = Icons.Filled.Lan,
                tint = Color.White.copy(alpha = 0.7f),
                showsProgress = true,
            )
        }

        uiState.connectionMessage?.let { message ->
            OnboardingStatusBanner(text = message, icon = Icons.Filled.CheckCircle, tint = OnboardingSuccess)
        }

        uiState.errorMessage?.let { message ->
            OnboardingStatusBanner(text = message, icon = Icons.Filled.Warning, tint = OnboardingError)
        }
    }
}

@Composable
private fun OnboardingTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType,
    secure: Boolean = false,
    onSubmit: () -> Unit,
) {
    Box {
        if (value.isEmpty()) {
            Text(
                placeholder,
                color = Color.White.copy(alpha = 0.38f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            ),
            cursorBrush = SolidColor(OnboardingAccent),
            visualTransformation = if (secure) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = ImeAction.Go,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onGo = { onSubmit() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
