package io.github.jcartu.hermex.model.hermes

/**
 * Port of `BotArtifactContext` (`Features/Bots/BotArtifact.swift`): captured with the
 * transcript, never reconstructed from the currently selected server.
 */
data class BotArtifactContext(
    val connectionID: String,
    val profile: String,
    val sessionID: String,
    val generation: Int,
)

/**
 * Port of `BotArtifactFailure`. [Folder] is a download the host refused because the path is
 * a folder, as a symlinked folder the workspace listing shows as a file is (#1112).
 */
sealed class BotArtifactFailure : Exception() {
    object Unavailable : BotArtifactFailure() {
        private fun readResolve(): Any = Unavailable
    }

    object TooLarge : BotArtifactFailure() {
        private fun readResolve(): Any = TooLarge
    }

    object InvalidReference : BotArtifactFailure() {
        private fun readResolve(): Any = InvalidReference
    }

    object Folder : BotArtifactFailure() {
        private fun readResolve(): Any = Folder
    }

    override val message: String
        get() = when (this) {
            Unavailable, Folder -> "This file could not be opened. It may be missing or unavailable on this Hermes connection."
            TooLarge -> "This file is too large to preview on this device (25 MB maximum)."
            InvalidReference -> "This reference does not name a file on this Bot connection."
        }
}

/** Port of `BotAttachmentFailure` (`Networking/Bots/BotAttachmentUpload.swift`). */
sealed class BotAttachmentFailure : Exception() {
    object Limit : BotAttachmentFailure() {
        private fun readResolve(): Any = Limit
    }

    object Type : BotAttachmentFailure() {
        private fun readResolve(): Any = Type
    }

    object Unreadable : BotAttachmentFailure() {
        private fun readResolve(): Any = Unreadable
    }

    override val message: String
        get() = when (this) {
            Limit -> "Use up to 8 attachments, 25 MB each and 50 MB total."
            Type -> "This attachment type is not supported. Choose an image, PDF, text, audio or document file."
            Unreadable -> "Could not read this attachment. Remove it and select it again."
        }
}
