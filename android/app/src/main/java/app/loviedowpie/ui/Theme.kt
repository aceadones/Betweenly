package app.loviedowpie.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.Shapes
import app.loviedowpie.R
import java.time.Instant
import java.time.ZoneId

/**
 * Happn-like glass look. Screens read [LdColors] or [MaterialTheme].
 * Brand blue is #2F80FF. Filled controls that carry white text use a deeper
 * blue so body copy stays above WCAG AA (white on #2F80FF is only ~3.7:1).
 */
object LdColors {
    val primary = Color(0xFF2F80FF)
    val navy = Color(0xFF0A1F44)
    val heart = Color(0xFFFF4D5E)
    val success = Color(0xFF34C759)
    val white = Color(0xFFFFFFFF)
    val black = Color(0xFF111318)
    val clear = Color.Transparent
    val paper = Color(0xFFFFFFFF)

    /** Solid "mine" bubble. Deepened from [primary] so white text clears 4.5:1. */
    val bubbleMine = Color(0xFF185FC4)
    val onMineMuted = Color(0xDBFFFFFF)
    val metaTheirs = Color(0xFF3E5670)

    val cameraBg = Color(0xFF000000)
    val cameraContent = Color(0xFFFFFFFF)
    val cameraScrim = Color(0x66000000)
    val cameraFaint = Color(0x66FFFFFF)
    val cameraError = Color(0xFFFFB4AB)

    val mapPlaceholderLight = Color(0xFFD5E8F6)
    val mapPlaceholderDark = Color(0xFF10243F)

    /** Wallpaper swatches, same order as Store "color:0" … "color:5". */
    val wallpapers: List<Color> = listOf(
        Color(0xFFF6D5DC),
        Color(0xFFF6E6C8),
        Color(0xFFD5EBD8),
        Color(0xFFD3E7F8),
        Color(0xFFE4D7F6),
        Color(0xFF1A2744),
    )

    val inkRed = Color(0xFFD64545)
    val inkOrange = Color(0xFFE8882F)
    val inkYellow = Color(0xFFF2C14E)
    val inkGreen = Color(0xFF3E9B5B)
    val inkBlue = Color(0xFF3A7BD5)
    val inkPurple = Color(0xFF8A4FBF)
    val hueRed = Color(0xFFFF0000)
    val hueYellow = Color(0xFFFFFF00)
    val hueGreen = Color(0xFF00FF00)
    val hueCyan = Color(0xFF00FFFF)
    val hueBlue = Color(0xFF0000FF)
    val hueMagenta = Color(0xFFFF00FF)

    val light = LdPalette(
        skyTop = Color(0xFFBFE3FF),
        skyMid = Color(0xFFDDF0FF),
        skyBottom = Color(0xFFF7FBFF),
        blobA = Color(0x8CFFFFFF),
        blobB = Color(0x66FFFFFF),
        blobC = Color(0x55EAF6FF),
        glass = Color(0xB8FFFFFF),
        glassStrong = Color(0xD6FFFFFF),
        glassBorder = Color(0x99FFFFFF),
        ink = Color(0xFF0A1F44),
        inkMuted = Color(0xFF3E5670),
        chipOn = Color(0xFFFFFFFF),
        chipOnInk = Color(0xFF0A1F44),
        pill = Color(0xFF111318),
        onPill = Color(0xFFFFFFFF),
        pillBorder = Color(0x00000000),
        knob = Color(0xFFFFFFFF),
        onKnob = Color(0xFF111318),
        shadow = Color(0x26102A4C),
        bubbleTheirs = Color(0xB8FFFFFF),
        navIdle = Color(0xFF5C7290),
        dialog = Color(0xFFF4F9FF),
        textPrimary = Color(0xFF185FC4),
        // White at 96% so chat text and photos do not show through a popup.
        menu = Color(0xF5FFFFFF),
        menuBorder = Color(0x3310284F),
    )

