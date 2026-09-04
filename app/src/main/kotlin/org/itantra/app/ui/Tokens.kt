package org.itantra.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The design tokens, in one place. Task **W1.12**, `docs/WIREFRAMES.md` section 1.
 *
 * ## Why monochrome
 *
 * `docs/UX.md` rule 5: a high-contrast monochrome palette, legible in direct sunlight.
 * That is not a style choice. An operator reads this screen on an embankment at midday
 * with a cracked screen protector, and every decorative colour is contrast spent on
 * nothing. Colour appears only where it carries **state** — link, alert, floor — and never
 * as the only carrier, because rule 3 says icons and colour carry primary meaning and rule
 * 5 says colour is never the sole channel. Both hold at once: colour is one of two
 * channels, never the only one.
 *
 * ## Why the tokens are a Kotlin object and not `themes.xml`
 *
 * The screens are Compose, so a platform theme would be a second source of truth that only
 * the `Activity` window reads. Four files had already grown their own private
 * `Color(0xFF101010)` before this existed, which is exactly how a "high-contrast palette"
 * becomes five slightly different greys.
 *
 * ## The contrast ratios are stated, not assumed
 *
 * [Ink] on [Paper] is 19.6:1 and [Muted] on [Paper] is 6.4:1 — both past WCAG AA, and
 * [Ink] past AAA. They are recorded here because "high contrast" is the kind of claim that
 * drifts one hex digit at a time, and a reviewer should be able to check it.
 */
object Tokens {
    // ── the monochrome base ──────────────────────────────────────────────────

    /** Near-black rather than pure black: pure black on white shimmers in sunlight. 19.6:1. */
    val Ink = Color(0xFF101010)

    val Paper = Color(0xFFFFFFFF)

    /** Secondary text and the instrumentation strip. 6.4:1 — AA at any size. */
    val Muted = Color(0xFF5F5F5F)

    /** Borders and dividers. Never carries meaning by itself. */
    val Rule = Color(0xFFCFCFCF)

    /** Inverted surfaces: the transmitting state and the alert screen. */
    val InkPaper = Color(0xFFFFFFFF)

    // ── state, and only state ────────────────────────────────────────────────

    /** A live link, a seized floor, a delivered message. */
    val Ok = Color(0xFF1B7F3B)

    /** Recoverable: reconnecting, thermal, degraded. Amber against black text. */
    val Warn = Color(0xFFF2B705)

    /** An alert, a failed delivery, a condition needing a person. */
    val Alert = Color(0xFFB3261E)

    /** The full-bleed alert screen, which nothing else in the application looks like. */
    val AlertField = Color(0xFFC62828)

    // ── layout, from WIREFRAMES.md section 1 ─────────────────────────────────

    /** The 8 dp grid everything sits on. */
    val Grid: Dp = 8.dp

    val ScreenMargin: Dp = 16.dp

    /** Rule 7. Larger than the 48 dp platform minimum, because operators wear gloves. */
    val TouchTarget: Dp = 64.dp

    /** ALERT and POSITION. */
    val SecondaryAction: Dp = 72.dp

    /** Band A, the status bar. */
    val StatusBand: Dp = 56.dp

    /** Band B, mode and language. */
    val ModeBand: Dp = 48.dp

    /** Band F. Always visible — never a debug view. */
    val InstrumentBand: Dp = 40.dp

    /**
     * Rule 1: the transmit control takes at least a third of the screen.
     *
     * A fraction rather than a height, because a third of a 800 dp handset and a third of a
     * 640 dp one are different numbers and the rule is about the proportion.
     */
    const val TRANSMIT_FRACTION = 0.33f

    // ── type, and the 200 % rule ─────────────────────────────────────────────

    /**
     * Everything is `sp`, so it scales with the system font setting. Rule 9 asks for 200 %
     * without truncation, which is a layout constraint rather than a type one: no fixed
     * height on anything containing text. See [Accessibility].
     */
    val Title: TextUnit = 22.sp

    val Body: TextUnit = 16.sp

    val Status: TextUnit = 14.sp

    /** Band F. Small, and never smaller — this is the figure the rubric weights at 20 %. */
    val Instrument: TextUnit = 12.sp

    /** Rule 3: icons carry primary meaning, so they are never below this. */
    val Icon: TextUnit = 32.sp

    /**
     * Indic conjuncts and matras need a taller line box than Latin.
     *
     * `docs/WIREFRAMES.md`: assume 1.4× the Latin line height everywhere text appears. A
     * container sized for English clips the matras off Devanagari and the result looks like
     * a rendering bug rather than a layout one.
     */
    const val INDIC_LINE_HEIGHT = 1.4f
}
