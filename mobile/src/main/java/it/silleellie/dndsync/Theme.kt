import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.*
import androidx.compose.runtime.Composable

val LightColorPalette = lightColorScheme(
    primary = Color(0xFF1976D2), // primaryColor
    primaryContainer = Color(0xFF63A4FF), // primaryLightColor
    onPrimary = Color(0xFFFFFFFF), // primaryTextColor
    secondary = Color(0xFF4272F5), // secondaryColor
    secondaryContainer = Color(0xFF67DAFF), // secondaryLightColor
    onSecondary = Color(0xFF000000), // secondaryTextColor
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    onBackground = Color(0xFF000000),
    onSurface = Color(0xFF000000)
)

val DarkColorPalette = darkColorScheme(
    primary = Color(0xFF1976D2), // primaryColor
    primaryContainer = Color(0xFF004BA0), // primaryDarkColor
    onPrimary = Color(0xFFFFFFFF), // primaryTextColor
    secondary = Color(0xFF4272F5), // secondaryColor
    secondaryContainer = Color(0xFF007AC1), // secondaryDarkColor
    onSecondary = Color(0xFF000000), // secondaryTextColor
    background = Color(0xFF121212),
    surface = Color(0xFF121212),
    onBackground = Color(0xFFFFFFFF),
    onSurface = Color(0xFFFFFFFF)
)

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorPalette else LightColorPalette,
        content = content
    )
}
