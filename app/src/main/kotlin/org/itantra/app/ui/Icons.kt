// The path data below is transcribed verbatim from the `<symbol>` definitions in
// `iTantra Screens v2.dc.html`. An SVG `d` attribute is a single indivisible token: wrapping
// one at column 120 would put a line break inside a string literal, which costs the one
// property that makes these glyphs auditable -- that a reader can search the design file for
// the icon's `i-` name and compare the two strings character for character. So the line
// limit is lifted for this file only, and for this reason only.
@file:Suppress("ktlint:standard:max-line-length")

package org.itantra.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Every glyph in the interface, drawn rather than fetched.
 *
 * ## Why these are not Material icons
 *
 * `androidx.compose.material:material-icons-extended` is nine megabytes of vector data for
 * the twenty-odd glyphs this application uses, and constraint **N2** allows the whole
 * installer thirty. It is also the wrong set: there is no Material glyph for *floor seized*,
 * for *the frame was relayed*, or for a transmit control that has to read as an antenna
 * radiating rather than as a microphone. `iTantra Screens v2` draws its own, and these are
 * those, transcribed from the canvas's `<symbol>` definitions by `tools/`-style generation
 * rather than by eye.
 *
 * ## Why the path data is verbatim
 *
 * Each `Part` below holds the exact `d` attribute from the design file. A circle or a rect
 * in the source became a path here — that is the only transformation applied, and it is
 * arithmetic rather than judgement. Anyone can diff a glyph against the canvas by searching
 * the design file for its `i-` name in the KDoc above it.
 *
 * ## Colour
 *
 * Every path is built in black and tinted at the call site, the way `Icon(tint = …)`
 * expects. That is what makes one glyph serve both palettes: `Icons.Alert` in Spectrum is
 * Blush, in Field Mode it is the same Alert red, and neither is baked in here.
 *
 * ## Rule 3
 *
 * `docs/UX.md` rule 3 — icons carry primary meaning — is why [Tokens.Icon] is 32.sp and why
 * these are line drawings at a 2 px stroke on a 24 unit grid rather than filled pictograms:
 * a filled glyph at 32 dp on a cracked screen protector in sunlight is a blob, and an
 * outlined one is still a shape.
 */
object Icons {
    /** One drawing instruction: a path, filled or stroked, with the source's own attributes. */
    private class Part(
        val d: String,
        val fill: Boolean = false,
        val width: Float = 2f,
        val cap: StrokeCap = StrokeCap.Butt,
        val join: StrokeJoin = StrokeJoin.Miter,
    )

