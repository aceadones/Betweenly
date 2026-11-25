package com.nextlayer.betweenly.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.LocalIndication
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Shapes
import com.nextlayer.betweenly.R
import com.nextlayer.betweenly.Store
import com.nextlayer.betweenly.ui.icons.Lu
import com.nextlayer.betweenly.ui.icons.LuIcon
import java.time.Instant
import java.time.ZoneId

/**
 * Glass surfaces on a warm rose ground. Screens read [LdColors] or [MaterialTheme].
 * Accent is crimson-rose #D6245A. White on that fill is about 4.9:1.
 * [deep] (#A3123E) is the pressed end of the button and clears 7:1 with white.
 * [navy] is the wine ink callers already use for text. It is not blue.
 */
object LdColors {
    val primary = Color(0xFFD6245A)
    val deep = Color(0xFFA3123E)
    val glow = Color(0xFFFF5C86)
    val tint = Color(0xFFFFE3EA)
    val navy = Color(0xFF1A0A12)
    val heart = Color(0xFFFF4D5E)
    val success = Color(0xFF34C759)
    val white = Color(0xFFFFFFFF)
    val black = Color(0xFF111318)
    val clear = Color.Transparent
    val paper = Color(0xFFFFFFFF)
    /** Transparency checker. Same in light and dark so clear paper stays readable. */
    val checkerLight = Color(0xFFF4F4F6)
    val checkerDark = Color(0xFFD9D9DE)
    /** Warm plate under a brush sample. */
    val previewPlate = Color(0xFFF7F4EF)
    /** Rose wash. The Duet card paints with [navy] and [primary]. */
    val musicDeep = Color(0xFFA3123E)
    val musicWashTop = Color(0xFFFFE3EA)
    val musicWashBottom = Color(0xFFFF8AA6)

    /** Deep end of the mine bubble. White text on #B01848 is about 6.9:1. */
    val bubbleMine = Color(0xFFB01848)
    val bubbleMineStart = Color(0xFFD6245A)
    val bubbleMineEnd = Color(0xFFB01848)
    val onMineMuted = Color(0xDBFFFFFF)
    val metaTheirs = Color(0xFF7A4A58)

    fun mineBrush(): Brush = Brush.linearGradient(listOf(bubbleMineStart, bubbleMineEnd))

    val cameraBg = Color(0xFF000000)
    val cameraContent = Color(0xFFFFFFFF)
    val cameraScrim = Color(0x66000000)
    val cameraFaint = Color(0x66FFFFFF)
    val cameraError = Color(0xFFFFB4AB)

    val mapPlaceholderLight = Color(0xFFF6D5DE)
    val mapPlaceholderDark = Color(0xFF2A0F1C)

    /** Wallpaper swatches, same order as Store "color:0" … "color:5". */
    val wallpapers: List<Color> = listOf(
        Color(0xFFF6D5DC),
        Color(0xFFF6E6C8),
        Color(0xFFD5EBD8),
        Color(0xFFFFD6E2),
        Color(0xFFE4D7F6),
        Color(0xFF1A0A12),
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
        skyTop = Color(0xFFFFE9EF),
        skyMid = Color(0xFFFFF7F9),
        skyBottom = Color(0xFFFFFFFF),
        blobA = Color(0x8CFFFFFF),
        blobB = Color(0x66FFE3EA),
        blobC = Color(0x33FF5C86),
        glass = Color(0xB8FFFFFF),
        glassStrong = Color(0xD6FFFFFF),
        glassBorder = Color(0x99FFFFFF),
        ink = Color(0xFF1A0A12),
        inkMuted = Color(0xFF7A4A58),
        chipOn = Color(0xFFFFFFFF),
        chipOnInk = Color(0xFF1A0A12),
        pill = Color(0xFFD6245A),
        onPill = Color(0xFFFFFFFF),
        pillBorder = Color(0x00000000),
        knob = Color(0xFFFFFFFF),
        onKnob = Color(0xFFA3123E),
        shadow = Color(0x33A3123E),
        bubbleTheirs = Color(0xB8FFFFFF),
        navIdle = Color(0xFF8E5968),
        dialog = Color(0xFFFFF7F9),
        textPrimary = Color(0xFFA3123E),
        // White at 96% so chat text and photos do not show through a popup.
        menu = Color(0xF5FFFFFF),
        menuBorder = Color(0x33A3123E),
    )

