package io.github.jcartu.hermex.ui.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jcartu.hermex.core.prefs.ServerConfig
import io.github.jcartu.hermex.di.AppContainer
import io.github.jcartu.hermex.ui.adaptive.HingeAwarePaneRow
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount
import kotlinx.coroutines.launch

/**
 * Onboarding flow, port of `OnboardingView.swift`. Compact: full-screen pager.
 * Medium/expanded/trifold: [HingeAwarePaneRow] splits the window into the pager
 * (left) and a live setup-guide pane (right) with the boundary on the hinge,
 * so no interactive control ever straddles the fold.
 */
@Composable
fun OnboardingRoute(container: AppContainer, savedServer: ServerConfig?) {
    val viewModel: OnboardingViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                OnboardingViewModel(
                    authManager = container.authManager,
                    savedServer = savedServer,
                )
            }
        },
    )

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val paneCount = LocalPaneCount.current

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (paneCount >= 2) {
            HingeAwarePaneRow(paneCount = 2) { index, paneModifier ->
                when (index) {
                    0 -> OnboardingPagerColumn(viewModel, uiState, paneModifier)
                    else -> OnboardingGuidePane(uiState, paneModifier)
                }
            }
        } else {
            OnboardingPagerColumn(viewModel, uiState, Modifier.fillMaxSize())
        }
    }

    if (uiState.isShowingCopyReminder) {
        AlertDialog(
            onDismissRequest = viewModel::dismissCopyReminder,
            title = { Text("Copy the setup prompt first") },
            text = {
                Text(
                    "Copy the agent setup prompt on your desktop before continuing so Hermes Web UI and Tailscale are configured correctly.",
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::bypassCopyReminderAndAdvance) {
                    Text("Continue Anyway")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissCopyReminder) { Text("Stay Here") }
            },
        )
    }
}

@Composable
private fun OnboardingPagerColumn(
    viewModel: OnboardingViewModel,
    uiState: OnboardingUiState,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(
        initialPage = uiState.currentPage,
        pageCount = { OnboardingFlowPolicy.pageCount },
    )

    // ViewModel → pager (button navigation, copy-reminder rollback).
    LaunchedEffect(uiState.currentPage) {
        if (pagerState.currentPage != uiState.currentPage && !pagerState.isScrollInProgress) {
            pagerState.animateScrollToPage(uiState.currentPage)
        }
    }

    // Pager → ViewModel (swipes); an intercepted forward swipe from the agent
    // prompt page snaps back and raises the copy reminder, mirroring iOS.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { settled ->
            val resolved = viewModel.onPageChangeRequested(settled)
            if (resolved != settled) {
                pagerState.animateScrollToPage(resolved)
            }
        }
    }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.widthIn(max = 520.dp).fillMaxSize(),
            ) { page ->
                when (page) {
                    0 -> OnboardingWelcomePage()
                    1 -> OnboardingFeaturesPage()
                    2 -> OnboardingAgentPromptPage(onPromptCopied = viewModel::onAgentPromptCopied)
                    3 -> OnboardingTailscalePage()
                    else -> OnboardingConnectPage(
                        uiState = uiState,
                        onServerUrlChange = viewModel::onServerUrlChange,
                        onPasswordChange = viewModel::onPasswordChange,
                        onSubmit = { if (uiState.canSubmitConnection) viewModel.connect() },
                    )
                }
            }
        }

        OnboardingBottomBar(viewModel, uiState)
    }
}

@Composable
private fun OnboardingBottomBar(viewModel: OnboardingViewModel, uiState: OnboardingUiState) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        OnboardingPageIndicator(
            pageCount = OnboardingFlowPolicy.pageCount,
            currentPage = uiState.currentPage,
        )

        if (uiState.currentPage == OnboardingFlowPolicy.connectPageIndex) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OnboardingSecondaryButton(
                    text = "Test Connection",
                    icon = Icons.Filled.Lan,
                    enabled = !uiState.isWorking && uiState.canSubmitConnection,
                    modifier = Modifier.weight(1f),
                    onClick = viewModel::testConnection,
                )
                OnboardingPrimaryButton(
                    text = "Connect",
                    icon = Icons.Filled.CheckCircle,
                    enabled = !uiState.isWorking && uiState.canSubmitConnection,
                    modifier = Modifier.weight(1f),
                    onClick = viewModel::connect,
                )
            }
        } else {
            OnboardingPrimaryButton(
                text = OnboardingFlowPolicy.primaryButtonTitle(uiState.currentPage),
                onClick = viewModel::handlePrimaryAction,
            )

            if (OnboardingFlowPolicy.showsServerShortcut(uiState.currentPage)) {
                TextButton(onClick = viewModel::jumpToConnectPage) {
                    Text(
                        "Already have a server?",
                        color = Color.White.copy(alpha = 0.55f),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/**
 * Right-hand pane on medium+ layouts: a persistent setup guide with the current
 * step highlighted, plus contextual content (feature list on the intro pages,
 * connect status while connecting). Read-only by design — every interactive
 * control lives in the pager pane, clear of the hinge.
 */
@Composable
private fun OnboardingGuidePane(uiState: OnboardingUiState, modifier: Modifier = Modifier) {
    val steps = listOf(
        "Welcome to Hermex",
        "What you get",
        "Set up Hermes Web UI",
        "Install Tailscale",
        "Connect to your server",
    )

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 32.dp)
            .widthIn(max = 480.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                "Setup guide",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            steps.forEachIndexed { index, step ->
                val isCurrent = index == uiState.currentPage
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(23.dp)
                            .background(
                                if (isCurrent) OnboardingAccent else Color.White.copy(alpha = 0.12f),
                                CircleShape,
                            ),
                    ) {
                        Text(
                            "${index + 1}",
                            color = if (isCurrent) Color.Black else Color.White.copy(alpha = 0.6f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        step,
                        color = if (isCurrent) Color.White else Color.White.copy(alpha = 0.45f),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }

        when (uiState.currentPage) {
            0, 1 -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OnboardingFeatureList()
            }
            2 -> OnboardingStatusBanner(
                text = "Copy the prompt and hand it to the Hermes agent on your desktop. It replies with the exact HTTPS URL and password to enter on the last step.",
                icon = Icons.Filled.CheckCircle,
                tint = Color.White.copy(alpha = 0.7f),
            )
            3 -> OnboardingStatusBanner(
                text = "Your phone and the server must be on the same tailnet. Keep the Tailscale VPN connected while using Hermex.",
                icon = Icons.Filled.Lan,
                tint = Color.White.copy(alpha = 0.7f),
            )
            else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OnboardingStatusBanner(
                    text = "Use the exact HTTPS Tailscale Serve URL your agent returned, for example https://server.tailnet-name.ts.net.",
                    icon = Icons.Filled.Lan,
                    tint = Color.White.copy(alpha = 0.7f),
                )
                if (uiState.authStatus?.authEnabled == false) {
                    OnboardingStatusBanner(
                        text = "This server does not require a password.",
                        icon = Icons.Filled.CheckCircle,
                        tint = OnboardingSuccess,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}