    val dark = LdPalette(
        skyTop = Color(0xFF0B1B3A),
        skyMid = Color(0xFF10284F),
        skyBottom = Color(0xFF0C2144),
        blobA = Color(0x332F80FF),
        blobB = Color(0x28143A6E),
        blobC = Color(0x142F80FF),
        glass = Color(0x2EFFFFFF),
        glassStrong = Color(0x40FFFFFF),
        glassBorder = Color(0x66FFFFFF),
        ink = Color(0xFFF4F8FF),
        inkMuted = Color(0xFFC5D4E8),
        chipOn = Color(0xFFFFFFFF),
        chipOnInk = Color(0xFF0A1F44),
        // Light fill so the pill stays visible on the navy sky. Navy text clears WCAG AA.
        pill = Color(0xFFF4F8FF),
        onPill = Color(0xFF0A1F44),
        pillBorder = Color(0xFFFFFFFF),
        knob = Color(0xFF0A1F44),
        onKnob = Color(0xFFFFFFFF),
        shadow = Color(0x66000000),
        bubbleTheirs = Color(0xE6FFFFFF),
        navIdle = Color(0xFFB7C7DC),
        dialog = Color(0xFF122848),
        textPrimary = Color(0xFF7EB6FF),
        // Navy #10284F at 97%. Light text (ink) sits on this fill.
        menu = Color(0xF710284F),
        menuBorder = Color(0x66FFFFFF),
    )

    val colors: LdPalette
        @Composable get() = if (isSystemInDarkTheme()) dark else light
}

@Immutable
class LdPalette(
    val skyTop: Color,
    val skyMid: Color,
    val skyBottom: Color,
    val blobA: Color,
    val blobB: Color,
    val blobC: Color,
    val glass: Color,
    val glassStrong: Color,
    val glassBorder: Color,
    val ink: Color,
    val inkMuted: Color,
    val chipOn: Color,
    val chipOnInk: Color,
    val pill: Color,
    val onPill: Color,
    val pillBorder: Color,
    val knob: Color,
    val onKnob: Color,
    val shadow: Color,
    val bubbleTheirs: Color,
    val navIdle: Color,
    val dialog: Color,
    val textPrimary: Color,
    /**
     * Popup fill. Light is white at 96% opacity. Dark is navy #10284F at 97%.
     * Opaque enough that chat text and photos do not read through menus.
     */
    val menu: Color,
    /** Thin stroke around menus and dialogs. */
    val menuBorder: Color,
)

private val BaseType = Typography()

private val AppType = Typography(
    displaySmall = BaseType.displaySmall.copy(fontWeight = FontWeight.Black, letterSpacing = (-1.1).sp),
    headlineLarge = BaseType.headlineLarge.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.8).sp),
    headlineMedium = BaseType.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    headlineSmall = BaseType.headlineSmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.35).sp),
    titleLarge = BaseType.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
    titleMedium = BaseType.titleMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.1).sp),
    titleSmall = BaseType.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = BaseType.bodyLarge.copy(fontWeight = FontWeight.Medium),
    bodyMedium = BaseType.bodyMedium.copy(fontWeight = FontWeight.Medium),
    bodySmall = BaseType.bodySmall.copy(fontWeight = FontWeight.Medium),
    labelLarge = BaseType.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = BaseType.labelMedium.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = BaseType.labelSmall.copy(fontWeight = FontWeight.Medium),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val LightColors = lightColorScheme(
    primary = LdColors.light.textPrimary,
    onPrimary = LdColors.white,
    primaryContainer = Color(0xFFD6E8FF),
    onPrimaryContainer = LdColors.navy,
    secondary = LdColors.navy,
    onSecondary = LdColors.white,
    secondaryContainer = Color(0xFFD6E8FF),
    onSecondaryContainer = LdColors.navy,
    background = LdColors.clear,
    onBackground = LdColors.navy,
    surface = LdColors.light.dialog,
    onSurface = LdColors.navy,
    surfaceVariant = LdColors.light.glass,
    onSurfaceVariant = LdColors.light.inkMuted,
    outline = Color(0xFF6E86A0),
    error = Color(0xFFB42318),
    onError = LdColors.white,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    surfaceTint = LdColors.clear,
    surfaceContainerLowest = LdColors.light.menu,
    surfaceContainerLow = LdColors.light.menu,
    surfaceContainer = LdColors.light.menu,
    surfaceContainerHigh = LdColors.light.menu,
    surfaceContainerHighest = LdColors.light.menu,
    inverseSurface = LdColors.navy,
    inverseOnSurface = LdColors.white,
    scrim = Color(0x99000000),
)

