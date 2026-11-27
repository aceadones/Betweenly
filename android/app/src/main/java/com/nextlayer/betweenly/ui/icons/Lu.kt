package com.nextlayer.betweenly.ui.icons

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nextlayer.betweenly.ui.Motion
import java.util.IdentityHashMap

/**
 * Lucide-style stroke icons (24×24, stroke 2, round caps and joins).
 * Paths are the Lucide set, ISC license — see THIRD-PARTY.md at the repo root.
 * Each icon is built on first use. [Icon] tints the black stroke at draw time.
 *
 * Closed bodies also carry a duotone fill we drew ourselves: the same tint at
 * [LuIcon]'s [secondaryAlpha], painted behind the stroke. Open paths stay
 * stroke-only. This is not a Font Awesome duotone font.
 *
 * [LuIcon] `selected` is the heavier tab state: same vector, a 1.08 scale,
 * and whatever secondary alpha the caller passes (the dock uses 0.38).
 */
object Lu {
    val MessageCircle: ImageVector by lazy { icon("M7.9 20A9 9 0 1 0 4 16.1L2 22Z") }
    val MapPin: ImageVector by lazy {
        val body = "M20 10c0 4.993-5.539 10.193-7.399 11.799a1 1 0 0 1-1.202 0C9.539 20.193 4 14.993 4 10a8 8 0 0 1 16 0"
        icon(body, circle(12f, 10f, 3f), fill = arrayOf(body + "z"))
    }
    val User: ImageVector by lazy {
        icon(
            "M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2",
            circle(12f, 7f, 4f),
        )
    }
    val Pencil: ImageVector by lazy {
        icon(
            "M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z",
            "m15 5 4 4",
        )
    }
    val PenLine: ImageVector by lazy {
        icon(
            "M13 21h8",
            "M16.376 3.622a1 1 0 0 1 3.002 3.002L7.368 18.635a2 2 0 0 1-.855.506l-2.872.838a.5.5 0 0 1-.62-.62l.838-2.872a2 2 0 0 1 .506-.854z",
        )
    }
    val Music: ImageVector by lazy {
        icon(
            "M9 18V5l12-2v13",
            circle(6f, 18f, 3f),
            circle(18f, 16f, 3f),
        )
    }
    val Send: ImageVector by lazy {
        icon(
            "M14.536 21.686a.5.5 0 0 0 .937-.024l6.5-19a.496.496 0 0 0-.635-.635l-19 6.5a.5.5 0 0 0-.024.937l7.93 3.18a2 2 0 0 1 1.112 1.11z",
            "m21.854 2.147-10.94 10.939",
        )
    }
    val Plus: ImageVector by lazy { icon("M5 12h14", "M12 5v14") }
    val CirclePlus: ImageVector by lazy { icon(circle(12f, 12f, 10f), "M12 8v8", "M8 12h8") }
    val ChevronLeft: ImageVector by lazy { icon("m15 18-6-6 6-6") }
    val ChevronRight: ImageVector by lazy { icon("m9 18 6-6-6-6") }
    val ChevronDown: ImageVector by lazy { icon("m6 9 6 6 6-6") }
    val ChevronUp: ImageVector by lazy { icon("m18 15-6-6-6 6") }
    val X: ImageVector by lazy { icon("M18 6 6 18", "m6 6 12 12") }
    val Camera: ImageVector by lazy {
        icon(
            "M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3l-2.5-3z",
            circle(12f, 13f, 3f),
        )
    }
    val SwitchCamera: ImageVector by lazy {
        icon(
            "M11 19H4a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2h5",
            "M13 5h7a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2h-5",
            circle(12f, 12f, 3f),
            "m18 22-3-3 3-3",
            "m6 2 3 3-3 3",
        )
    }
    val Image: ImageVector by lazy {
        icon(
            rrect(3f, 3f, 18f, 18f, 2f),
            circle(9f, 9f, 2f),
            "m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21",
        )
    }
    val ImagePlus: ImageVector by lazy {
        icon(
            "M16 5h6",
            "M19 2v6",
            "M21 11.5V19a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h7.5",
            "m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21",
            circle(9f, 9f, 2f),
        )
    }
    val FileText: ImageVector by lazy {
        icon(
            "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z",
            "M14 2v4a2 2 0 0 0 2 2h4",
            "M10 9H8",
            "M16 13H8",
            "M16 17H8",
        )
    }
    val Paperclip: ImageVector by lazy {
        icon("m21.44 11.05-9.19 9.19a6 6 0 0 1-8.49-8.49l8.57-8.57A4 4 0 1 1 18 8.84l-8.59 8.57a2 2 0 0 1-2.83-2.83l8.49-8.48")
    }
    val Smile: ImageVector by lazy {
        icon(
            circle(12f, 12f, 10f),
            "M8 14s1.5 2 4 2 4-2 4-2",
            "M9 9h.01",
            "M15 9h.01",
        )
    }
    val SmilePlus: ImageVector by lazy {
        icon(
            "M22 11v1a10 10 0 1 1-9-10",
            "M8 14s1.5 2 4 2 4-2 4-2",
            "M9 9h.01",
            "M15 9h.01",
            "M16 5h6",
            "M19 2v6",
        )
    }
    val Heart: ImageVector by lazy {
        icon("M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7Z")
    }
    val Reply: ImageVector by lazy {
        icon("M9 17 4 12 9 7", "M20 18v-2a4 4 0 0 0-4-4H4")
    }
    val Copy: ImageVector by lazy {
        icon(
            rrect(8f, 8f, 14f, 14f, 2f),
            "M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2",
        )
    }
    val Trash2: ImageVector by lazy {
        icon(
            "M3 6h18",
            "M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6",
            "M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2",
            "M10 11v6",
            "M14 11v6",
            fill = arrayOf("M5 6h14v14c0 1-1 2-2 2H7c-1 0-2-1-2-2z"),
        )
    }
    val Undo2: ImageVector by lazy {
        icon(
            "M9 14 4 9l5-5",
            "M4 9h10.5a5.5 5.5 0 0 1 5.5 5.5a5.5 5.5 0 0 1-5.5 5.5H11",
        )
    }
    val Redo2: ImageVector by lazy {
        icon(
            "m15 14 5-5-5-5",
            "M20 9H9.5A5.5 5.5 0 0 0 4 14.5A5.5 5.5 0 0 0 9.5 20H13",
        )
    }
    val Eraser: ImageVector by lazy {
        icon(
            "m7 21-4.3-4.3c-1-1-1-2.5 0-3.4l9.6-9.6c1-1 2.5-1 3.4 0l5.6 5.6c1 1 1 2.5 0 3.4L13 21",
            "M22 21H7",
            "m5 11 9 9",
        )
    }
    val Palette: ImageVector by lazy {
        icon(
            "M12 2C6.5 2 2 6.5 2 12s4.5 10 10 10c.926 0 1.648-.746 1.648-1.688 0-.437-.18-.835-.437-1.125-.29-.289-.438-.652-.438-1.125a1.64 1.64 0 0 1 1.668-1.668h1.996c3.051 0 5.555-2.503 5.555-5.554C21.965 6.012 17.461 2 12 2z",
            circle(13.5f, 6.5f, 0.5f),
            circle(17.5f, 10.5f, 0.5f),
            circle(8.5f, 7.5f, 0.5f),
            circle(6.5f, 12.5f, 0.5f),
        )
    }
    val Brush: ImageVector by lazy {
        icon(
            "m9.06 11.9 8.07-8.06a2.85 2.85 0 1 1 4.03 4.03l-8.06 8.08",
            "M7.07 14.94c-1.66 0-3 1.35-3 3.02 0 1.33-2.5 1.52-2 2.02 1.08 1.1 2.49 2.02 4 2.02 2.2 0 4-1.8 4-4.04a3.01 3.01 0 0 0-3-3.02z",
        )
    }
    val Highlighter: ImageVector by lazy {
        icon(
            "m9 11-6 6v3h9l3-3",
            "m22 12-4.6 4.6a2 2 0 0 1-2.8 0l-5.2-5.2a2 2 0 0 1 0-2.8L14 4",
        )
    }
    val SprayCan: ImageVector by lazy {
        icon(
            "M3 3h.01",
            "M7 5h.01",
            "M11 7h.01",
            "M3 7h.01",
            "M7 9h.01",
            "M3 11h.01",
            rrect(15f, 5f, 4f, 4f, 0.5f),
            "m19 9 2 2v10c0 .6-.4 1-1 1h-6c-.6 0-1-.4-1-1V11l2-2",
            "m13 14 8-2",
            "m13 19 8-2",
        )
    }
    val Droplet: ImageVector by lazy {
        icon("M12 22a7 7 0 0 0 7-7c0-2-1-3.9-3-5.5s-3.5-4-4-6.5c-.5 2.5-2 4.9-4 6.5C6 11.1 5 13 5 15a7 7 0 0 0 7 7z")
    }
    val Droplets: ImageVector by lazy {
        icon(
            "M7 16.3c2.2 0 4-1.83 4-4.05 0-1.16-.57-2.26-1.71-3.19S7.29 6.75 7 5.3c-.29 1.45-1.14 2.84-2.29 3.76S3 11.1 3 12.25c0 2.22 1.8 4.05 4 4.05z",
            "M12.56 6.6A10.97 10.97 0 0 0 14 3.02c.5 2.5 2 4.9 4 6.5s3 3.5 3 5.5a6.98 6.98 0 0 1-11.91 4.97",
        )
    }
    val Download: ImageVector by lazy {
        icon(
            "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4",
            "m7 10 5 5 5-5",
            "M12 15V3",
        )
    }
    val Save: ImageVector by lazy {
        icon(
            "M15.2 3a2 2 0 0 1 1.4.6l3.8 3.8a2 2 0 0 1 .6 1.4V19a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z",
            "M17 21v-7a1 1 0 0 0-1-1H8a1 1 0 0 0-1 1v7",
            "M7 3v4a1 1 0 0 0 1 1h7",
        )
    }
    val Share2: ImageVector by lazy {
        icon(
            circle(18f, 5f, 3f),
            circle(6f, 12f, 3f),
            circle(18f, 19f, 3f),
            "M8.59 13.51 15.42 17.49",
            "M15.41 6.51 8.59 10.49",
        )
    }
    val Bell: ImageVector by lazy {
        val body = "M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9"
        icon(body, "M10.3 21a1.94 1.94 0 0 0 3.4 0", fill = arrayOf(body + "z"))
    }
    val BellRing: ImageVector by lazy {
        val body = "M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9"
        icon(
            body,
            "M10.3 21a1.94 1.94 0 0 0 3.4 0",
            "M4 2C2.8 3.7 2 5.7 2 8",
            "M22 8c0-2.3-.8-4.3-2-6",
            fill = arrayOf(body + "z"),
        )
    }
    val Settings: ImageVector by lazy {
        icon(
            "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z",
            circle(12f, 12f, 3f),
        )
    }
    val EllipsisVertical: ImageVector by lazy {
        icon(circle(12f, 12f, 1f), circle(12f, 5f, 1f), circle(12f, 19f, 1f))
    }
    val Ellipsis: ImageVector by lazy {
        icon(circle(12f, 12f, 1f), circle(19f, 12f, 1f), circle(5f, 12f, 1f))
    }
    val Check: ImageVector by lazy { icon("M20 6 9 17l-5-5") }
    val CheckCheck: ImageVector by lazy { icon("M18 6 7 17l-5-5", "m22 10-7.5 7.5L13 16") }
    val Clock: ImageVector by lazy { icon(circle(12f, 12f, 10f), "M12 6v6l4 2") }
    val Battery: ImageVector by lazy { icon(rrect(2f, 7f, 16f, 10f, 2f), "M22 11v2") }
    val BatteryCharging: ImageVector by lazy {
        icon(
            "M15 7h1a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2h-2",
            "M6 7H4a2 2 0 0 0-2 2v6a2 2 0 0 0 2 2h1",
            "m11 7-3 5h4l-3 5",
            "M22 11v2",
        )
    }
    val Navigation: ImageVector by lazy { icon("m3 11 19-9-9 19-2-8-8-2z") }
    val Radio: ImageVector by lazy {
        icon(
            "M4.9 19.1C1 15.2 1 8.8 4.9 4.9",
            "M7.8 16.2c-2.3-2.3-2.3-6.1 0-8.5",
            circle(12f, 12f, 2f),
            "M16.2 7.8c2.3 2.3 2.3 6.1 0 8.5",
            "M19.1 4.9C23 8.8 23 15.1 19.1 19",
        )
    }
    val Play: ImageVector by lazy {
        icon("M5 5a2 2 0 0 1 3.008-1.728l11.997 6.998a2 2 0 0 1 .003 3.458l-12 7A2 2 0 0 1 5 19z")
    }
    val Pause: ImageVector by lazy { icon(rrect(14f, 4f, 4f, 16f, 1f), rrect(6f, 4f, 4f, 16f, 1f)) }
    val SkipForward: ImageVector by lazy { icon("m5 4 10 8-10 8V4z", "M19 5v14") }
    val SkipBack: ImageVector by lazy { icon("m19 20-10-8 10-8v16z", "M5 19V5") }
    val Search: ImageVector by lazy { icon(circle(11f, 11f, 8f), "m21 21-4.3-4.3") }
    val ListMusic: ImageVector by lazy {
        icon(
            "M21 15V6",
            "M18.5 18a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Z",
            "M12 12H3",
            "M16 6H3",
            "M12 18H3",
        )
    }
    val Volume2: ImageVector by lazy {
        icon(
            "M11 5 6 9H2v6h4l5 4V5z",
            "M15.54 8.46a5 5 0 0 1 0 7.07",
            "M19.07 4.93a10 10 0 0 1 0 14.14",
        )
    }
    val Link: ImageVector by lazy {
        icon(
            "M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71",
            "M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71",
        )
    }
    val RefreshCw: ImageVector by lazy {
        icon(
            "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8",
            "M21 3v5h-5",
            "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16",
            "M8 16H3v5",
        )
    }
    val Zap: ImageVector by lazy {
        icon("M4 14a1 1 0 0 1-.78-1.63l9.9-10.2a.5.5 0 0 1 .86.46l-1.92 6.02A1 1 0 0 0 13 10h7a1 1 0 0 1 .78 1.63l-9.9 10.2a.5.5 0 0 1-.86-.46l1.92-6.02A1 1 0 0 0 11 14z")
    }
    val Sun: ImageVector by lazy {
        icon(
            circle(12f, 12f, 4f),
            "M12 2v2",
            "M12 20v2",
            "m4.93 4.93 1.41 1.41",
            "m17.66 17.66 1.41 1.41",
            "M2 12h2",
            "M20 12h2",
            "m6.34 17.66-1.41 1.41",
            "m19.07 4.93-1.41 1.41",
        )
    }
    val Moon: ImageVector by lazy { icon("M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z") }
    val Lock: ImageVector by lazy {
        icon(rrect(3f, 11f, 18f, 11f, 2f), "M7 11V7a5 5 0 0 1 10 0v4")
    }
    val ShieldCheck: ImageVector by lazy {
        val body = "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z"
        icon(body, "m9 12 2 2 4-4", fill = arrayOf(body + "z"))
    }
    val Info: ImageVector by lazy { icon(circle(12f, 12f, 10f), "M12 16v-4", "M12 8h.01") }
    val ArrowDown: ImageVector by lazy { icon("M12 5v14", "m19 12-7 7-7-7") }
    val ArrowLeft: ImageVector by lazy { icon("m12 19-7-7 7-7", "M19 12H5") }
    val ArrowRight: ImageVector by lazy { icon("M5 12h14", "m12 5 7 7-7 7") }
    val Sparkles: ImageVector by lazy {
        icon(
            "M9.937 15.5A2 2 0 0 0 8.5 14.063l-6.135-1.582a.5.5 0 0 1 0-.962L8.5 9.936A2 2 0 0 0 9.937 8.5l1.582-6.135a.5.5 0 0 1 .963 0L14.063 8.5A2 2 0 0 0 15.5 9.937l6.135 1.581a.5.5 0 0 1 0 .964L15.5 14.063a2 2 0 0 0-1.437 1.437l-1.582 6.135a.5.5 0 0 1-.963 0z",
            "M20 3v4",
            "M22 5h-4",
            "M4 17v2",
            "M5 18H3",
        )
    }
    val Layers: ImageVector by lazy {
        icon(
            "m12.83 2.18a2 2 0 0 0-1.66 0L2.6 6.08a1 1 0 0 0 0 1.83l8.58 3.91a2 2 0 0 0 1.66 0l8.58-3.9a1 1 0 0 0 0-1.83Z",
            "m22 17.65-9.17 4.16a2 2 0 0 1-1.66 0L2 17.65",
            "m22 12.65-9.17 4.16a2 2 0 0 1-1.66 0L2 12.65",
        )
    }
    val Ban: ImageVector by lazy { icon(circle(12f, 12f, 10f), "m4.9 4.9 14.2 14.2") }
    val SlidersHorizontal: ImageVector by lazy {
        icon(
            "M21 4h-7",
            "M10 4H3",
            "M21 12h-9",
            "M8 12H3",
            "M21 20h-5",
            "M12 20H3",
            "M14 2v4",
            "M8 10v4",
            "M16 18v4",
        )
    }
    val CircleDot: ImageVector by lazy { icon(circle(12f, 12f, 10f), circle(12f, 12f, 1f)) }
    val PaintBucket: ImageVector by lazy {
        icon(
            "m19 11-8-8-8.6 8.6a2 2 0 0 0 0 2.8l5.2 5.2c.8.8 2 .8 2.8 0L19 11Z",
            "m5 2 5 5",
            "M2 13h15",
            "M22 20a2 2 0 1 1-4 0c0-1.6 1.7-2.4 2-4 .3 1.6 2 2.4 2 4Z",
        )
    }
    val Maximize2: ImageVector by lazy {
        icon("M15 3h6v6", "m21 3-7 7", "M9 21H3v-6", "m3 21 7-7")
    }
    val Minimize2: ImageVector by lazy {
        icon("M4 14h6v6", "m3 21 7-7", "M20 10h-6V4", "m21 3-7 7")
    }
    val Users: ImageVector by lazy {
        icon(
            "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2",
            circle(9f, 7f, 4f),
            "M22 21v-2a4 4 0 0 0-3-3.87",
            "M16 3.13a4 4 0 0 1 0 7.75",
        )
    }
    val Headphones: ImageVector by lazy {
        icon(
            "M3 14h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a9 9 0 0 1 18 0v7a2 2 0 0 1-2 2h-1a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2h3",
            fill = arrayOf(rrect(3f, 14f, 5f, 7f, 2f), rrect(16f, 14f, 5f, 7f, 2f)),
        )
    }
    val Disc3: ImageVector by lazy {
        icon(
            circle(12f, 12f, 10f),
            "M6 12c0-1.7.7-3.2 1.8-4.2",
            circle(12f, 12f, 2f),
            "M18 12c0 1.7-.7 3.2-1.8 4.2",
        )
    }
    val Mic: ImageVector by lazy {
        icon(
            "M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3Z",
            "M19 10v2a7 7 0 0 1-14 0v-2",
            "M12 19v3",
        )
    }
    val Video: ImageVector by lazy {
        icon(
            "m16 13 5.223 3.482a.5.5 0 0 0 .777-.416V7.87a.5.5 0 0 0-.752-.432L16 10.5",
            rrect(2f, 6f, 14f, 12f, 2f),
        )
    }
    val Timer: ImageVector by lazy { icon("M10 2h4", "m12 14 3-3", circle(12f, 14f, 8f)) }
    val Gauge: ImageVector by lazy { icon("m12 14 4-4", "M3.34 19a10 10 0 1 1 17.32 0") }
    val Eye: ImageVector by lazy {
        icon("M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7Z", circle(12f, 12f, 3f))
    }
    val EyeOff: ImageVector by lazy {
        icon(
            "M9.88 9.88a3 3 0 1 0 4.24 4.24",
            "M10.73 5.08A10.43 10.43 0 0 1 12 5c7 0 10 7 10 7a13.16 13.16 0 0 1-1.67 2.68",
            "M6.61 6.61A13.526 13.526 0 0 0 2 12s3 7 10 7a9.74 9.74 0 0 0 5.39-1.61",
            "M2 2l20 20",
        )
    }
    val Trash: ImageVector by lazy {
        icon(
            "M3 6h18",
            "M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6",
            "M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2",
            fill = arrayOf("M5 6h14v14c0 1-1 2-2 2H7c-1 0-2-1-2-2z"),
        )
    }
    val Edit3: ImageVector by lazy {
        icon("M12 20h9", "M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4Z")
    }
    val Pin: ImageVector by lazy {
        icon(
            "M12 17v5",
            "M9 10.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V7a1 1 0 0 1 1-1 2 2 0 0 0 0-4H8a2 2 0 0 0 0 4 1 1 0 0 1 1 1z",
        )
    }
    val Star: ImageVector by lazy {
        icon("M11.525 2.295a.53.53 0 0 1 .95 0l2.31 4.679a2.123 2.123 0 0 0 1.595 1.16l5.166.756a.53.53 0 0 1 .294.904l-3.736 3.638a2.123 2.123 0 0 0-.611 1.878l.882 5.14a.53.53 0 0 1-.771.56l-4.618-2.428a2.122 2.122 0 0 0-1.973 0L6.396 21.01a.53.53 0 0 1-.77-.56l.881-5.139a2.122 2.122 0 0 0-.611-1.879L2.16 9.795a.53.53 0 0 1 .294-.906l5.165-.755a2.122 2.122 0 0 0 1.597-1.16z")
    }
    val LocateFixed: ImageVector by lazy {
        icon(
            "M2 12h3",
            "M19 12h3",
            "M12 2v3",
            "M12 19v3",
            circle(12f, 12f, 7f),
            circle(12f, 12f, 3f),
        )
    }
    val Home: ImageVector by lazy {
        icon(
            "M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8",
            "M3 10a2 2 0 0 1 .709-1.528l7-5.999a2 2 0 0 1 2.582 0l7 5.999A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z",
        )
    }

