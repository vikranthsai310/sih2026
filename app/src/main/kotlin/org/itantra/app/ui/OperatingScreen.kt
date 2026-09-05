package org.itantra.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.itantra.audio.EngineState

/**
 * The main screen. Tasks **W1.11**, **W1.31**, **W1.32**, **W1.34**;
 * `docs/WIREFRAMES.md` sections 4 and 5.
 *
 * ## The six bands, in the same order on every screen
 *
 * ```
 *   A  status              56 dp
 *   B  mode and language   48 dp
 *   C  transmit            ≥ 33 % of the screen      rule 1
 *   D  alert and position  72 dp
 *   E  recent traffic      flexible                  rule 6
 *   F  instrumentation     40 dp, always visible
 * ```
 *
 * An operator who has learned one screen has learned all of them, which is why the bands
 * do not move and do not resize between states. Only band C changes appearance, and it
 * changes by **inverting** — the loudest possible signal that the unit is transmitting,
 * visible from across a room and without reading anything.
 *
 * ## Band F is not a debug view
 *
 * It is the single most persuasive element on the screen for a rubric that weights latency
 * at twenty per cent, and hiding it behind a developer toggle wastes it. It is also the
 * reason [org.itantra.bench.UtteranceClock] runs on the live path: these are the numbers
 * from the utterance that just happened, not from a benchmark.
 *
 * ## What this screen replaced
 *
 * The week-2 bring-up screen with a text field, which task **W3.12** required be deleted
 * once speech replaced typing. Keeping it would have left an application whose fastest
 * route to sending a message was to type it — which is the one thing this project exists
 * not to do.
 */
@Composable
fun OperatingScreen(
    state: OperatingState,
    onTransmitChange: (Boolean) -> Unit,
    onAlert: () -> Unit,
    onPosition: () -> Unit,
    onLanguageSelected: (String) -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(Tokens.Paper)
            // `targetSdk 35` draws edge to edge on Android 15 with no opt-out, so without
            // this band A sits under the status bar and band F under the gesture pill —
            // the two bands the layout guarantees are always visible.
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        StatusBand(state, onMenu)
        ModeBand(state, onLanguageSelected)

        // Band C. `weight` rather than a fixed height, so it grows with the screen and
        // still satisfies rule 1 on a taller handset.
        TransmitBand(
            state = state,
            onTransmitChange = onTransmitChange,
            modifier = Modifier.fillMaxWidth().weight(TRANSMIT_WEIGHT),
        )

        // The partial hypothesis sits between C and D while transmitting, so an operator
        // can see what the machine heard before it goes — rule 6, and the sender's last
        // chance to notice a misrecognition.
        if (state.transmitting || state.partial != null) {
            PartialBand(state)
        }
        state.speechNote?.let { SpeechNote(it) }

        SecondaryBand(onAlert, onPosition)
        TrafficBand(state, modifier = Modifier.fillMaxWidth().weight(TRAFFIC_WEIGHT))
        state.degraded?.let { DegradedBanner(it) }
        InstrumentBand(state)
    }
}

/** Everything the screen needs, and nothing about how it was obtained. */
data class OperatingState(
    val unitName: String,
    val nodeId: Int,
    val peerCount: Int,
    val linkUp: Boolean,
    val transportName: String,
    val mode: String,
    val audience: String,
    /** This unit's language in its own script, for band B. */
    val language: String,
    /** Its code, so the menu can mark which row is current without matching on script. */
    val languageCode: String = "",
    /** Every language this profile carries, for the band B menu. */
    val languages: List<LanguageOption> = emptyList(),
    val transmitting: Boolean = false,
    /** The running hypothesis while the operator is still speaking. */
    val partial: String? = null,
    val confidence: Int? = null,
    /** Microphone level while the control is held, 0..1. Drives the meter in band C. */
    val level: Float = 0f,
    /**
     * Why the last press produced no words, when it produced none.
     *
     * Shown rather than swallowed: a template arriving in place of what the operator
     * actually said is the single most misleading thing this screen could do quietly.
     */
    val speechNote: String? = null,
    val messages: List<LoggedMessage> = emptyList(),
    val degraded: EngineState.Degraded.Reason? = null,
    val metrics: BandFMetrics = BandFMetrics(),
    /** Frames held because the link is down. Zero is not shown. */
    val queued: Int = 0,
)

