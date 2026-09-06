package org.itantra.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The two palettes, and the one geometry they share.
 *
 * ## Why there are two
 *
 * `docs/UX.md` rule 5 asks for a high-contrast monochrome palette legible in direct
 * sunlight, and it is right: an operator reading this screen on an embankment at midday
 * needs contrast, not hue. But the same application is also read indoors, by a jury, on a
 * desk — and a screen that spends its entire contrast budget on being survivable is a
 * screen nobody can tell apart from a terminal emulator.
 *
 * So both exist. **Spectrum** is the default: seven families, each carrying one meaning.
 * **Field** is rule 5 restored exactly — the monochrome tokens that were here before this
 * file, unchanged, with colour surviving only on the three states that cannot lose it.
 *
 * ## The part that makes it safe
 *
 * **The two palettes share one geometry.** Every dimension, radius, type size and touch
 * target in [Tokens] is the same under both, so switching is a repaint and never a
 * relayout: nothing moves, nothing reflows, and an operator who learned the screen in one
 * palette has learned it in the other. That is the whole reason this is a palette swap and
 * not a theme — a theme would be licence to move things.
 *
 * ## Why colour is never the only carrier
 *
 * Rule 3 says icons and colour carry primary meaning; rule 5 says colour is never the sole
 * channel. Both hold at once, and the way they hold is that every coloured thing in this
 * interface also has a second carrier — an icon, a border, a dot, a word. The alert grid is
 * the clearest case: its tiles are Paper on a hairline and the emergency lives in the icon
 * alone, so the grid still separates in greyscale. Test it by building with [Field] and
 * checking that nothing became ambiguous; if something did, it was relying on hue.
 *
 * ## Five steps per family, and what each is for
 *
 * `tint` is a fill you can put text on. `mid` is a border. `track` is the unfilled part of
 * a meter, and it is a step of its own because the canvas draws a histogram's ground
 * (`#DCE9F2`) a clear shade off the border it sits beside (`#BEE3FB`) — a bar at zero must
 * not read as a bar with a value. `core` is the signal itself: the icon, the dot, the
 * filled control. `deep` is text on `tint`, and it is the only step guaranteed to pass AA
 * against its own family's tint.
 *
 * Do not invent a sixth. Five slightly different greens is exactly the drift [Tokens] was
 * written to stop.
 */