private val DarkColors = darkColorScheme(
    primary = LdColors.dark.textPrimary,
    onPrimary = LdColors.navy,
    primaryContainer = Color(0xFF1A3E7A),
    onPrimaryContainer = Color(0xFFE7F1FF),
    secondary = Color(0xFFD6E4F7),
    onSecondary = LdColors.navy,
    secondaryContainer = Color(0xFF1A3E7A),
    onSecondaryContainer = Color(0xFFE7F1FF),
    background = LdColors.clear,
    onBackground = Color(0xFFF4F8FF),
    surface = LdColors.dark.dialog,
    onSurface = Color(0xFFF4F8FF),
    surfaceVariant = LdColors.dark.glass,
    onSurfaceVariant = LdColors.dark.inkMuted,
    outline = Color(0xFFA8BDD4),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    surfaceTint = LdColors.clear,
    surfaceContainerLowest = LdColors.dark.menu,
    surfaceContainerLow = LdColors.dark.menu,
    surfaceContainer = LdColors.dark.menu,
    surfaceContainerHigh = LdColors.dark.menu,
    surfaceContainerHighest = LdColors.dark.menu,
    inverseSurface = Color(0xFFE7F1FF),
    inverseOnSurface = LdColors.navy,
    scrim = Color(0xCC000000),
)

@Composable
fun LoviedowpieTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppType,
        shapes = AppShapes,
    ) {
        Box(Modifier.fillMaxSize()) {
            AppBackground()
            content()
        }
    }
}

/** Soft sky (or navy) gradient plus a few cloud blobs. No blur. */
@Composable
fun AppBackground(modifier: Modifier = Modifier) {
    val c = LdColors.colors
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(c.skyTop, c.skyMid, c.skyBottom))),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(c.blobA, radius = size.minDimension * 0.62f, center = Offset(size.width * 0.08f, size.height * 0.06f))
            drawCircle(c.blobB, radius = size.minDimension * 0.48f, center = Offset(size.width * 0.98f, size.height * 0.20f))
            drawCircle(c.blobC, radius = size.minDimension * 0.72f, center = Offset(size.width * 0.40f, size.height * 0.82f))
        }
    }
}

@Composable
fun Modifier.ldGlass(shape: Shape = RoundedCornerShape(28.dp), strong: Boolean = false): Modifier {
    val c = LdColors.colors
    val fill = if (strong) c.glassStrong else c.glass
    return this
        .shadow(elevation = 10.dp, shape = shape, ambientColor = c.shadow, spotColor = c.shadow)
        .clip(shape)
        .background(fill)
        .border(width = 1.dp, color = c.glassBorder, shape = shape)
}

/** Mostly opaque popup surface: theme menu fill plus a thin border. */
@Composable
fun Modifier.ldPopupFrame(shape: Shape = MaterialTheme.shapes.extraLarge): Modifier {
    return this.border(width = 1.dp, color = LdColors.colors.menuBorder, shape = shape)
}

/** Black pill with a white arrow knob. Primary actions such as Continue. */
@Composable
fun PrimaryPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = LdColors.colors
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .heightIn(min = 60.dp)
            .alpha(if (enabled) 1f else 0.42f)
            .shadow(elevation = 8.dp, shape = shape, ambientColor = c.shadow, spotColor = c.shadow)
            .clip(shape)
            .background(c.pill)
            .border(1.dp, c.pillBorder, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(c.knob),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = c.onKnob)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(
                text,
                color = c.onPill,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(48.dp))
    }
}