/**
 * Band F's numbers, from the utterance that just happened.
 *
 * Every one is nullable and an absent one is drawn as `—` rather than as zero. A zero
 * millisecond stage and a stage that was never measured are different facts, and this is
 * the strip a jury photographs.
 */
data class BandFMetrics(
    val sttMillis: Long? = null,
    val linkMillis: Long? = null,
    val ttsMillis: Long? = null,
    val totalMillis: Long? = null,
    val realTimeFactor: Double? = null,
    val cpuPercent: Double? = null,
    val lastFrameBytes: Int? = null,
) {
    /** The compression figure the demonstration points at, against three seconds of audio. */
    val compressionRatio: Int?
        get() = lastFrameBytes?.takeIf { it > 0 }?.let { Math.round(RAW_AUDIO_BYTES / it).toInt() }

    private companion object {
        const val RAW_AUDIO_BYTES = 96_000.0
    }
}

// ── A ────────────────────────────────────────────────────────────────────────

@Composable
private fun StatusBand(
    state: OperatingState,
    onMenu: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Tokens.StatusBand)
            .padding(horizontal = Tokens.ScreenMargin),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .heightIn(min = Tokens.TouchTarget)
                .width(Tokens.TouchTarget)
                .clickable { onMenu() }
                .semantics(mergeDescendants = true) { contentDescription = "Menu" },
            contentAlignment = Alignment.Center,
        ) {
            Text("☰", fontSize = Tokens.Icon, color = Tokens.Ink)
        }
        Column(Modifier.weight(1f)) {
            Text(
                "${state.unitName} · node ${"%02d".format(state.nodeId)}",
                fontSize = Tokens.Status,
                fontWeight = FontWeight.Bold,
                color = Tokens.Ink,
            )
            Text(
                "${state.peerCount} units" + if (state.queued > 0) " · ${state.queued} queued" else "",
                fontSize = Tokens.Instrument,
                color = Tokens.Muted,
            )
        }
        // The dot is the colour channel; the words are the text channel. Rule 5 asks for
        // both, so removing either still leaves the state readable.
        Text(
            if (state.linkUp) "● LINK OK" else "○ NO LINK",
            fontSize = Tokens.Status,
            fontWeight = FontWeight.Bold,
            color = if (state.linkUp) Tokens.Ok else Tokens.Alert,
            modifier =
                Modifier.semantics {
                    contentDescription =
                        if (state.linkUp) {
                            "Link up over ${state.transportName}, ${state.peerCount} units"
                        } else {
                            "No link. ${state.queued} messages waiting."
                        }
                },
        )
    }
    Divider()
}

// ── B ────────────────────────────────────────────────────────────────────────

@Composable
private fun ModeBand(
    state: OperatingState,
    onLanguageSelected: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Tokens.ModeBand)
            .padding(horizontal = Tokens.ScreenMargin),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            "${state.mode} · ${state.audience}",
            fontSize = Tokens.Status,
            color = Tokens.Ink,
        )
        // The language in its own script, never a code. A speaker of Odia is looking for
        // ଓଡ଼ିଆ, and "or" means nothing to anyone.
        //
        // A `Box` rather than the minimum height on the `Text` itself: a 64 dp tall text
        // node draws its glyphs at the top of that box, which put the language a third of a
        // line above the mode beside it and read as a rendering fault.
        Box {
            Box(
                Modifier
                    .heightIn(min = Tokens.TouchTarget)
                    .clickable { open = true }
                    .padding(horizontal = Tokens.Grid)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Language ${state.language}. Change."
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("▾ ${state.language}", fontSize = Tokens.Body, color = Tokens.Ink)
            }

            // The chevron promised a menu and delivered a cycle: each tap advanced one
            // language, so reaching Odia from Hindi was eight taps through eight scripts an
            // operator did not want, with no way back but to go round again. It also made
            // the control unusable without sight, since nothing announced the destination
            // before arriving at it.
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                state.languages.forEach { option ->
                    val current = option.code == state.languageCode
                    DropdownMenuItem(
                        onClick = {
                            open = false
                            onLanguageSelected(option.code)
                        },
                        text = {
                            Column(
                                Modifier.semantics(mergeDescendants = true) {
                                    contentDescription =
                                        option.nativeName + ", " + option.englishName +
                                        if (current) ". Current." else ""
                                },
                            ) {
                                Text(
                                    // Own script first and largest: a speaker of Odia is
                                    // looking for ଓଡ଼ିଆ, not for "or".
                                    (if (current) "✓ " else "") + option.nativeName,
                                    fontSize = Tokens.Body,
                                    fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                                    color = Tokens.Ink,
                                )
                                Text(option.englishName, fontSize = Tokens.Instrument, color = Tokens.Muted)
                            }
                        },
                    )
                }
            }
        }
    }
    Divider()
}