@Immutable
data class ItantraPalette(
    /** True when this is the sunlight-legible monochrome set. Screens that must not lose a
     *  distinction in greyscale can branch on it — sparingly, and never for layout. */
    val fieldMode: Boolean,
    // ── neutrals ─────────────────────────────────────────────────────────────
    /** Chrome and cards. Warmer than pure white, which shimmers under sun. */
    val paper: Color,
    /** The thread ground, a half-step warmer than [paper] so chrome and dock lift off it. */
    val ground: Color,
    /** Inset surfaces — a track, a well, a disabled field. */
    val sunken: Color,
    /** Borders and dividers. Never carries meaning by itself. */
    val hairline: Color,
    /** A border that has to be seen rather than merely respected. */
    val hairlineStrong: Color,
    /** Primary text. 19.6:1 on [paper]. */
    val ink: Color,
    /** Secondary text and instrumentation. 6.4:1 on [paper] — AA at any size. */
    val muted: Color,
    /** Text and glyphs on a `core` fill. */
    val onAccent: Color,
    // ── the seven families ───────────────────────────────────────────────────
    /** Identity. This unit, its name, its node, the transmit control. */
    val periwinkle: Family,
    /** The link, and anything live on it — a seized floor, a speaking bubble. */
    val aqua: Family,
    /** Measurement. Every figure a jury reads: bytes, milliseconds, ratios. */
    val sky: Family,
    /** Emergency. The alert path and nothing else, so it never reads as decoration. */
    val blush: Family,
    /** Navigation and preference — the control room, settings, appearance. */
    val orchid: Family,
    /** Success: delivered, all clear, installed, verified. */
    val mint: Family,
    /**
     * Recoverable trouble: link down, reconnecting, thermal, microphone lost.
     *
     * Distinct from [butter], and the distinction is the whole point of board 11 — a unit
     * that is *fixing itself* and a unit that is *holding something for later* are
     * different facts, and an operator who reads one as the other either waits for nothing
     * or walks toward a peer they did not need to reach.
     */
    val apricot: Family,
    /** Held rather than broken: queued sends, a dashed bubble, a pending pack. */
    val butter: Family,
    /**
     * A template-coded message, and the cross-language claim it carries.
     *
     * Its own family because it is its own kind of traffic: one byte on the wire, rendered
     * from a table rather than recognised, and spoken in the *receiver's* language. A
     * bubble saying *sent in தமிழ் · heard in हिन्दी* is the most interesting thing this
     * application does and it should not be drawn in the same colour as ordinary speech.
     */
    val fuchsia: Family,
) {
    /** One meaning, five steps. See the class KDoc for what each step is for. */
    @Immutable
    data class Family(
        val tint: Color,
        val mid: Color,
        val core: Color,
        val deep: Color,
        /** A meter's unfilled ground. Defaults to [mid] where the design draws no separate one. */
        val track: Color = mid,
    )

    // ── the three states colour may never be taken from ──────────────────────
    //
    // Rule 5 permits colour where it carries state. These three carry state that has no
    // adequate second carrier at a glance, so they survive into Field Mode unchanged --
    // they are the reason Field Mode is monochrome rather than greyscale.

    /** A live link, a delivered message, a seized floor. */
    val ok: Color get() = mint.core

    /** Recoverable: reconnecting, degraded, thermal. */
    val warn: Color get() = apricot.core

    /** An alert, a failed delivery, a condition needing a person. */
    val alert: Color get() = blush.core

    companion object {
        /**
         * The default. Seven families, from `iTantra Screens v2`.
         *
         * The hexes are the canvas's own, transcribed rather than approximated, so a
         * reviewer holding the design file beside this can check them one at a time.
         */
        val Spectrum =
            ItantraPalette(
                fieldMode = false,
                paper = Color(0xFFFDFCFB),
                ground = Color(0xFFF7F5F2),
                sunken = Color(0xFFF0EFEC),
                hairline = Color(0xFFE7E4E0),
                hairlineStrong = Color(0xFFB8B4AE),
                ink = Color(0xFF101010),
                muted = Color(0xFF5F5F5F),
                onAccent = Color(0xFFFFFFFF),
                periwinkle = Family(Color(0xFFE8EAFF), Color(0xFFC7CEFF), Color(0xFF4F5BD5), Color(0xFF2F3A8F)),
                aqua = Family(Color(0xFFDFF7F7), Color(0xFFB5EDEC), Color(0xFF0E7C7B), Color(0xFF0F4C4C)),
                sky = Family(Color(0xFFE4F3FE), Color(0xFFBEE3FB), Color(0xFF0369A1), Color(0xFF0C4A6E), track = Color(0xFFDCE9F2)),
                blush = Family(Color(0xFFFFE7EA), Color(0xFFFFC7CE), Color(0xFFBE123C), Color(0xFF9F1239)),
                orchid = Family(Color(0xFFF0EBFE), Color(0xFFD7C9FC), Color(0xFF7C3AED), Color(0xFF4C1D95)),
                mint = Family(Color(0xFFE3F7EC), Color(0xFFB8ECD0), Color(0xFF1B7F3B), Color(0xFF14532D)),
                apricot = Family(Color(0xFFFFEEDF), Color(0xFFFFD5B0), Color(0xFFC2410C), Color(0xFF7C2D12)),
                butter = Family(Color(0xFFFEF6DC), Color(0xFFFBE7A6), Color(0xFFB45309), Color(0xFF713F12)),
                fuchsia = Family(Color(0xFFFBEAFB), Color(0xFFF0CDF1), Color(0xFFA21CAF), Color(0xFF701A75)),
            )

        /**
         * `docs/UX.md` rule 5, restored exactly.
         *
         * Every value here is one of the monochrome tokens that predated this file, so
         * Field Mode is not a new palette — it is the old one, still authoritative, still
         * the thing to demonstrate to anyone who asks what happens in sunlight.
         *
         * The families collapse rather than disappear. A family's `core` becomes [Tokens.Ink]
         * where its meaning is structural, and stays coloured on the three families whose
         * meaning **is** the colour — mint, apricot and blush are ok, warn and alert. A
         * `tint` becomes Paper and a `mid` becomes Rule, so a bordered tile is still a
         * bordered tile and the layout is untouched.
         */
        val Field =
            ItantraPalette(
                fieldMode = true,
                paper = Tokens.Paper,
                ground = Tokens.Paper,
                sunken = Color(0xFFF2F2F2),
                hairline = Tokens.Rule,
                hairlineStrong = Tokens.Muted,
                ink = Tokens.Ink,
                muted = Tokens.Muted,
                onAccent = Tokens.InkPaper,
                periwinkle = Family(Tokens.Paper, Tokens.Rule, Tokens.Ink, Tokens.Ink),
                aqua = Family(Tokens.Paper, Tokens.Rule, Tokens.Ink, Tokens.Ink),
                sky = Family(Tokens.Paper, Tokens.Rule, Tokens.Ink, Tokens.Ink),
                orchid = Family(Tokens.Paper, Tokens.Rule, Tokens.Ink, Tokens.Ink),
                fuchsia = Family(Tokens.Paper, Tokens.Rule, Tokens.Ink, Tokens.Ink),
                // The four that keep their hue, because it is the state itself. Apricot and
                // butter are both amber here and are told apart by their icon and their
                // words -- which is the rule the whole palette is built on, applied to
                // itself: nothing in Field Mode is separated by colour alone either.
                mint = Family(Tokens.Paper, Tokens.Rule, Tokens.Ok, Tokens.Ok),
                apricot = Family(Tokens.Paper, Tokens.Rule, Tokens.Warn, Tokens.Ink),
                butter = Family(Tokens.Paper, Tokens.Rule, Tokens.Warn, Tokens.Ink),
                blush = Family(Tokens.Paper, Tokens.Rule, Tokens.Alert, Tokens.Alert),
            )
    }
}