    /** Failed-send mark. Not in the required name list; Lucide's triangle-alert. */
    val TriangleAlert: ImageVector by lazy {
        val body = "m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3"
        icon(body, "M12 9v4", "M12 17h.01", fill = arrayOf(body + "z"))
    }

    val Wallpaper: ImageVector get() = Image
    val Sliders: ImageVector get() = SlidersHorizontal
    val Flashlight: ImageVector get() = Zap
    val Crosshair: ImageVector get() = LocateFixed
}

@Composable
fun LuIcon(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = 24.dp,
    selected: Boolean = false,
    duo: Boolean = true,
    secondaryAlpha: Float = 0.28f,
) {
    val bump by animateFloatAsState(
        targetValue = if (selected) 1.08f else 1f,
        animationSpec = Motion.tween<Float>(Motion.FAST, Motion.easeOut),
        label = "lu",
    )
    val fill = if (duo) luDuoFill(icon) else null
    val scaled = modifier
        .size(size)
        .graphicsLayer {
            scaleX = bump
            scaleY = bump
        }
    if (fill == null) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = scaled,
            tint = tint,
        )
        return
    }
    val soft = secondaryAlpha.coerceIn(0f, 1f)
    Box(scaled, contentAlignment = Alignment.Center) {
        Icon(
            imageVector = fill,
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            tint = tint.copy(alpha = tint.alpha * soft),
        )
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.matchParentSize(),
            tint = tint,
        )
    }
}

