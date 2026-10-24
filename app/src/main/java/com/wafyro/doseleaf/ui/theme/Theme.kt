package com.wafyro.doseleaf.ui.theme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
private val Colors = lightColorScheme(
    primary = ForestGreen, onPrimary = PureWhite, secondary = DeepGreen,
    background = PureWhite, onBackground = PrimaryText, surface = PureWhite,
    onSurface = PrimaryText, surfaceVariant = SurfaceSoft,
    onSurfaceVariant = SecondaryText, outline = BorderColor, error = ErrorRed
)
@Composable fun DoseleafTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = androidx.compose.material3.Typography(), content = content)
}