/** Translucent pill for secondary actions. */
@Composable
fun SecondaryPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = LdColors.colors
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .heightIn(min = 48.dp)
            .alpha(if (enabled) 1f else 0.42f)
            .ldGlass(shape, strong = true)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = c.ink,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
) {
    val c = LdColors.colors
    Box(
        modifier
            .size(44.dp)
            .ldGlass(CircleShape, strong = true)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = c.ink)
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    val c = LdColors.colors
    Column(
        modifier
            .ldGlass(RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = c.inkMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            color = c.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun VerifiedDot() {
    Box(
        Modifier.size(18.dp).clip(CircleShape).background(LdColors.primary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Check,
            contentDescription = null,
            tint = LdColors.white,
            modifier = Modifier.size(12.dp),
        )
    }
}

@Composable
fun GreetingHeader(
    name: String,
    revision: Int,
    loadAvatar: () -> ByteArray?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = LdColors.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RoundAvatar(revision, loadAvatar, name, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(greetingPhrase(), style = MaterialTheme.typography.labelLarge, color = c.inkMuted)
            Text(
                name,
                style = MaterialTheme.typography.headlineMedium,
                color = c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        actions()
    }
}

@Composable
private fun greetingPhrase(): String {
    val now = rememberNow()
    val hour = remember(now) {
        Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).hour
    }
    val id = when (hour) {
        in 5..11 -> R.string.greeting_morning
        in 12..16 -> R.string.greeting_afternoon
        in 17..21 -> R.string.greeting_evening
        else -> R.string.greeting_night
    }
    return stringResource(id)
}

data class LdTab(
    val label: String,
    val outlined: ImageVector,
    val filled: ImageVector,
    val selected: Boolean,
    val badge: Int = 0,
    val onClick: () -> Unit,
)

/** Floating glass bar, four tabs, and a center camera orb between tab 2 and 3. */
@Composable
fun LdBottomBar(
    tabs: List<LdTab>,
    onCamera: () -> Unit,
    cameraDescription: String,
) {
    val shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Box(
            Modifier
                .padding(top = 22.dp)
                .fillMaxWidth()
                .height(72.dp)
                .ldGlass(shape, strong = true),
        )
        Row(
            Modifier
                .padding(top = 22.dp)
                .fillMaxWidth()
                .height(72.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                if (index == 2) Spacer(Modifier.width(72.dp))
                LdTabItem(tab, Modifier.weight(1f))
            }
        }
        CameraOrb(
            onClick = onCamera,
            description = cameraDescription,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun LdTabItem(tab: LdTab, modifier: Modifier) {
    val c = LdColors.colors
    val tint = if (tab.selected) c.ink else c.navIdle
    val spoken = if (tab.badge > 0) stringResource(R.string.tab_unread, tab.label, tab.badge) else tab.label
    Column(
        modifier
            .height(64.dp)
            .semantics {
                selected = tab.selected
                contentDescription = spoken
            }
            .clickable(role = Role.Tab, onClick = tab.onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val icon = @Composable {
            Icon(
                imageVector = if (tab.selected) tab.filled else tab.outlined,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(24.dp),
            )
        }
        if (tab.badge > 0) {
            BadgedBox(
                badge = {
                    Badge(
                        containerColor = LdColors.heart,
                        contentColor = LdColors.white,
                    ) {
                        Text(if (tab.badge > 99) "99+" else tab.badge.toString())
                    }
                },
            ) {
                icon()
            }
        } else {
            icon()
        }
        Spacer(Modifier.height(2.dp))
        Text(
            tab.label,
            color = tint,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (tab.selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun CameraOrb(onClick: () -> Unit, description: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(64.dp)
            .shadow(12.dp, CircleShape, ambientColor = LdColors.navy, spotColor = LdColors.navy)
            .clip(CircleShape)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF1E4FBE), Color(0xFF0A1F44), Color(0xFF05070C)),
                        center = center,
                        radius = size.minDimension * 0.72f,
                    ),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(LdColors.white.copy(alpha = 0.92f), Color.Transparent),
                        center = Offset(size.width * 0.36f, size.height * 0.28f),
                        radius = size.minDimension * 0.26f,
                    ),
                )
            }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            OrbCamera,
            contentDescription = description,
            tint = LdColors.white,
            modifier = Modifier.size(28.dp),
        )
    }
}

private val OrbCamera: ImageVector = ImageVector.Builder(
    name = "OrbCamera",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(LdColors.white), pathFillType = PathFillType.EvenOdd) {
        moveTo(9f, 4f)
        lineTo(7.2f, 6.2f)
        horizontalLineTo(4.2f)
        curveTo(3f, 6.2f, 2f, 7.2f, 2f, 8.4f)
        verticalLineTo(18f)
        curveTo(2f, 19.2f, 3f, 20.2f, 4.2f, 20.2f)
        horizontalLineTo(19.8f)
        curveTo(21f, 20.2f, 22f, 19.2f, 22f, 18f)
        verticalLineTo(8.4f)
        curveTo(22f, 7.2f, 21f, 6.2f, 19.8f, 6.2f)
        horizontalLineTo(16.8f)
        lineTo(15f, 4f)
        close()
        moveTo(12f, 9.2f)
        arcTo(3.4f, 3.4f, 0f, true, true, 11.99f, 9.2f)
        close()
    }
}.build()