    val dark = LdPalette(
        skyTop = Color(0xFF1A0A12),
        skyMid = Color(0xFF220D18),
        skyBottom = Color(0xFF2A0F1C),
        blobA = Color(0x33FF5C86),
        blobB = Color(0x28D6245A),
        blobC = Color(0x14FF5C86),
        glass = Color(0x2EFFFFFF),
        glassStrong = Color(0x40FFFFFF),
        glassBorder = Color(0x66FFFFFF),
        ink = Color(0xFFFFF7F9),
        inkMuted = Color(0xFFE4C6D0),
        chipOn = Color(0xFFFFFFFF),
        chipOnInk = Color(0xFF1A0A12),
        pill = Color(0xFFD6245A),
        onPill = Color(0xFFFFFFFF),
        pillBorder = Color(0x00000000),
        knob = Color(0xFFFFFFFF),
        onKnob = Color(0xFFA3123E),
        shadow = Color(0x66000000),
        bubbleTheirs = Color(0xE6FFFFFF),
        navIdle = Color(0xFFC9A3B0),
        dialog = Color(0xFF2A0F1C),
        textPrimary = Color(0xFFFF5C86),
        // Wine #2A0F1C at 97%. Light text (ink) sits on this fill.
        menu = Color(0xF72A0F1C),
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
     * Popup fill. Light is white at 96% opacity. Dark is wine #2A0F1C at 97%.
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

/**
 * Display, Title, Body, and Caption stay on the compact scale used by sheets.
 * [Hero], [Lead], and [PageTitle] are the editorial sizes for story screens.
 */
object LdType {
    val Hero = TextStyle(
        fontSize = 56.sp,
        lineHeight = 60.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-1.8).sp,
    )

    val Lead = TextStyle(
        fontSize = 40.sp,
        lineHeight = 44.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-1.2).sp,
    )

    val PageTitle = TextStyle(
        fontSize = 30.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.6).sp,
    )

    val Display: TextStyle
        @Composable get() = MaterialTheme.typography.headlineLarge

    val Title: TextStyle
        @Composable get() = MaterialTheme.typography.titleMedium

    val Body: TextStyle
        @Composable get() = MaterialTheme.typography.bodyLarge

    val Caption: TextStyle
        @Composable get() = MaterialTheme.typography.bodySmall
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(LdRadius.card),
    extraLarge = RoundedCornerShape(32.dp),
)

private val LightColors = lightColorScheme(
    primary = LdColors.primary,
    onPrimary = LdColors.white,
    primaryContainer = LdColors.tint,
    onPrimaryContainer = LdColors.navy,
    secondary = LdColors.deep,
    onSecondary = LdColors.white,
    secondaryContainer = LdColors.tint,
    onSecondaryContainer = LdColors.navy,
    background = LdColors.clear,
    onBackground = LdColors.navy,
    surface = LdColors.light.dialog,
    onSurface = LdColors.navy,
    surfaceVariant = LdColors.light.glass,
    onSurfaceVariant = LdColors.light.inkMuted,
    outline = Color(0xFF8A6570),
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
    primary = LdColors.glow,
    onPrimary = LdColors.navy,
    primaryContainer = LdColors.deep,
    onPrimaryContainer = LdColors.tint,
    secondary = Color(0xFFFFD6E2),
    onSecondary = LdColors.navy,
    secondaryContainer = LdColors.deep,
    onSecondaryContainer = LdColors.tint,
    background = LdColors.clear,
    onBackground = Color(0xFFFFF7F9),
    surface = LdColors.dark.dialog,
    onSurface = Color(0xFFFFF7F9),
    surfaceVariant = LdColors.dark.glass,
    onSurfaceVariant = LdColors.dark.inkMuted,
    outline = Color(0xFFC9A3B0),
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
    inverseSurface = Color(0xFFFFF7F9),
    inverseOnSurface = LdColors.navy,
    scrim = Color(0xCC000000),
)

@Composable
fun BetweenlyTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val animationsOn = Store.animationsFlow(context).collectAsState().value
    Motion.bind(animationsOn)
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

/**
 * Blush or wine gradient, soft blobs, and a static rose doodle tile.
 * The doodle is drawn once into a bitmap and painted at a very low alpha.
 */
@Composable
fun AppBackground(modifier: Modifier = Modifier) {
    val c = LdColors.colors
    val dark = isSystemInDarkTheme()
    val tile = remember(dark) { doodleTile(dark) }
    val doodleAlpha = if (dark) 0.08f else 0.07f
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(c.skyTop, c.skyMid, c.skyBottom))),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(c.blobA, radius = size.minDimension * 0.62f, center = Offset(size.width * 0.08f, size.height * 0.06f))
            drawCircle(c.blobB, radius = size.minDimension * 0.48f, center = Offset(size.width * 0.98f, size.height * 0.20f))
            drawCircle(c.blobC, radius = size.minDimension * 0.72f, center = Offset(size.width * 0.40f, size.height * 0.82f))
            val tw = tile.width.toFloat()
            val th = tile.height.toFloat()
            if (tw > 1f && th > 1f) {
                var y = 0f
                while (y < size.height) {
                    var x = 0f
                    while (x < size.width) {
                        drawImage(tile, topLeft = Offset(x, y), alpha = doodleAlpha)
                        x += tw
                    }
                    y += th
                }
            }
        }
    }
}

@Composable
fun Modifier.ldGlass(
    shape: Shape = RoundedCornerShape(LdRadius.card),
    strong: Boolean = false,
    solid: Boolean = false,
): Modifier {
    val c = LdColors.colors
    val fill = when {
        // Menu fill is nearly opaque, so ink stays AA on top of a photo or a light wallpaper.
        solid -> c.menu
        strong -> c.glassStrong
        else -> c.glass
    }
    val stroke = if (solid) c.menuBorder else c.glassBorder
    return this
        .shadow(elevation = 10.dp, shape = shape, ambientColor = c.shadow, spotColor = c.shadow)
        .clip(shape)
        .background(fill)
        .border(width = 1.dp, color = stroke, shape = shape)
}