// ── C ────────────────────────────────────────────────────────────────────────

/**
 * Rule 1: at least a third of the screen, reachable one-handed with gloves.
 *
 * It inverts while transmitting rather than changing a label, because the operator holding
 * it is not reading — they are speaking, and often not looking at the screen at all. Rule 4
 * says the same control answers the hardware key with the display off, which is why the
 * visual state exists for the benefit of everyone *else* in the room.
 */
@Composable
private fun TransmitBand(
    state: OperatingState,
    onTransmitChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val transmitting = state.transmitting
    Box(
        modifier
            .padding(Tokens.ScreenMargin)
            .background(if (transmitting) Tokens.Ink else Tokens.Paper, RoundedCornerShape(12.dp))
            .border(3.dp, Tokens.Ink, RoundedCornerShape(12.dp))
            // Held, not tapped. `clickable` would fire once on release and never report the
            // press, which is the wrong shape for a control whose whole meaning is "the
            // floor is mine for as long as my thumb is down".
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onTransmitChange(true)
                        // Returns on release *and* on cancellation — a thumb that slides
                        // off the control must give the floor back, or the unit transmits
                        // silence until the application is restarted.
                        tryAwaitRelease()
                        onTransmitChange(false)
                    },
                )
            }
            .semantics(mergeDescendants = true) {
                contentDescription =
                    if (transmitting) "Transmitting. Release to send." else "Push to talk."
                // A press-and-hold gesture is unreachable through a screen reader, so the
                // same message goes out on a double tap. Rule 7 and task W7.23.
                onClick(label = "Send") {
                    onTransmitChange(true)
                    onTransmitChange(false)
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "((•))",
                fontSize = 40.sp,
                color = if (transmitting) Tokens.InkPaper else Tokens.Ink,
                modifier = Modifier.decorative(),
            )
            Spacer(Modifier.height(Tokens.Grid))
            Text(
                if (transmitting) "T R A N S M I T" else "PUSH  TO  TALK",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = if (transmitting) Tokens.InkPaper else Tokens.Ink,
                textAlign = TextAlign.Center,
            )
            if (transmitting) {
                Spacer(Modifier.height(Tokens.Grid))
                LevelMeter(state.level)
            }
        }
    }
}

/**
 * The microphone level, while the control is held.
 *
 * The one question an operator has mid-sentence is whether the unit can hear them at all,
 * and the inverted panel does not answer it -- it inverts identically whether the handset is
 * listening to a shout or to a covered microphone. A silent recogniser and a working one
 * look the same without this.
 *
 * Deliberately not a number. It is read at arm's length, in motion, by someone speaking.
 */
@Composable
private fun LevelMeter(level: Float) {
    val filled = (level.coerceIn(0f, 1f) * METER_SEGMENTS).toInt()
    Row(
        // Cleared, not merged: a screen reader announcing twelve blocks one at a time is
        // worse than useless. The spoken channel for "am I being heard" is the partial
        // hypothesis in band C', which is a live region already.
        Modifier.clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        repeat(METER_SEGMENTS) { index ->
            Box(
                Modifier
                    .width(10.dp)
                    .height(if (index < filled) 18.dp else 6.dp)
                    .background(
                        if (index < filled) Tokens.InkPaper else Tokens.InkPaper.copy(alpha = 0.3f),
                    ),
            )
        }
    }
}