private val duoFills = IdentityHashMap<ImageVector, ImageVector>()
private val duoLock = Any()

internal fun luDuoFill(icon: ImageVector): ImageVector? = synchronized(duoLock) { duoFills[icon] }

private fun icon(vararg paths: String, fill: Array<out String> = emptyArray()): ImageVector {
    val vector = ImageVector.Builder(
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        for (data in paths) {
            addPath(
                pathData = PathParser().parsePathString(data).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()
    val layers = LinkedHashSet<String>()
    for (data in paths) {
        if (closedPath(data)) layers.add(data)
    }
    for (data in fill) layers.add(data)
    if (layers.isNotEmpty()) {
        val painted = ImageVector.Builder(
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            for (data in layers) {
                addPath(
                    pathData = PathParser().parsePathString(data).toNodes(),
                    fill = SolidColor(Color.Black),
                )
            }
        }.build()
        synchronized(duoLock) { duoFills[vector] = painted }
    }
    return vector
}

/** Closed Lucide bodies, plus the two-arc circles from [circle]. Open strokes stay out. */
private fun closedPath(data: String): Boolean {
    val trimmed = data.trim()
    if (trimmed.length < 8) return false
    if (trimmed.endsWith("z", ignoreCase = true)) return true
    var arcs = 0
    for (ch in trimmed) {
        if (ch == 'a' || ch == 'A') arcs++
    }
    return arcs >= 2 && trimmed.contains("0 1 0")
}

private fun circle(cx: Float, cy: Float, r: Float): String {
    val d = r * 2f
    return "M${cx - r} $cy a$r $r 0 1 0 $d 0 a$r $r 0 1 0 ${-d} 0"
}

private fun rrect(x: Float, y: Float, w: Float, h: Float, r: Float): String {
    val rr = r.coerceAtMost(minOf(w, h) / 2f)
    val right = x + w
    val bottom = y + h
    if (rr <= 0f) return "M$x $y H$right V$bottom H$x Z"
    return "M${x + rr} $y H${right - rr} A$rr $rr 0 0 1 $right ${y + rr} V${bottom - rr} A$rr $rr 0 0 1 ${right - rr} $bottom H${x + rr} A$rr $rr 0 0 1 $x ${bottom - rr} V${y + rr} A$rr $rr 0 0 1 ${x + rr} $y Z"
}