/** Mostly opaque popup surface: theme menu fill plus a thin border. */
@Composable
fun Modifier.ldPopupFrame(shape: Shape = MaterialTheme.shapes.extraLarge): Modifier {
    return this.border(width = 1.dp, color = LdColors.colors.menuBorder, shape = shape)
}

/** Accent tap pill. Same control as [LdButton] primary. */
@Composable
fun PrimaryPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    LdButton(text = text, onClick = onClick, modifier = modifier, enabled = enabled)
}

/** Glass pill with accent text. Same control as [LdButton] secondary. */
@Composable
fun SecondaryPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    LdButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        style = LdButtonStyle.Secondary,
        enabled = enabled,
    )
}

@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    solid: Boolean = false,
) {
    val c = LdColors.colors
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(interaction)
            .size(48.dp)
            .ldGlass(CircleShape, strong = !solid, solid = solid)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        LuIcon(icon, contentDescription = description, tint = c.ink)
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    val c = LdColors.colors
    Column(
        modifier
            .ldGlass(RoundedCornerShape(LdRadius.tile))
            .padding(8.dp),
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
        LuIcon(Lu.Check, contentDescription = null, tint = LdColors.white, size = 12.dp)
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

private fun doodleTile(dark: Boolean): ImageBitmap {
    val size = 480
    val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val ink = if (dark) 0xFFFF5C86.toInt() else 0xFFA3123E.toInt()
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 2.4f
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
        color = ink
    }
    val dot = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL
        color = ink
    }
    val rnd = java.util.Random(0x10F1ED0DL)
    val margin = 28f
    repeat(22) {
        val x = margin + rnd.nextFloat() * (size - margin * 2f)
        val y = margin + rnd.nextFloat() * (size - margin * 2f)
        val s = 12f + rnd.nextFloat() * 14f
        when (rnd.nextInt(5)) {
            0 -> doodleHeart(canvas, paint, x, y, s)
            1 -> doodleCloud(canvas, paint, x, y, s)
            2 -> doodleStar(canvas, paint, x, y, s)
            3 -> doodlePlane(canvas, paint, x, y, s)
            else -> canvas.drawCircle(x, y, 2.2f, dot)
        }
    }
    return bitmap.asImageBitmap()
}

private fun doodleHeart(canvas: android.graphics.Canvas, paint: android.graphics.Paint, x: Float, y: Float, s: Float) {
    val path = android.graphics.Path()
    path.moveTo(x, y + s * 0.35f)
    path.cubicTo(x - s, y - s * 0.45f, x - s * 0.2f, y - s * 0.95f, x, y - s * 0.25f)
    path.cubicTo(x + s * 0.2f, y - s * 0.95f, x + s, y - s * 0.45f, x, y + s * 0.35f)
    canvas.drawPath(path, paint)
}

private fun doodleCloud(canvas: android.graphics.Canvas, paint: android.graphics.Paint, x: Float, y: Float, s: Float) {
    val path = android.graphics.Path()
    path.moveTo(x - s * 0.7f, y + s * 0.15f)
    path.cubicTo(x - s, y - s * 0.15f, x - s * 0.35f, y - s * 0.7f, x, y - s * 0.25f)
    path.cubicTo(x + s * 0.15f, y - s * 0.85f, x + s * 0.9f, y - s * 0.35f, x + s * 0.55f, y + s * 0.15f)
    path.cubicTo(x + s * 0.95f, y + s * 0.45f, x + s * 0.2f, y + s * 0.55f, x - s * 0.15f, y + s * 0.2f)
    canvas.drawPath(path, paint)
}

private fun doodleStar(canvas: android.graphics.Canvas, paint: android.graphics.Paint, x: Float, y: Float, s: Float) {
    val path = android.graphics.Path()
    path.moveTo(x, y - s)
    path.lineTo(x + s * 0.22f, y - s * 0.22f)
    path.lineTo(x + s, y)
    path.lineTo(x + s * 0.22f, y + s * 0.22f)
    path.lineTo(x, y + s)
    path.lineTo(x - s * 0.22f, y + s * 0.22f)
    path.lineTo(x - s, y)
    path.lineTo(x - s * 0.22f, y - s * 0.22f)
    path.close()
    canvas.drawPath(path, paint)
}

private fun doodlePlane(canvas: android.graphics.Canvas, paint: android.graphics.Paint, x: Float, y: Float, s: Float) {
    val path = android.graphics.Path()
    path.moveTo(x - s * 0.8f, y + s * 0.15f)
    path.lineTo(x + s * 0.85f, y - s * 0.55f)
    path.lineTo(x - s * 0.05f, y - s * 0.05f)
    path.close()
    canvas.drawPath(path, paint)
    canvas.drawLine(x - s * 0.05f, y - s * 0.05f, x + s * 0.2f, y + s * 0.5f, paint)
}