private const val METER_SEGMENTS = 12

/**
 * Band C′ — the partial hypothesis, task **W1.32**.
 *
 * Rule 6: recognised text is shown so a **literate** operator can verify what the machine
 * heard, without the system requiring literacy to work. Confidence sits beside it, because
 * a hesitant recognition of the right sentence and a confident one of the wrong sentence
 * look identical without it.
 */
@Composable
private fun PartialBand(state: OperatingState) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Tokens.TouchTarget)
            .padding(horizontal = Tokens.ScreenMargin, vertical = Tokens.Grid)
            .semantics {
                // Spoken as it changes: an operator who cannot see the screen still learns
                // what was heard before it is sent.
                liveRegion = LiveRegionMode.Polite
                contentDescription =
                    state.partial?.let { "Heard: $it" } ?: "Listening."
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            state.partial?.let { "\"$it\"" } ?: "…",
            fontSize = Tokens.Body,
            color = Tokens.Ink,
            modifier = Modifier.weight(1f),
        )
        state.confidence?.let { level ->
            Text(
                "●".repeat(level.coerceIn(0, 3)) + "○".repeat((3 - level).coerceIn(0, 3)),
                fontSize = Tokens.Status,
                color = if (level >= 2) Tokens.Ok else Tokens.Warn,
                modifier = Modifier.decorative(),
            )
        }
    }
}

/**
 * What happened instead of recognition, said plainly. Task **W1.32**.
 *
 * `docs/ARCHITECTURE.md` specifies IndicConformer through sherpa-onnx, and there are no
 * acoustic models in this repository to run it -- so a press can fall back to a template the
 * operator did not choose. That substitution is invisible in band E, where a canned sentence
 * and a recognised one are both simply text, and it is exactly the kind of thing an
 * interface should never do silently.
 */
@Composable
private fun SpeechNote(note: String) {
    Text(
        note,
        fontSize = Tokens.Instrument,
        color = Tokens.Muted,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Tokens.ScreenMargin, vertical = 2.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

// ── D ────────────────────────────────────────────────────────────────────────

@Composable
private fun SecondaryBand(
    onAlert: () -> Unit,
    onPosition: () -> Unit,
) {
    Divider()
    // `IntrinsicSize.Min` rather than nothing, and this is not a style preference.
    //
    // A `Column` measures its non-weighted children first, handing each one all the space
    // the weighted children have not taken yet. The `fillMaxHeight` on the 1 dp rule below
    // took that literally: it grew to the whole remaining screen, this row grew with it,
    // and bands C, E and F were left with zero height. The transmit control, the traffic
    // list and the instrumentation strip all vanished behind a hairline divider.
    //
    // Measuring the row at its minimum intrinsic height bounds it to its content, and keeps
    // rule 9 — no fixed height on anything containing text, so it still grows at 200 %.
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        SecondaryAction("⚠", "ALERT", Tokens.Alert, Modifier.weight(1f), onAlert)
        Box(Modifier.width(1.dp).fillMaxHeight().background(Tokens.Rule))
        SecondaryAction("⌖", "POSITION", Tokens.Ink, Modifier.weight(1f), onPosition)
    }
    Divider()
}

@Composable
private fun SecondaryAction(
    icon: String,
    label: String,
    colour: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .fillMaxHeight()
            .heightIn(min = Tokens.SecondaryAction)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Icon first and larger: it is the primary carrier for an operator who cannot read
        // the word beside it. Rule 3.
        Text(icon, fontSize = Tokens.Icon, color = colour, modifier = Modifier.decorative())
        Spacer(Modifier.width(Tokens.Grid))
        Text(label, fontSize = Tokens.Body, fontWeight = FontWeight.Bold, color = colour)
    }
}

// ── E ────────────────────────────────────────────────────────────────────────

