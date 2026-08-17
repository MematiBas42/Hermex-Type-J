package io.github.jcartu.hermex.model

import kotlinx.serialization.Serializable

/** Tolerant response for POST /api/transcribe. */
@Serializable
data class TranscribeResponse(
    val ok: Boolean? = null,
    val transcript: String? = null,
    val error: String? = null,
)
