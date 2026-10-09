package com.vettid.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Raw brand palette, aligned with the website tokens (vettid.org
 * `website/assets/site.css`; token names in comments). Screens never use these
 * directly: they read [androidx.compose.material3.MaterialTheme.colorScheme] or
 * [VettIdTheme.colors].
 *
 * Gold (#FFC125, `--gold`) is the single accent. On light surfaces it is too pale
 * for text and icons (1.6:1), so "gold content" there uses [GoldDeep]
 * (`--gold-ink-light`, 5.6:1 on white) and the bright gold is kept for fills.
 */
object VettIdPalette {
    val Gold = Color(0xFFFFC125) // --gold
    val GoldDeep = Color(0xFF7F640A) // --gold-ink-light
    val Ink = Color(0xFF101018) // --ink-on-light
    val White = Color(0xFFFFFFFF)

    // Navy ramp (dark theme surfaces), after the website's surface tokens.
    val Navy950 = Color(0xFF0B0B18)
    val Navy900 = Color(0xFF14142A) // --surface-1
    val Navy850 = Color(0xFF1B1B3A) // --surface-2
    val Navy800 = Color(0xFF26264A)
    val Navy700 = Color(0xFF30305A)
    val Navy600 = Color(0xFF3E3E6C)
    val Indigo = Color(0xFF2E2D88) // --surface-indigo

    /** Favourite connections' tiles: a teal that sits with navy and gold. */
    val Teal = Color(0xFF1F7A6B)

    // Light theme neutrals.
    val Paper = Color(0xFFFFFFFF)
    val Mist50 = Color(0xFFF7F7FA)
    val Mist100 = Color(0xFFF4F4F6) // --band-light
    val Mist200 = Color(0xFFE6E6EE)
    val Mist300 = Color(0xFFD9D9DF) // --border-light

    val InkHigh = Color(0xFFF7F7FA) // --ink-hi
    val InkBody = Color(0xFFA9A9C2)
    val InkMuteLight = Color(0xFF4C4C58) // --ink-on-light-mute

    val Success = Color(0xFF3FD97F) // --success
    val SuccessDeep = Color(0xFF1E7A46)
    val Warning = Color(0xFFFFB020) // --warn
    val WarningDeep = Color(0xFF8A5A00)
    val ErrorDark = Color(0xFFFF6B6B) // --error
    val ErrorLight = Color(0xFFB3261E)
}

/** Colours outside the Material scheme. */
@Immutable
data class VettIdColors(
    /** Background of a connection's initial tile. */
    val tile: Color,
    /** Letter on [tile]. */
    val onTile: Color,
    /** Tile of a favourite connection (`connection.update` favorite, VAULT-MESSAGING 10.4). */
    val favoriteTile: Color,
    val onFavoriteTile: Color,
    /** The member's own avatar tile (gold). */
    val avatar: Color,
    val onAvatar: Color,
    val success: Color,
    val warning: Color,
    /** Background of grouped screens (settings); also the hairline between rows in a group. */
    val groupedBackground: Color,
    /** Grouped settings card. */
    val card: Color,
    /** Background behind modal content (drawer, sheet). */
    val scrim: Color,
    /** The dark theme (picks the tag colours, [TagColors]). */
    val isDark: Boolean,
)

internal val DarkVettIdColors = VettIdColors(
    tile = VettIdPalette.Indigo,
    onTile = VettIdPalette.InkHigh,
    favoriteTile = VettIdPalette.Teal,
    onFavoriteTile = VettIdPalette.White,
    avatar = VettIdPalette.Gold,
    onAvatar = VettIdPalette.Ink,
    success = VettIdPalette.Success,
    warning = VettIdPalette.Warning,
    groupedBackground = VettIdPalette.Navy850,
    card = VettIdPalette.Navy800,
    scrim = VettIdPalette.Navy950.copy(alpha = 0.6f),
    isDark = true,
)

internal val LightVettIdColors = VettIdColors(
    tile = VettIdPalette.Indigo,
    onTile = VettIdPalette.White,
    favoriteTile = VettIdPalette.Teal,
    onFavoriteTile = VettIdPalette.White,
    avatar = VettIdPalette.Gold,
    onAvatar = VettIdPalette.Ink,
    success = VettIdPalette.SuccessDeep,
    warning = VettIdPalette.WarningDeep,
    groupedBackground = VettIdPalette.Mist100,
    card = VettIdPalette.Paper,
    scrim = VettIdPalette.Ink.copy(alpha = 0.4f),
    isDark = false,
)

/**
 * Role mapping (both themes):
 * - `primary` = gold *content* (icons, selected text, switches); `primaryContainer` = gold *fill*.
 * - `background` = screen; `surfaceContainerLow` = drawer and sheets;
 *   `surfaceContainer` = cards, chips, floating buttons; `surfaceContainerHigh` = pressed / nested.
 */
internal val DarkColors: ColorScheme = darkColorScheme(
    primary = VettIdPalette.Gold,
    onPrimary = VettIdPalette.Ink,
    primaryContainer = VettIdPalette.Gold,
    onPrimaryContainer = VettIdPalette.Ink,
    secondary = VettIdPalette.InkBody,
    onSecondary = VettIdPalette.Ink,
    secondaryContainer = VettIdPalette.Navy800,
    onSecondaryContainer = VettIdPalette.InkHigh,
    tertiary = VettIdPalette.Gold,
    onTertiary = VettIdPalette.Ink,
    background = VettIdPalette.Navy900,
    onBackground = VettIdPalette.InkHigh,
    surface = VettIdPalette.Navy900,
    onSurface = VettIdPalette.InkHigh,
    surfaceVariant = VettIdPalette.Navy800,
    onSurfaceVariant = VettIdPalette.InkBody,
    surfaceDim = VettIdPalette.Navy950,
    surfaceBright = VettIdPalette.Navy700,
    surfaceContainerLowest = VettIdPalette.Navy950,
    surfaceContainerLow = VettIdPalette.Navy850,
    surfaceContainer = VettIdPalette.Navy800,
    surfaceContainerHigh = VettIdPalette.Navy700,
    surfaceContainerHighest = VettIdPalette.Navy600,
    inverseSurface = VettIdPalette.InkHigh,
    inverseOnSurface = VettIdPalette.Navy900,
    inversePrimary = VettIdPalette.GoldDeep,
    outline = VettIdPalette.Navy600,
    outlineVariant = VettIdPalette.Navy800,
    error = VettIdPalette.ErrorDark,
    onError = VettIdPalette.Ink,
    scrim = VettIdPalette.Navy950,
)

internal val LightColors: ColorScheme = lightColorScheme(
    primary = VettIdPalette.GoldDeep,
    onPrimary = VettIdPalette.White,
    primaryContainer = VettIdPalette.Gold,
    onPrimaryContainer = VettIdPalette.Ink,
    secondary = VettIdPalette.InkMuteLight,
    onSecondary = VettIdPalette.White,
    secondaryContainer = VettIdPalette.Mist100,
    onSecondaryContainer = VettIdPalette.Ink,
    tertiary = VettIdPalette.GoldDeep,
    onTertiary = VettIdPalette.White,
    background = VettIdPalette.Paper,
    onBackground = VettIdPalette.Ink,
    surface = VettIdPalette.Paper,
    onSurface = VettIdPalette.Ink,
    surfaceVariant = VettIdPalette.Mist100,
    onSurfaceVariant = VettIdPalette.InkMuteLight,
    surfaceDim = VettIdPalette.Mist200,
    surfaceBright = VettIdPalette.Paper,
    surfaceContainerLowest = VettIdPalette.Paper,
    surfaceContainerLow = VettIdPalette.Mist50,
    surfaceContainer = VettIdPalette.Mist100,
    surfaceContainerHigh = VettIdPalette.Mist200,
    surfaceContainerHighest = VettIdPalette.Mist300,
    inverseSurface = VettIdPalette.Navy900,
    inverseOnSurface = VettIdPalette.InkHigh,
    inversePrimary = VettIdPalette.Gold,
    outline = VettIdPalette.Mist300,
    outlineVariant = VettIdPalette.Mist200,
    error = VettIdPalette.ErrorLight,
    onError = VettIdPalette.White,
    scrim = VettIdPalette.Ink,
)
