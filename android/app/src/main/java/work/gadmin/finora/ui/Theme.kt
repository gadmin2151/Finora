package work.gadmin.finora.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Forest = Color(0xFF123A32)
val CaptureMint = Color(0xFFC5F4DC)

private val LocalDarkPalette = staticCompositionLocalOf { true }
val Green: Color
    @Composable get() = if (LocalDarkPalette.current) Color(0xFF82DDB5) else Color(0xFF285F41)
val Mint: Color
    @Composable get() = if (LocalDarkPalette.current) Color(0xFFC5F4DC) else Color(0xFF285F41)
val Paper: Color
    @Composable get() = if (LocalDarkPalette.current) Color(0xFF101715) else Color(0xFFF8FAF6)
val Ink: Color
    @Composable get() = if (LocalDarkPalette.current) Color(0xFFF1F3EC) else Color(0xFF203B31)
val Muted: Color
    @Composable get() = if (LocalDarkPalette.current) Color(0xFFA6B5AD) else Color(0xFF657369)
val SoftGreen: Color
    @Composable get() = if (LocalDarkPalette.current) Color(0xFF203B31) else Color(0xFFE9F4EC)
val Border: Color
    @Composable get() = if (LocalDarkPalette.current) Color(0xFF40534A) else Color(0xFFCDD9CF)
val SurfaceColor: Color
    @Composable get() = if (LocalDarkPalette.current) Color(0xFF1B2722) else Color(0xFFFFFFFF)
val SurfaceRaised: Color
    @Composable get() = if (LocalDarkPalette.current) Color(0xFF25352D) else Color(0xFFF0F5EF)
val OutlineSoft: Color
    @Composable get() = if (LocalDarkPalette.current) Color(0xFF31443A) else Color(0xFFE1E8E2)
val Amber: Color
    @Composable get() = if (LocalDarkPalette.current) Color(0xFFF0CB8C) else Color(0xFF80602A)
val AmberSurface: Color
    @Composable get() = if (LocalDarkPalette.current) Color(0xFF3B3021) else Color(0xFFFBF4E4)
val Coral: Color
    @Composable get() = if (LocalDarkPalette.current) Color(0xFFFFB4A8) else Color(0xFFAA3A31)
val CoralSurface: Color
    @Composable get() = if (LocalDarkPalette.current) Color(0xFF442B26) else Color(0xFFFFF0ED)
val Sage: Color
    @Composable get() = if (LocalDarkPalette.current) Color(0xFFBCD1B5) else Color(0xFF56715E)
val HeroStart: Color
    @Composable get() = if (LocalDarkPalette.current) Color(0xFF123A32) else Color(0xFFECF6E9)
val HeroEnd: Color
    @Composable get() = if (LocalDarkPalette.current) Color(0xFF2B3B32) else Color(0xFFF4F8ED)
val HeroInk: Color
    @Composable get() = if (LocalDarkPalette.current) Color(0xFFFFFFFF) else Color(0xFF204A32)
val OnPrimary: Color
    @Composable get() = if (LocalDarkPalette.current) Color(0xFF123A32) else Color(0xFFFFFFFF)

@Composable
fun FinoraTheme(darkOverride: Boolean? = null, content: @Composable () -> Unit) {
    val appearance = rememberAppearance()
    val dark = darkOverride ?: appearance.dark
    CompositionLocalProvider(LocalAppearance provides appearance, LocalDarkPalette provides dark) {
        MaterialTheme(
            colorScheme =
                (if (dark) darkColorScheme() else lightColorScheme()).copy(
                    primary = Green,
                    onPrimary = OnPrimary,
                    primaryContainer = SoftGreen,
                    onPrimaryContainer = Mint,
                    secondary = Amber,
                    onSecondary = AmberSurface,
                    secondaryContainer = AmberSurface,
                    onSecondaryContainer = Amber,
                    tertiary = Amber,
                    onTertiary = AmberSurface,
                    tertiaryContainer = AmberSurface,
                    onTertiaryContainer = Amber,
                    background = Paper,
                    surface = SurfaceColor,
                    onBackground = Ink,
                    onSurface = Ink,
                    onSurfaceVariant = Muted,
                    outline = Border,
                    outlineVariant = OutlineSoft,
                    surfaceVariant = SurfaceRaised,
                    surfaceTint = Color.Transparent,
                    surfaceDim = Paper,
                    surfaceBright = SurfaceRaised,
                    surfaceContainerLowest = Paper,
                    surfaceContainerLow = SurfaceColor,
                    surfaceContainer = SurfaceColor,
                    surfaceContainerHigh = SurfaceRaised,
                    surfaceContainerHighest = Border,
                    inverseSurface = Ink,
                    inverseOnSurface = Paper,
                    inversePrimary = Forest,
                    error = Coral,
                    onError = CoralSurface,
                    errorContainer = CoralSurface,
                    onErrorContainer = Coral,
                    scrim = Color.Black,
                ),
            typography =
                Typography(
                    headlineLarge =
                        TextStyle(
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 32.sp,
                            lineHeight = 38.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-0.9).sp,
                        ),
                    headlineMedium =
                        TextStyle(
                            fontSize = 28.sp,
                            lineHeight = 34.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.6).sp,
                        ),
                    titleLarge =
                        TextStyle(
                            fontSize = 22.sp,
                            lineHeight = 28.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    titleMedium =
                        TextStyle(
                            fontSize = 17.sp,
                            lineHeight = 23.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
                    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
                    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                ),
            shapes =
                Shapes(
                    small = RoundedCornerShape(12.dp),
                    medium = RoundedCornerShape(18.dp),
                    large = RoundedCornerShape(26.dp),
                ),
            content = { CompositionLocalProvider(LocalContentColor provides Ink) { content() } },
        )
    }
}

