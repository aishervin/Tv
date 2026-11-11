package com.darktv.player

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private val accent = Color(0xFF8B5CF6)

internal data class AppPalette(
    val background: Color, val text: Color, val muted: Color,
    val glassTop: Color, val glassBottom: Color, val glassBorder: Color,
    val card: Color, val focusedCard: Color, val serverText: Color
)
private val darkPalette = AppPalette(Color(0xFF080D1B), Color(0xFFF5F6FF), Color(0xFFA5AFC7),
    Color(0xC02B344A), Color(0xD9111726), Color(0x668A9BBF),
    Color(0xA51B2334), Color(0xCC39425A), Color(0xFFCBD0D9))
private val lightPalette = AppPalette(Color(0xFFF1F3FA), Color(0xFF202438), Color(0xFF5E687C),
    Color(0xDFEEF2FA), Color(0xD9D3DAE9), Color(0xAAFFFFFF),
    Color(0xDFFFFFFF), Color(0xFFE2DAF6), Color(0xFF505968))
internal val LocalPalette = staticCompositionLocalOf { darkPalette }
internal val LocalAccent = staticCompositionLocalOf { accent }
internal val LocalIsLight = staticCompositionLocalOf { false }
internal val LocalIsSimple = staticCompositionLocalOf { true }
private val vazir = FontFamily(Font(R.font.vazirmatn_regular))

private fun appTypography(): Typography {
    val t = Typography()
    return t.copy(
        displayLarge = t.displayLarge.copy(fontFamily = vazir), displayMedium = t.displayMedium.copy(fontFamily = vazir), displaySmall = t.displaySmall.copy(fontFamily = vazir),
        headlineLarge = t.headlineLarge.copy(fontFamily = vazir), headlineMedium = t.headlineMedium.copy(fontFamily = vazir), headlineSmall = t.headlineSmall.copy(fontFamily = vazir),
        titleLarge = t.titleLarge.copy(fontFamily = vazir), titleMedium = t.titleMedium.copy(fontFamily = vazir), titleSmall = t.titleSmall.copy(fontFamily = vazir),
        bodyLarge = t.bodyLarge.copy(fontFamily = vazir), bodyMedium = t.bodyMedium.copy(fontFamily = vazir), bodySmall = t.bodySmall.copy(fontFamily = vazir),
        labelLarge = t.labelLarge.copy(fontFamily = vazir), labelMedium = t.labelMedium.copy(fontFamily = vazir), labelSmall = t.labelSmall.copy(fontFamily = vazir))
}

internal fun Modifier.glass(palette: AppPalette, radius: Int = 22): Modifier {
    val shape = RoundedCornerShape(radius.dp)
    return this.background(Brush.verticalGradient(listOf(palette.glassTop, palette.glassBottom)), shape)
        .border(1.dp, palette.glassBorder, shape)
}

@Composable
internal fun AppTheme(inPictureInPicture: Boolean, onEnterPictureInPicture: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { AppearanceSettings(context) }
    val light = when (settings.mode) { "light" -> true; "system" -> !isSystemInDarkTheme(); else -> false }
    val selectedAccent = themeColors[settings.colorIndex]
    val base = if (light) lightPalette else darkPalette
    val palette = when (settings.style) {
        "oled" -> if (light) base else base.copy(background = Color.Black, glassTop = Color(0xE51B1B20), glassBottom = Color(0xF2000000), card = Color(0xFF111114))
        else -> base
    }
    // Tonal containers follow the user's selected accent on every Android version.
    val scheme = if (light) lightColorScheme() else darkColorScheme()
    val container = lerp(selectedAccent, if (light) Color.White else Color.Black, if (light) 0.82f else 0.72f)
    val variant = lerp(selectedAccent, if (light) Color.White else Color.Black, if (light) 0.90f else 0.83f)
    val colors = scheme.copy(primary = selectedAccent,
        onSurface = if (light) Color.Black else Color.White,
        onBackground = if (light) Color.Black else Color.White,
        primaryContainer = container, secondaryContainer = container, surfaceVariant = variant,
        onPrimaryContainer = if (light) Color.Black else Color.White,
        onSecondaryContainer = if (light) Color.Black else Color.White,
        onSurfaceVariant = if (light) Color(0xFF303030) else Color(0xFFE2E2E2),
        onPrimary = if (selectedAccent.luminance() > 0.179f) Color.Black else Color.White,
        background = if (settings.style == "oled" && !light) Color.Black else scheme.background,
        surface = if (settings.style == "oled" && !light) Color(0xFF101014) else scheme.surface)
    val materialPalette = palette.copy(background = colors.background, text = colors.onSurface,
        muted = colors.onSurfaceVariant,
        glassTop = if (settings.style == "oled" && !light) Color(0xE51B1B20) else colors.surfaceVariant.copy(alpha = if (light) 0.85f else 0.78f),
        glassBottom = colors.surface.copy(alpha = 0.90f), glassBorder = colors.outline.copy(alpha = 0.5f),
        card = when {
            settings.style == "oled" && !light -> Color(0xFF111114)
            light -> lerp(colors.surfaceVariant, Color.Black, 0.06f)
            else -> lerp(colors.background, Color.Black, 0.10f)
        }, focusedCard = colors.secondaryContainer,
        serverText = colors.onSurfaceVariant)
    val density = LocalDensity.current
    CompositionLocalProvider(LocalPalette provides materialPalette, LocalAccent provides selectedAccent,
        LocalIsLight provides light, LocalIsSimple provides (settings.style != "oled" || light), LocalLayoutDirection provides LayoutDirection.Ltr,
        LocalDensity provides Density(density.density, density.fontScale * settings.fontPercent / 100f)) {
        MaterialTheme(colorScheme = colors, typography = appTypography(), shapes = Shapes()) {
            var opening by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) { delay(1400); opening = false }
            if (opening) {
                Column(Modifier.fillMaxSize().background(materialPalette.background), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Image(painterResource(R.drawable.app_icon), contentDescription = "Dark TV", modifier = Modifier.size(140.dp))
                    Spacer(Modifier.height(14.dp))
                    Text("Dark TV Live", color = materialPalette.text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
            } else TvApp(settings, TelegramSettings(context), inPictureInPicture, onEnterPictureInPicture)
        }
    }
}