    /**
     * Builds one glyph.
     *
     * `defaultWidth`/`defaultHeight` are 24 dp regardless of the viewport, so a 108-unit
     * glyph and a 24-unit one occupy the same box and a caller never has to know which is
     * which. [Tokens.Icon] then sizes them all at the call site.
     */
    private fun vector(
        name: String,
        viewport: Float,
        vararg parts: Part,
    ): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = viewport,
            viewportHeight = viewport,
        ).apply {
            parts.forEach { part ->
                addPath(
                    pathData = addPathNodes(part.d),
                    fill = if (part.fill) SolidColor(Color.Black) else null,
                    stroke = if (part.fill) null else SolidColor(Color.Black),
                    strokeLineWidth = part.width,
                    strokeLineCap = part.cap,
                    strokeLineJoin = part.join,
                )
            }
        }.build()

    /** `i-transmit` */
    val Transmit: ImageVector by lazy {
        vector(
            "Transmit",
            108f,
            Part("M46 54A8 8 0 1 0 62 54A8 8 0 1 0 46 54Z", fill = true),
            Part(
                "M66.7 41.3A18 18 0 0166.7 66.7M41.3 41.3A18 18 0 0041.3 66.7M75.2 32.8A30 30 0 0175.2 75.2M32.8 32.8A30 30 0 0032.8 75.2",
                width = 7f,
                cap = StrokeCap.Round,
            ),
        )
    }

    /** `i-openline` */
    val OpenLine: ImageVector by lazy {
        vector(
            "OpenLine",
            24f,
            Part(
                "M8.5 9.5a3.5 3.5 0 010 5M12.5 6.5a7.5 7.5 0 010 11M16.5 3.5a11.5 11.5 0 010 17",
                width = 2f,
                cap = StrokeCap.Round,
            ),
        )
    }

    /** `i-grid` */
    val Grid: ImageVector by lazy {
        vector(
            "Grid",
            24f,
            Part(
                "M5.7 3.5H8.3A2.2 2.2 0 0 1 10.5 5.7V8.3A2.2 2.2 0 0 1 8.3 10.5H5.7A2.2 2.2 0 0 1 3.5 8.3V5.7A2.2 2.2 0 0 1 5.7 3.5Z",
                width = 2f,
            ),
            Part(
                "M15.7 3.5H18.3A2.2 2.2 0 0 1 20.5 5.7V8.3A2.2 2.2 0 0 1 18.3 10.5H15.7A2.2 2.2 0 0 1 13.5 8.3V5.7A2.2 2.2 0 0 1 15.7 3.5Z",
                width = 2f,
            ),
            Part(
                "M5.7 13.5H8.3A2.2 2.2 0 0 1 10.5 15.7V18.3A2.2 2.2 0 0 1 8.3 20.5H5.7A2.2 2.2 0 0 1 3.5 18.3V15.7A2.2 2.2 0 0 1 5.7 13.5Z",
                width = 2f,
            ),
            Part(
                "M15.7 13.5H18.3A2.2 2.2 0 0 1 20.5 15.7V18.3A2.2 2.2 0 0 1 18.3 20.5H15.7A2.2 2.2 0 0 1 13.5 18.3V15.7A2.2 2.2 0 0 1 15.7 13.5Z",
                width = 2f,
            ),
        )
    }

    /** `i-back` */
    val Back: ImageVector by lazy {
        vector(
            "Back",
            24f,
            Part("M15 4.5L7.5 12l7.5 7.5", width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }

    /** `i-fwd` */
    val Forward: ImageVector by lazy {
        vector(
            "Forward",
            24f,
            Part("M9.5 5.5l6.5 6.5-6.5 6.5", width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }

    /** `i-caret` */
    val Caret: ImageVector by lazy {
        vector(
            "Caret",
            24f,
            Part("M6 9.5l6 6 6-6", width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }

    /** `i-replay` */
    val Replay: ImageVector by lazy {
        vector(
            "Replay",
            24f,
            Part("M20 12a8 8 0 11-2.6-5.9", width = 2f, cap = StrokeCap.Round),
            Part("M20 3.4V7h-3.6", width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }

    /** `i-speaking` */
    val Speaking: ImageVector by lazy {
        vector(
            "Speaking",
            24f,
            Part("M4.4 12A2.6 2.6 0 1 0 9.6 12A2.6 2.6 0 1 0 4.4 12Z", fill = true),
            Part("M13 8.5a5 5 0 010 7M17 5.5a9.5 9.5 0 010 13", width = 2f, cap = StrokeCap.Round),
        )
    }

    /** `i-tick` */
    val Tick: ImageVector by lazy {
        vector(
            "Tick",
            24f,
            Part("M4.5 12.8l4.8 4.8L19.5 6.5", width = 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }

    /** `i-tick2` */
    val TickDouble: ImageVector by lazy {
        vector(
            "TickDouble",
            24f,
            Part(
                "M2.5 12.8l4 4 7.5-8M9.5 16.8l1.5 1.5 9-9.6",
                width = 2.2f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }

    /** `i-cross` */
    val Cross: ImageVector by lazy {
        vector(
            "Cross",
            24f,
            Part("M6 6l12 12M18 6L6 18", width = 2.2f, cap = StrokeCap.Round),
        )
    }

    /** `i-alert` */
    val Alert: ImageVector by lazy {
        vector(
            "Alert",
            24f,
            Part("M12 3.6l9 15.8H3z", width = 2f, join = StrokeJoin.Round),
            Part("M12 9.2v4.4", width = 2f, cap = StrokeCap.Round),
            Part("M10.9 16.6A1.1 1.1 0 1 0 13.1 16.6A1.1 1.1 0 1 0 10.9 16.6Z", fill = true),
        )
    }

    /** `i-medical` */
    val Medical: ImageVector by lazy {
        vector(
            "Medical",
            24f,
            Part("M12 4.5v15M4.5 12h15", width = 2.4f, cap = StrokeCap.Round),
        )
    }

    /** `i-fire` */
    val Fire: ImageVector by lazy {
        vector(
            "Fire",
            24f,
            Part(
                "M12 3.2c3.4 3.6 5.4 5.8 5.4 8.9a5.4 5.4 0 01-10.8 0c0-3.1 2-5.3 5.4-8.9z",
                width = 2f,
                join = StrokeJoin.Round,
            ),
            Part(
                "M12 12.4c1.4 1.5 2.2 2.4 2.2 3.5a2.2 2.2 0 01-4.4 0c0-1.1.8-2 2.2-3.5z",
                width = 2f,
                join = StrokeJoin.Round,
            ),
        )
    }

    /** `i-flood` */
    val Flood: ImageVector by lazy {
        vector(
            "Flood",
            24f,
            Part(
                "M2.5 7.5c2-2.4 4.5-2.4 6.5 0s4.5 2.4 6.5 0 4.5-2.4 6-.6M2.5 13c2-2.4 4.5-2.4 6.5 0s4.5 2.4 6.5 0 4.5-2.4 6-.6M2.5 18.5c2-2.4 4.5-2.4 6.5 0s4.5 2.4 6.5 0 4.5-2.4 6-.6",
                width = 2f,
                cap = StrokeCap.Round,
            ),
        )
    }

    /** `i-evac` */
    val Evacuate: ImageVector by lazy {
        vector(
            "Evacuate",
            24f,
            Part(
                "M3.5 10.8L12 4l8.5 6.8M6.2 9.6V20h11.6V9.6",
                width = 2f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
            Part("M12 19v-6M9.4 15.4L12 12.8l2.6 2.6", width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }

    /** `i-extract` */
    val Extract: ImageVector by lazy {
        vector(
            "Extract",
            24f,
            Part("M5 12A7 7 0 1 0 19 12A7 7 0 1 0 5 12Z", width = 2f),
            Part("M12 1.5v3.5M12 19v3.5M1.5 12H5M19 12h3.5", width = 2f, cap = StrokeCap.Round),
            Part("M10.4 12A1.6 1.6 0 1 0 13.6 12A1.6 1.6 0 1 0 10.4 12Z", fill = true),
        )
    }

    /** `i-allclear` */
    val AllClear: ImageVector by lazy {
        vector(
            "AllClear",
            24f,
            Part("M3.6 12A8.4 8.4 0 1 0 20.4 12A8.4 8.4 0 1 0 3.6 12Z", width = 2f),
            Part("M8 12.4l3 3 5.2-5.8", width = 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }

    /** `i-mic` */
    val Mic: ImageVector by lazy {
        vector(
            "Mic",
            24f,
            Part(
                "M12 3.5a2.9 2.9 0 012.9 2.9v5.2a2.9 2.9 0 01-5.8 0V6.4A2.9 2.9 0 0112 3.5z",
                width = 2f,
                join = StrokeJoin.Round,
            ),
            Part("M6.4 11.2a5.6 5.6 0 0011.2 0M12 16.8V20M9 20h6", width = 2f, cap = StrokeCap.Round),
        )
    }

    /** `i-thermo` */
    val Thermal: ImageVector by lazy {
        vector(
            "Thermal",
            24f,
            Part(
                "M12 3.5a2.2 2.2 0 012.2 2.2v8.6a4.2 4.2 0 11-4.4 0V5.7A2.2 2.2 0 0112 3.5z",
                width = 2f,
                join = StrokeJoin.Round,
            ),
            Part("M10 17.4A2 2 0 1 0 14 17.4A2 2 0 1 0 10 17.4Z", fill = true),
        )
    }

    /** `i-storage` */
    val Storage: ImageVector by lazy {
        vector(
            "Storage",
            24f,
            Part(
                "M3.6 7c0-1.7 3.8-3 8.4-3s8.4 1.3 8.4 3-3.8 3-8.4 3-8.4-1.3-8.4-3z",
                width = 2f,
                join = StrokeJoin.Round,
            ),
            Part(
                "M3.6 12c0 1.7 3.8 3 8.4 3s8.4-1.3 8.4-3M3.6 7v10c0 1.7 3.8 3 8.4 3s8.4-1.3 8.4-3V7",
                width = 2f,
                cap = StrokeCap.Round,
            ),
        )
    }

    /** `i-bt` */
    val Bluetooth: ImageVector by lazy {
        vector(
            "Bluetooth",
            24f,
            Part(
                "M8.5 7.5L16 16.2 12 19.8V4.2l4 3.6L8.5 16.5",
                width = 2f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }

    /** `i-lockopen` */
    val LockOpen: ImageVector by lazy {
        vector(
            "LockOpen",
            24f,
            Part(
                "M7.5 10.5H16.5A2 2 0 0 1 18.5 12.5V18A2 2 0 0 1 16.5 20H7.5A2 2 0 0 1 5.5 18V12.5A2 2 0 0 1 7.5 10.5Z",
                width = 2f,
            ),
            Part("M9 10.5V7.8a3.4 3.4 0 016.8 0", width = 2f, cap = StrokeCap.Round),
        )
    }

    /** `i-download` */
    val Download: ImageVector by lazy {
        vector(
            "Download",
            24f,
            Part(
                "M12 3.5v10.8M7.6 10.4L12 14.8l4.4-4.4M4.5 19.5h15",
                width = 2f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }

    /** `i-play` */
    val Play: ImageVector by lazy {
        vector(
            "Play",
            24f,
            Part("M8 5.2l11 6.8-11 6.8z", fill = true),
        )
    }

    /**
     * `i-pause` — **drawn here, not ported.**
     *
     * Board 10 asks for `<use href="#i-pause">` on the HOLD flank and the canvas never
     * defines that symbol, so the reference resolves to nothing and the control renders as a
     * word with an empty square above it. Every other glyph in this file is transcribed from
     * a symbol that exists; this one is the set's vocabulary applied to a missing member —
     * two round-capped bars at the same weight and inset as [Play], which it sits beside.
     *
     * If the canvas later defines `i-pause`, replace this with the transcription and delete
     * the note.
     */
    val Pause: ImageVector by lazy {
        vector(
            "Pause",
            24f,
            Part("M9.5 6.5v11M14.5 6.5v11", width = 2.6f, cap = StrokeCap.Round),
        )
    }

    /** `i-globe` */
    val Globe: ImageVector by lazy {
        vector(
            "Globe",
            24f,
            Part("M3.6 12A8.4 8.4 0 1 0 20.4 12A8.4 8.4 0 1 0 3.6 12Z", width = 2f),
            Part("M3.6 12h16.8M12 3.6a13 13 0 000 16.8 13 13 0 000-16.8", width = 2f),
        )
    }

    /** `i-theme` */
    val Theme: ImageVector by lazy {
        vector(
            "Theme",
            24f,
            Part("M3.6 12A8.4 8.4 0 1 0 20.4 12A8.4 8.4 0 1 0 3.6 12Z", width = 2f),
            Part("M12 3.6a8.4 8.4 0 000 16.8z", fill = true),
        )
    }

    /** `i-chart` */
    val Chart: ImageVector by lazy {
        vector(
            "Chart",
            24f,
            Part("M4 20h16M6.5 20v-6.5M11.5 20V6.5M16.5 20v-9.5M21 20V4.5", width = 2f, cap = StrokeCap.Round),
        )
    }

    /** `i-qr` */
    val Qr: ImageVector by lazy {
        vector(
            "Qr",
            24f,
            Part(
                "M4 9.5v-5h5M15 4.5h5v5M20 14.5v5h-5M9 19.5H4v-5",
                width = 2f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
            Part("M9.5 9.5H14.5V14.5H9.5Z", fill = true),
        )
    }

    /** `i-plane` */
    val Aeroplane: ImageVector by lazy {
        vector(
            "Aeroplane",
            24f,
            Part(
                "M12 3l2 7.5 7 2.5v2l-7-1.2-1 4.7 2.5 1.8v1.2L12 20l-3.5 1.5v-1.2L11 18.5l-1-4.7-7 1.2v-2l7-2.5z",
                fill = true,
            ),
        )
    }

    /** `i-doc` */
    val Document: ImageVector by lazy {
        vector(
            "Document",
            24f,
            Part("M6 3.5h8l4 4V20.5H6z", width = 2f, join = StrokeJoin.Round),
            Part("M14 3.5v4h4M9 12h6M9 16h6", width = 2f, cap = StrokeCap.Round),
        )
    }
}