@Composable
private fun TrafficBand(
    state: OperatingState,
    modifier: Modifier = Modifier,
) {
    if (state.messages.isEmpty()) {
        Box(modifier.padding(Tokens.ScreenMargin), contentAlignment = Alignment.TopStart) {
            Text("No traffic yet.", fontSize = Tokens.Status, color = Tokens.Muted)
        }
        return
    }
    LazyColumn(modifier.padding(horizontal = Tokens.ScreenMargin)) {
        items(state.messages) { message ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Tokens.TouchTarget)
                    .spokenAs(Spoken.messageRow(message)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    message.from,
                    fontSize = Tokens.Status,
                    fontWeight = FontWeight.Bold,
                    color = if (message.isAlert) Tokens.Alert else Tokens.Ink,
                    modifier = Modifier.width(72.dp),
                )
                Text(
                    message.text,
                    fontSize = Tokens.Body,
                    color = Tokens.Ink,
                    modifier = Modifier.weight(1f),
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text(message.age, fontSize = Tokens.Instrument, color = Tokens.Muted)
                    // Only on this unit's own sends. A message that arrived was spoken by
                    // whoever sent it, and this column is about how *this* handset produced
                    // the one beside it.
                    if (message.delivery != LoggedMessage.Delivery.RECEIVED) {
                        Text(
                            if (message.fromSpeech) "spoken" else "template",
                            fontSize = Tokens.Instrument,
                            color = Tokens.Muted,
                        )
                    }
                }
            }
        }
    }
}

// ── F ────────────────────────────────────────────────────────────────────────

/**
 * Task **W1.34**. Two lines, always present, never a toggle.
 *
 * An absent figure is `—` rather than `0`. This is the strip a jury photographs, and a zero
 * where nothing was measured is a claim the project did not make.
 */
@Composable
private fun InstrumentBand(state: OperatingState) {
    Divider()
    val m = state.metrics
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Tokens.InstrumentBand)
            .background(Tokens.Paper)
            .padding(horizontal = Tokens.ScreenMargin, vertical = 4.dp)
            .semantics { contentDescription = spokenMetrics(m) },
    ) {
        Text(
            "STT ${ms(m.sttMillis)} · LINK ${ms(m.linkMillis)} · TTS ${ms(m.ttsMillis)}",
            fontSize = Tokens.Instrument,
            fontFamily = FontFamily.Monospace,
            color = Tokens.Muted,
        )
        Text(
            buildString {
                append("TOTAL ${ms(m.totalMillis)}")
                append(" · RTF ${m.realTimeFactor?.let { "%.2f".format(it) } ?: "—"}")
                append(" · CPU ${m.cpuPercent?.let { "%.1f %%".format(it) } ?: "—"}")
                // The compression figure, on screen, on every message. Demonstration step 3
                // points at this rather than at a slide.
                m.lastFrameBytes?.let { append(" · $it B ${m.compressionRatio}×") }
            },
            fontSize = Tokens.Instrument,
            fontFamily = FontFamily.Monospace,
            color = Tokens.Muted,
        )
    }
}

private fun ms(value: Long?): String = value?.let { "$it ms" } ?: "—"

/** Band F read aloud as a sentence, since a monospace strip is unreadable glyph by glyph. */
internal fun spokenMetrics(m: BandFMetrics): String {
    val parts = ArrayList<String>()
    m.totalMillis?.let { parts += "Total $it milliseconds" }
    m.sttMillis?.let { parts += "Recognition $it" }
    m.linkMillis?.let { parts += "Link $it" }
    m.ttsMillis?.let { parts += "Speech $it" }
    m.lastFrameBytes?.let {
        parts += "Last frame $it bytes, ${m.compressionRatio} times smaller than audio"
    }
    // Silence is stated. An empty announcement leaves a listener unsure whether the strip
    // was read at all or simply had nothing in it.
    if (parts.isEmpty()) return "Nothing measured yet."
    return "Latency. " + parts.joinToString(". ") + "."
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Tokens.Rule))
}

/** Rule 1: band C takes at least a third, and traffic takes what is left. */
private const val TRANSMIT_WEIGHT = 1.0f
private const val TRAFFIC_WEIGHT = 0.9f
