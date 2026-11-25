package app.loviedowpie.ui

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
import app.loviedowpie.R
import app.loviedowpie.Store
import app.loviedowpie.ui.icons.Lu
import app.loviedowpie.ui.icons.LuIcon
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
    /** Transparency checker. Same in light and dark so clear paper stays readable. */
    val checkerLight = Color(0xFFF4F4F6)
    val checkerDark = Color(0xFFD9D9DE)
    /** Warm plate under a brush sample. */
    val previewPlate = Color(0xFFF7F4EF)
    /** Music card wash. Deeper than [primary] so it sits next to [bubbleMine]. */
    val musicDeep = Color(0xFF0E4C9C)
    val musicWashTop = Color(0xFFB9DCFF)
    val musicWashBottom = Color(0xFF6EA8FF)

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

/** Display, Title, Body, and Caption. Sizes come from the existing font. */
object LdType {
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
 * Sky gradient, soft blobs, and a static doodle tile.
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

/** Black pill with a white arrow knob. Primary actions such as Continue. */
@Composable
fun PrimaryPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = LdColors.colors
    val shape = RoundedCornerShape(LdRadius.chip)
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .pressScale(interaction)
            .heightIn(min = 60.dp)
            .alpha(if (enabled) 1f else 0.42f)
            .shadow(elevation = 8.dp, shape = shape, ambientColor = c.shadow, spotColor = c.shadow)
            .clip(shape)
            .background(c.pill)
            .border(1.dp, c.pillBorder, shape)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(c.knob),
            contentAlignment = Alignment.Center,
        ) {
            LuIcon(Lu.ArrowRight, contentDescription = null, tint = c.onKnob)
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
    val shape = RoundedCornerShape(LdRadius.chip)
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(interaction)
            .heightIn(min = 48.dp)
            .alpha(if (enabled) 1f else 0.42f)
            .ldGlass(shape, strong = true)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
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
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(interaction)
            .size(48.dp)
            .ldGlass(CircleShape, strong = true)
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
    val ink = if (dark) 0xFFFFFFFF.toInt() else 0xFF0A1F44.toInt()
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
