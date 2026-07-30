package dev.bgeo.example.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/**
 * Light/dark palette for the example app chrome (panels, tab bar, sheet).
 *
 * The values are the web console's design tokens (`web/src/index.css`) converted
 * from oklch to hex — same palette on every client. Ported from
 * `react-native/example/src/theme.ts` (`ios/Example/Sources/Theme.swift` and
 * `flutter/example/lib/src/theme.dart` are the same port for iOS/Flutter). Two
 * roles per status color: `success`/`warning`/`danger` are FILLS, meant to carry
 * a contrasting foreground; `*Text` are the readable inks for type on
 * `background`/`surface`. In dark they coincide; in light the fill stays bright
 * and the ink is the same hue darkened to clear 4.5:1.
 *
 * Map-overlay colors (geofence actions, track polyline, breadcrumb dots) are NOT
 * theme-scoped — the web console keeps them fixed too.
 *
 * One palette, one place: unlike `Theme.swift` (four duplicated `scheme`/`colors`
 * blocks and two hex parsers — a deferred defect there), this file defines the
 * colors exactly once.
 */

enum class Scheme { LIGHT, DARK }

data class ThemeColors(
    /** Screen background. */
    val background: Color,
    /** Cards and raised chrome. */
    val surface: Color,
    /** Buttons, toggles, chips. */
    val surfaceRaised: Color,
    /** Text inputs and the log/terminal viewport. */
    val field: Color,
    val border: Color,
    val separator: Color,
    /** Floating panels over the map — translucent over `surface`. */
    val panel: Color,
    /** Bottom-sheet drag handle. */
    val handle: Color,
    val text: Color,
    /** Secondary type (labels, values). */
    val text2: Color,
    /** Dimmed type (metadata, table gutters). */
    val textDim: Color,
    val placeholder: Color,
    val accent: Color,
    /** Accent as type — darkened in light so it clears 4.5:1. */
    val accentText: Color,
    /** Tinted accent background (badges). */
    val accentSoft: Color,
    /** Type on an `accent`/`danger` fill. */
    val onAccent: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val successText: Color,
    val warningText: Color,
    val dangerText: Color,
    /** Row tint for geofence events. */
    val warningSoft: Color,
    val tabBar: Color,
    val tabBarBorder: Color,
)

private fun rgb(r: Int, g: Int, b: Int, alpha: Float = 1f): Color =
    Color(red = r / 255f, green = g / 255f, blue = b / 255f, alpha = alpha)

val LightColors = ThemeColors(
    background = rgb(245, 245, 246),
    surface = rgb(255, 255, 255),
    surfaceRaised = rgb(239, 239, 240),
    field = rgb(255, 255, 255),
    border = rgb(221, 222, 223),
    separator = rgb(228, 228, 229),
    panel = rgb(255, 255, 255, 0.94f),
    handle = rgb(207, 208, 210),
    text = rgb(24, 24, 25),
    text2 = rgb(82, 88, 100),
    textDim = rgb(113, 114, 115),
    placeholder = rgb(129, 134, 143),
    accent = rgb(58, 111, 240),
    accentText = rgb(50, 101, 229),
    accentSoft = rgb(58, 111, 240, 0.12f),
    onAccent = rgb(255, 255, 255),
    success = rgb(0, 201, 103),
    warning = rgb(244, 166, 32),
    danger = rgb(255, 56, 54),
    successText = rgb(2, 136, 69),
    warningText = rgb(161, 107, 8),
    dangerText = rgb(232, 20, 32),
    warningSoft = rgb(244, 166, 32, 0.14f),
    tabBar = rgb(255, 255, 255),
    tabBarBorder = rgb(221, 222, 223),
)

val DarkColors = ThemeColors(
    background = rgb(6, 8, 13),
    surface = rgb(24, 24, 25),
    surfaceRaised = rgb(35, 35, 36),
    field = rgb(0, 0, 0),
    border = rgb(41, 41, 41),
    separator = rgb(33, 34, 34),
    panel = rgb(24, 24, 25, 0.94f),
    handle = rgb(63, 64, 69),
    text = rgb(252, 252, 253),
    text2 = rgb(156, 165, 177),
    textDim = rgb(159, 160, 161),
    placeholder = rgb(109, 117, 128),
    accent = rgb(58, 111, 240),
    accentText = rgb(104, 149, 244),
    accentSoft = rgb(58, 111, 240, 0.28f),
    onAccent = rgb(255, 255, 255),
    success = rgb(0, 201, 103),
    warning = rgb(246, 184, 79),
    danger = rgb(219, 59, 58),
    successText = rgb(0, 201, 103),
    warningText = rgb(246, 184, 79),
    dangerText = rgb(219, 59, 58),
    warningSoft = rgb(246, 184, 79, 0.10f),
    tabBar = rgb(15, 15, 20),
    tabBarBorder = rgb(41, 41, 41),
)

val Palette: Map<Scheme, ThemeColors> = mapOf(Scheme.LIGHT to LightColors, Scheme.DARK to DarkColors)

/** `theme.ts`'s `MONO` resolves to `'monospace'` on Android (the non-iOS branch of its `Platform.select`). */
val Mono: FontFamily = FontFamily.Monospace
