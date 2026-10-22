package io.github.jcartu.hermex.ui.feature.sessions

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.github.jcartu.hermex.model.SessionSummary

/**
 * What one session row is asking of the user, shown where the relative time
 * otherwise sits. `null` means no attention is pending: the row shows its
 * time, with an unread dot when a newer settled reply exists.
 *
 * Port of `SessionRowAttentionState` from
 * `Features/SessionList/SessionRowView.swift` (1.6.0 "Session rows show
 * Approval, Input, and Working states").
 */
enum class SessionRowAttentionState {
    APPROVAL,
    INPUT,
    WORKING,
    ;

    /** Port of `SessionRowAttentionState.title`. */
    val title: String
        get() = when (this) {
            APPROVAL -> "Approval"
            INPUT -> "Input"
            WORKING -> "Working"
        }

    /** Port of `SessionRowAttentionState.accessibilityLabel`. */
    val accessibilityLabel: String
        get() = when (this) {
            APPROVAL -> "Waiting for approval"
            INPUT -> "Needs input"
            WORKING -> "Working"
        }

    companion object {
        /**
         * Port of `SessionRowAttentionState.resolve(session:hasPendingApproval:hasPendingClarification:)`.
         *
         * Precedence: approval → input → working → ready. Pure by design — the
         * caller decides which sessions are worth asking the server about, so a
         * pending flag on a non-streaming session still resolves here.
         */
        fun resolve(
            session: SessionSummary,
            hasPendingApproval: Boolean,
            hasPendingClarification: Boolean,
        ): SessionRowAttentionState? = when {
            hasPendingApproval -> APPROVAL
            hasPendingClarification -> INPUT
            isActiveStreaming(session) -> WORKING
            else -> null
        }

        /**
         * Port of `SessionRowView.effectiveAttentionState(for:attentionState:isViewingCachedData:)`.
         *
         * The state the row actually shows: what the polling screen resolved, or —
         * for screens that do not poll — whatever the session alone can say. The
         * fallback is off while the row is showing cached data: a cached summary
         * keeps whatever `isStreaming`/`activeStreamId` it was captured with, so
         * an offline row would otherwise claim the agent is still working.
         */
        fun effectiveAttentionState(
            session: SessionSummary,
            attentionState: SessionRowAttentionState?,
            isViewingCachedData: Boolean,
        ): SessionRowAttentionState? {
            if (attentionState != null) return attentionState
            if (isViewingCachedData) return null
            return resolve(
                session = session,
                hasPendingApproval = false,
                hasPendingClarification = false,
            )
        }
    }
}

/**
 * Row-state tints. Port of the `AttentionApproval` / `AttentionInput` /
 * `AttentionWorking` color sets (`Resources/Assets.xcassets`, 1.6.0), carried
 * as fixed light/dark accent pairs picked from the active
 * [MaterialTheme.colorScheme] surface luminance; the unread dot reads the
 * scheme's `primary` (iOS `Color.accentColor`).
 */
data class SessionRowStateColors(
    val approval: Color,
    val input: Color,
    val working: Color,
    val unread: Color,
    val cached: Color,
) {
    companion object {
        /** `AttentionApproval` universal (light) — srgb 0xB4/0x53/0x09. */
        val ApprovalLight = Color(0xFFB45309)

        /** `AttentionApproval` dark appearance — srgb 0xFC/0xD3/0x4D. */
        val ApprovalDark = Color(0xFFFCD34D)

        /** `AttentionInput` universal (light) — srgb 0x4F/0x46/0xE5. */
        val InputLight = Color(0xFF4F46E5)

        /** `AttentionInput` dark appearance — srgb 0xA5/0xB4/0xFC. */
        val InputDark = Color(0xFFA5B4FC)

        /** `AttentionWorking` universal (light) — srgb 0x02/0x84/0xC7. */
        val WorkingLight = Color(0xFF0284C7)

        /** `AttentionWorking` dark appearance — srgb 0x38/0xBD/0xF8. */
        val WorkingDark = Color(0xFF38BDF8)

        /** Cached badge tint (iOS `.orange`), unchanged from the pre-port row. */
        val Cached = Color(0xFFFB923C)
    }
}

/** Resolves the row-state palette against the active theme (dark-first default). */
@Composable
@ReadOnlyComposable
fun sessionRowStateColors(): SessionRowStateColors {
    val scheme = MaterialTheme.colorScheme
    val isLight = scheme.surface.luminance() > 0.5f
    return SessionRowStateColors(
        approval = if (isLight) SessionRowStateColors.ApprovalLight else SessionRowStateColors.ApprovalDark,
        input = if (isLight) SessionRowStateColors.InputLight else SessionRowStateColors.InputDark,
        working = if (isLight) SessionRowStateColors.WorkingLight else SessionRowStateColors.WorkingDark,
        unread = scheme.primary,
        cached = SessionRowStateColors.Cached,
    )
}

/** The tint [SessionRowAttentionState] (port of `SessionRowAttentionState.tint`). */
fun SessionRowAttentionState.tint(colors: SessionRowStateColors): Color = when (this) {
    SessionRowAttentionState.APPROVAL -> colors.approval
    SessionRowAttentionState.INPUT -> colors.input
    SessionRowAttentionState.WORKING -> colors.working
}
