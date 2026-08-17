package io.github.jcartu.hermex.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** System-default M3 type ramp with a monospace code style for transcript code blocks. */
val HermexTypography = Typography().let { base ->
    base.copy(
        bodyLarge = base.bodyLarge.copy(lineHeight = 22.sp),
        bodyMedium = base.bodyMedium.copy(lineHeight = 20.sp),
    )
}

val CodeTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 19.sp,
)

val TimestampTextStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontSize = 11.sp,
    lineHeight = 14.sp,
    fontWeight = FontWeight.Medium,
)