enum class Glyph(val paths: String) {
    SCAN(
        "M8 3H5a2 2 0 0 0-2 2v3 M16 3h3a2 2 0 0 1 2 2v3 M21 16v3a2 2 0 0 1-2 2h-3 M8 21H5a2 2 0 0 1-2-2v-3 M7 7h3v3H7z M14 7h3v3h-3z M7 14h3v3H7z M14 14h3v3 M14 17v-1"
    ),
    CAMERA(
        "M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3z M16 13a4 4 0 1 1-8 0a4 4 0 0 1 8 0"
    ),
    RECEIPT("M5 3l2 1 2-1 3 1 3-1 2 1 2-1v18l-2-1-2 1-3-1-3 1-2-1-2 1z M8 8h8 M8 12h8 M8 16h5"),
    CHART("M4 3v17h17 M8 15v-4 M13 15V7 M18 15v-6"),
    USER("M16 7a4 4 0 1 1-8 0a4 4 0 0 1 8 0 M4 21v-2a8 6 0 0 1 16 0v2"),
    HOME("M3 10l9-7 9 7 M5 9v12h14V9 M9 21v-8h6v8"),
    CHEVRON("M9 5l7 7-7 7"),
    DOWN("M6 9l6 6 6-6"),
    BACK("M15 5l-7 7 7 7"),
    CLOSE("M6 6l12 12 M6 18L18 6"),
    PLUS("M12 5v14 M5 12h14"),
    CHECK("M5 12l4 4L19 6"),
    SHIELD("M12 3l8 3v6c0 5-8 9-8 9S4 17 4 12V6z M8 12l3 3 5-6"),
    IMAGE("M3 3h18v18H3z M3 17l6-6 4 4 3-3 5 5 M9 7h.01"),
    FLASH("M13 2L4 14h7l-1 8 10-12h-7z"),
    SEARCH("M17 10a7 7 0 1 1-14 0a7 7 0 0 1 14 0 M15 15l6 6"),
    REFRESH("M20 8a8 8 0 1 0 0 8 M20 3v5h-5"),
    WALLET("M20 8V5H4a2 2 0 0 0 0 4h17v12H4a2 2 0 0 1-2-2V7 M21 13h-6v4h6"),
    ARROW("M5 12h14 M13 6l6 6-6 6"),
    EXIT("M10 3H4v18h6 M8 12h13 M17 8l4 4-4 4"),
    LOCK("M6 10h12v11H6z M8 10V6a4 4 0 0 1 8 0v4 M12 14v3"),
    EYE("M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12 M15 12a3 3 0 1 1-6 0a3 3 0 0 1 6 0"),
    LINK("M10 13a5 5 0 0 0 7 0l4-4a5 5 0 0 0-7-7l-3 3 M14 11a5 5 0 0 0-7 0l-4 4a5 5 0 0 0 7 7l3-3"),
    TRASH("M3 6h18 M9 6V3h6v3 M5 6l1 15h12l1-15 M10 10v7 M14 10v7"),
    SPARK("M12 2l3 7 7 3-7 3-3 7-3-7-7-3 7-3z"),
}

@Composable
fun LineIcon(glyph: Glyph, description: String? = null, tint: Color = Green, size: Dp = 24.dp) {
    val path = remember(glyph) { PathParser().parsePathString(glyph.paths).toPath() }
    Canvas(
        Modifier.size(size).semantics { if (description != null) contentDescription = description }
    ) {
        scale(
            this.size.width / 24f,
            this.size.height / 24f,
            pivot = androidx.compose.ui.geometry.Offset.Zero,
        ) {
            drawPath(
                path,
                tint,
                style = Stroke(width = 1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

@Composable
fun BrandMark(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Image(
        painter =
            androidx.compose.ui.res.painterResource(work.gadmin.finora.R.drawable.finora_symbol),
        contentDescription = null,
        modifier =
            modifier.then(
                Modifier.graphicsLayer {
                    scaleX = 1.42f
                    scaleY = 1.42f
                }
            ),
    )
}

@Composable
fun Brand(modifier: Modifier = Modifier, light: Boolean = false) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        BrandMark(Modifier.size(46.dp))
        Column {
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text(
                    "finora.",
                    fontSize = 31.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-1.5).sp,
                    color = Ink,
                )
                Surface(color = SoftGreen, shape = RoundedCornerShape(6.dp)) {
                    Text(
                        "27G",
                        Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        fontSize = 9.sp,
                        color = Mint,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Text(
                "PERSONAL FINANCE",
                fontSize = 8.sp,
                lineHeight = 12.sp,
                letterSpacing = 2.sp,
                color = Muted,
            )
        }
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: Glyph? = null,
) {
    Button(
        onClick,
        modifier.heightIn(min = 56.dp),
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 16.dp),
    ) {
        Text(text)
        if (icon != null) {
            Spacer(Modifier.width(10.dp))
            LineIcon(icon, tint = OnPrimary, size = 20.dp)
        }
    }
}

@Composable
fun SectionTitle(title: String, subtitle: String? = null) {
    Text(title, style = MaterialTheme.typography.titleLarge)
    if (subtitle != null) {
        Spacer(Modifier.height(5.dp))
        Text(subtitle, color = Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun InfoCard(text: String, icon: Glyph = Glyph.SHIELD) {
    Surface(color = SoftGreen, shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LineIcon(icon, size = 21.dp)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = Ink)
        }
    }
}

@Composable
fun EmptyState(title: String, text: String, icon: Glyph = Glyph.RECEIPT) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 36.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        Surface(color = SoftGreen, shape = RoundedCornerShape(24.dp)) {
            Box(Modifier.padding(22.dp)) { LineIcon(icon, size = 36.dp) }
        }
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(text, color = Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
