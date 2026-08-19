package pro.nspanel.ha2.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Dark wall-panel palette (near-black surfaces, muted HA-style green accent).
private val Background = Color(0xFF0D1117)
private val Surface = Color(0xFF161B22)
private val SurfaceVariant = Color(0xFF21262D)
private val OnSurface = Color(0xFFE6EDF3)
private val OnSurfaceVariant = Color(0xFF8B949E)
private val Outline = Color(0xFF30363D)
private val Primary = Color(0xFF3FB950)
private val OnPrimary = Color(0xFF041109)

private val DarkColors = darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = Color(0xFF1F3D28),
    onPrimaryContainer = Color(0xFFAFF4C6),
    secondary = Color(0xFF58A6FF),
    onSecondary = Color(0xFF0D1520),
    background = Background,
    onBackground = OnSurface,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    outline = Outline,
    error = Color(0xFFFF7B72),
    onError = Color(0xFF1E0505),
)

@Composable
fun NSPanelHATheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content,
    )
}
