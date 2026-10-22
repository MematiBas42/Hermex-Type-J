package io.github.jcartu.hermex.ui.feature.sessions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The session list's header (#283): HERMEX, and one pill of search and the
 * server's avatar. A tap on the avatar opens Settings and a hold switches
 * servers ([onSwitchServer] — implemented by the settings slice).
 *
 * Port of `SessionsHeader` from `Features/SessionList/SessionListComponents.swift`.
 * With search expanded the pill grows a text field that pushes the wordmark
 * out and turns the avatar into Close; collapsed, the search icon alone runs
 * [onOpenSearch]. The fork renders the wordmark as text in the header accent
 * color (the upstream image-mask logo has no assets in this fork).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionsHeader(
    logoColor: Color,
    avatarInitials: String,
    avatarColor: Color,
    avatarForeground: Color,
    isSearchExpanded: Boolean,
    searchText: String,
    onSearchTextChange: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onSwitchServer: () -> Unit,
    modifier: Modifier = Modifier,
    isSearchDisabled: Boolean = false,
    /**
     * Hosts the server-switcher menu (`ServerSwitcherMenu`, owned by the
     * settings slice) anchored to the avatar. The caller flips its expanded
     * state from [onSwitchServer]; empty by default.
     */
    avatarMenu: @Composable () -> Unit = {},
) {
    val searchFocusRequester = remember { FocusRequester() }

    LaunchedEffect(isSearchExpanded) {
        if (isSearchExpanded) searchFocusRequester.requestFocus()
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 20.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!isSearchExpanded) {
            Text(
                text = "HERMEX",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                letterSpacing = MaterialTheme.typography.headlineSmall.letterSpacing * 2,
                color = logoColor,
                maxLines = 1,
            )
            Spacer(Modifier.width(16.dp))
        }

        // The glass pill: search icon | growing field | avatar (or Close).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .then(if (isSearchExpanded) Modifier.fillMaxWidth() else Modifier)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
                .padding(2.dp),
        ) {
            IconButton(
                onClick = { if (isSearchExpanded) searchFocusRequester.requestFocus() else onOpenSearch() },
                enabled = !isSearchDisabled,
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = "Search sessions",
                    tint = if (isSearchExpanded) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }

            if (isSearchExpanded) {
                BasicTextField(
                    value = searchText,
                    onValueChange = onSearchTextChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(searchFocusRequester),
                )

                if (searchText.isNotEmpty()) {
                    IconButton(onClick = { onSearchTextChange("") }) {
                        Icon(
                            imageVector = Icons.Filled.Clear,
                            contentDescription = "Clear search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                IconButton(onClick = onCloseSearch) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close search",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            } else {
                // The server's avatar: tap opens Settings, hold switches servers (#283).
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 4.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(avatarColor)
                            .combinedClickable(
                                onClick = onOpenSettings,
                                onLongClick = onSwitchServer,
                            ),
                    ) {
                        Text(
                            text = avatarInitials,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = avatarForeground,
                            maxLines = 1,
                        )
                    }
                    // Anchored to the avatar: the switcher menu (settings slice).
                    avatarMenu()
                }
            }
        }
    }
}

/**
 * The avatar's initials, port of `SessionIdentitySettings.displayInitials`
 * (`Features/SessionList/SessionRowView.swift`): stored initials when the user
 * set them (letters/digits, max 3, uppercased), else the display name's
 * initials, else the server host's, else "UZ".
 */
fun sessionAvatarInitials(
    displayName: String,
    storedInitials: String,
    fallbackName: String,
): String {
    val normalizedStored = normalizeSessionInitials(storedInitials)
    if (normalizedStored.isNotEmpty()) return normalizedStored

    val displayNameInitials = initialsFromName(displayName)
    if (displayNameInitials.isNotEmpty()) return displayNameInitials

    val fallbackInitials = initialsFromName(fallbackName)
    return fallbackInitials.ifEmpty { "UZ" }
}

/** Port of `SessionIdentitySettings.normalizedInitials`. */
fun normalizeSessionInitials(rawValue: String): String =
    rawValue
        .filter { it.isLetterOrDigit() }
        .take(3)
        .uppercase()

/** Port of `SessionIdentitySettings.initials(from:)`: first letter of each word, max 2. */
private fun initialsFromName(rawName: String): String =
    rawName
        .split(' ', '-', '_')
        .filter { it.isNotEmpty() }
        .take(2)
        .map { it.first().uppercaseChar() }
        .joinToString("")
        .filter { it.isLetterOrDigit() }