/**
 * The palette in force.
 *
 * `static` rather than a normal `compositionLocalOf` because the palette changes about once
 * a session and reading it happens on every draw. A static local skips the invalidation
 * bookkeeping and re-composes the whole subtree on the rare write, which is the right trade
 * in that ratio.
 *
 * Defaults to [ItantraPalette.Spectrum] so a composable rendered outside [ItantraTheme] —
 * a preview, a unit test, a screenshot harness — draws in the real palette rather than
 * throwing or drawing in black.
 */
val LocalPalette = staticCompositionLocalOf { ItantraPalette.Spectrum }

/**
 * Puts a palette in force for [content].
 *
 * This is deliberately **not** a `MaterialTheme`. Material's colour scheme would be a second
 * source of truth that only the components this project does not use would read, and the
 * one thing [Tokens] exists to prevent is a second source of truth. Screens read
 * [LocalPalette] directly.
 *
 * @param fieldMode true restores the sunlight-legible monochrome. This is a **UI** decision
 *   and lives nowhere near the engine: no `AppState` field, no persistence through the
 *   protocol, nothing the radio can observe.
 */
@Composable
fun ItantraTheme(
    fieldMode: Boolean = false,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalPalette provides if (fieldMode) ItantraPalette.Field else ItantraPalette.Spectrum,
        content = content,
    )
}

/** Shorthand, so a screen reads `palette.ink` rather than `LocalPalette.current.ink`. */
val palette: ItantraPalette
    @Composable @ReadOnlyComposable
    get() = LocalPalette.current
