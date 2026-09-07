package org.itantra.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.itantra.audio.EngineState
import java.util.Locale

/**
 * The main screen. Tasks **W1.11**, **W1.31**, **W1.32**, **W1.34**;
 * `docs/REDESIGN.md` phase 2 — boards 06, 07, 07·a, 09, 10 of *iTantra Screens v2*.
 *
 * ## What replaced the six bands
 *
 * ```
 *   chrome     ☰  BASE · node 01      [6 units] [● LINK OK]
 *              [PTT|Phone]                        [ हिन्दी ▾ ]
 *   ─────────────────────────────────────────────────────────
 *   thread     received left, mine right, newest last
 *
 *   dock       HOLD TO TALK · OR VOLUME DOWN
 *              [ALERT]      ( 132 dp )      [LAST]
 *   instrument STT · LINK · TTS  /  TOTAL · RTF · CPU · B
 * ```
 *
 * Three moves, none of them cosmetic:
 *
 * 1. **The dock sits at the bottom, on a raised 28 dp shelf.** The transmit control is
 *    held, one-handed, gloved, usually without looking. A 132 dp circle at thumb height is
 *    reachable; a slab two thirds up the screen is a stretch on any handset a relief worker
 *    actually carries. A circle also reads as a physical button where a slab reads as a
 *    banner.
 * 2. **Traffic became a thread.** A received message and a sent one used to be the same row
 *    with a different name in the left column. Side carries it now: *mine is on the right*
 *    is learned once and then never read again.
 * 3. **The receive card is gone.** An arriving message is the thread bubble it already is,
 *    wearing a signal border while this handset speaks it. The old card printed the same
 *    sentence twice in two shapes.
 *
 * ## The dock's five states, and the one that must not move
 *
 * | State | Circle | Above it | LAST |
 * | --- | --- | --- | --- |
 * | idle | Periwinkle tint, 3 dp core border | `HOLD TO TALK · OR VOLUME DOWN` | live |
 * | seized | Periwinkle core, **no motion** | `opening the microphone…` | dimmed |
 * | live | Periwinkle core, two halos, 7 bars | `SPEAK NOW` | dimmed |
 * | busy | Butter, 2 dp **dashed**, at 50 % | `CHANNEL BUSY · <who>` | live |
 * | phone | Periwinkle tint, 5 bars, `OPEN LINE` | `LINE IS OPEN · BOTH SIDES AT ONCE` | — |
 *
 * **Seized carries no motion and that is the design, not an omission.** The floor is held
 * and the microphone is not open; anything said in that gap is not in the audio at all.
 * A spinner there would invite someone to start talking into a microphone that is not
 * listening, so merging it into the live state would be a safety defect rather than a tidy-up.
 *
 * **ALERT is never dimmed** — not while a peer holds the floor, not while this handset is
 * speaking. Alert frames pre-empt the transmit queue in the protocol, so dimming the control
 * would be a visual claim the radio contradicts.
 *
 * ## What did not change
 *
 * No engine, no protocol, no state model. [OperatingState], [BandFMetrics] and every
 * callback keep the shapes they had, and the instrument strip keeps every figure it had.
 * `docs/REDESIGN.md` states the rule — the design changes, the logic does not — and this
 * file is the largest test of it.
 */
@Composable
fun OperatingScreen(
    state: OperatingState,
    onTransmitChange: (Boolean) -> Unit,
    onAlert: () -> Unit,
    onLanguageSelected: (String) -> Unit,
    onMenu: () -> Unit,
    onReplay: (String) -> Unit = {},
    /** Push-to-talk or the open line, from the segments in band A. */
    onModeChange: (String) -> Unit = {},
    /** The LOCATE flank: who is on the channel, and the walk to one of them. */
    onLocate: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val p = palette
    Column(
        modifier
            .fillMaxSize()
            .background(p.ground)
            // `targetSdk 35` draws edge to edge on Android 15 with no opt-out. The boards
            // draw a phone status bar at the top; the real one goes there, so this screen
            // must not paint its own — it insets under it instead.
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        ChromeHeader(state, onMenu, onLanguageSelected, onModeChange)

        // A banner pushes the thread down rather than covering the dock the operator is
        // already reaching for.
        state.degraded?.let { DegradedBanner(it) }

        ThreadPane(state, onReplay, Modifier.fillMaxWidth().weight(1f))

        if (state.transmitting || state.partial != null) PartialStrip(state)
        state.speechNote?.let { SpeechNote(it) }

        Dock(state, onTransmitChange, onAlert, onReplay, onLocate)
        InstrumentStrip(state)
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
    /** This unit's language in its own script, for the header chip. */
    val language: String,
    /** Its code, so the menu can mark which row is current without matching on script. */
    val languageCode: String = "",
    /** Every language this profile carries, for the header chip's menu. */
    val languages: List<LanguageOption> = emptyList(),
    val transmitting: Boolean = false,
    /**
     * Whether the microphone is open *yet*.
     *
     * Distinct from [transmitting], and the distinction is where messages were being lost:
     * a press holds the floor immediately, and the recognition service takes a moment to
     * open the microphone. Anything said in that gap is not in the audio at all.
     */
    val listening: Boolean = false,
    /** The running hypothesis while the operator is still speaking. */
    val partial: String? = null,
    val confidence: Int? = null,
    /** Microphone level while the control is held, 0..1. */
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
    /**
     * Who this handset is speaking aloud right now, or null when it is not speaking.
     *
     * The engine has always known this -- [org.itantra.app.platform.Speaker] takes an
     * `onFirstAudio` and an `onFinished` and `MessageEngine` was already passing the first
     * of them to time the instrument strip's TTS stage. What it never did was *say* so,
     * which left the screen unable to draw the one moment the whole product exists for: a
     * sentence spoken by a person eleven kilometres away coming out of this handset.
     *
     * It is the sender's identifier, not a name -- `node 02` -- because the frame format
     * carries no names and inventing one would be inventing a feature. See
     * `docs/REDESIGN.md` gap G2.
     */
    val speakingFrom: String? = null,
    /** Every unit heard lately, nearest first: the count in band A and the locate list. */
    val units: List<UnitInfo> = emptyList(),
    /** On the open line, whether the operator has paused the microphone with HOLD. */
    val openLinePaused: Boolean = false,
)

/**
 * The instrument strip's numbers, from the utterance that just happened.
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
    /**
     * Processor time the utterance used, counted in cores rather than as a percentage.
     *
     * `1.07` means one core was busy for the whole interval and a second one for a
     * fourteenth of it. The measurement is unchanged from when this was a percentage —
     * it is the same quantity divided by a hundred — but "107 %" invites the reading
     * "107 % of the handset", which is impossible, and a strip a jury photographs cannot
     * afford a number that looks broken. See [cpuCoreCount].
     */
    val cpuCores: Double? = null,
    /** Cores the handset had online when [cpuCores] was taken, so the figure has a scale. */
    val cpuCoreCount: Int? = null,
    val lastFrameBytes: Int? = null,
    /** Audio actually captured for the last utterance, when one was recognised. */
    val audioMillis: Long? = null,
) {
    /**
     * How much smaller the frame is than the audio it replaced.
     *
     * Measured against **this utterance's own audio** whenever there was any, rather than
     * against a fixed reference. The reference used to be three seconds regardless, so a
     * six-second sentence was compared with a three-second clip nobody recorded — a true
     * enough sentence about the protocol printed where a reader takes it for a measurement
     * of what just happened.
     *
     * The three-second convention survives for a template send, where no audio was captured
     * and the figure is the protocol claim rather than an observation.
     */
    val compressionRatio: Int?
        get() {
            val bytes = lastFrameBytes?.takeIf { it > 0 } ?: return null
            val audio = audioMillis?.takeIf { it > 0 }?.let { it * BYTES_PER_SECOND / 1000.0 }
            return Math.round((audio ?: RAW_AUDIO_BYTES) / bytes).toInt()
        }

    private companion object {
        /** Three seconds of 16 kHz 16-bit mono, the convention when nothing was recorded. */
        const val RAW_AUDIO_BYTES = 96_000.0

        /** 16 kHz, 16-bit, mono — what AudioCapture delivers. */
        const val BYTES_PER_SECOND = 32_000
    }
}

// ── chrome ───────────────────────────────────────────────────────────────────

/**
 * Board 06's header. Task **2.1** — the old bands A and B, merged into one block of chrome.
 *
 * They were always one thing pretending to be two: both are identity, neither is touched
 * during an utterance, and the hairline between them separated nothing. Merged, they read as
 * the frame around the conversation rather than as its first two rows.
 */
@Composable
private fun ChromeHeader(
    state: OperatingState,
    onMenu: () -> Unit,
    onLanguageSelected: (String) -> Unit,
    onModeChange: (String) -> Unit,
) {
    val p = palette
    Column(
        Modifier
            .fillMaxWidth()
            .background(p.paper)
            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Tokens.StatusBand),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .sizeIn(minWidth = Tokens.TouchTarget, minHeight = Tokens.TouchTarget)
                    .clickable(onClick = onMenu)
                    .semantics(mergeDescendants = true) { contentDescription = "Menu" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Grid, contentDescription = null, tint = p.ink, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    state.unitName,
                    fontSize = Tokens.Body,
                    fontWeight = FontWeight.Bold,
                    color = p.ink,
                )
                Text(
                    "node ${"%02d".format(state.nodeId)}",
                    fontSize = Tokens.Instrument,
                    fontFamily = FontFamily.Monospace,
                    color = p.muted,
                )
            }
            Pill(
                text = "${state.peerCount} units",
                fill = p.orchid.tint,
                border = null,
                ink = p.orchid.deep,
            )
            LinkPill(state)
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeSegments(state.mode, onModeChange)
            Spacer(Modifier.weight(1f))
            LanguageChip(state, onLanguageSelected)
        }
    }
    Hairline()
}

/** A rounded chip of text. The board's `border-radius:99px` shape, once. */
@Composable
private fun Pill(
    text: String,
    fill: Color,
    border: Color?,
    ink: Color,
    leading: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(Tokens.RadiusPill)
    Row(
        modifier
            .background(fill, shape)
            .then(if (border != null) Modifier.border(Tokens.Hairline, border, shape) else Modifier)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        leading?.invoke()
        Text(text, fontSize = Tokens.Label, fontWeight = FontWeight.SemiBold, color = ink)
    }
}

/**
 * The link, as a dot **and** a word.
 *
 * Rule 5: colour is never the only carrier. Take the hue away and "LINK OK" is still there;
 * take the word away and the dot is still Mint or Blush. The dot breathes while the link is
 * up — a still dot and a stale screen look identical otherwise.
 */
@Composable
private fun LinkPill(state: OperatingState) {
    val p = palette
    val family = if (state.linkUp) p.mint else p.blush
    Pill(
        text = if (state.linkUp) "LINK OK" else "NO LINK",
        fill = family.tint,
        border = family.mid,
        ink = family.deep,
        leading = {
            Box(
                Modifier
                    .size(8.dp)
                    .then(if (state.linkUp) Modifier.alpha(pulseAlpha()) else Modifier)
                    .background(family.core, CircleShape),
            )
        },
        modifier =
            Modifier.semantics(mergeDescendants = true) {
                contentDescription =
                    if (state.linkUp) {
                        "Link up over ${state.transportName}, ${state.peerCount} units"
                    } else {
                        "No link. ${state.queued} messages waiting."
                    }
            },
    )
}

/**
 * Which mode the radio is in, as a two-segment switch.
 *
 * A switch now, not an indicator: the open line exists (`MessageEngine.setMode`), so the
 * segment that used to be drawn for a mode that did not work changes the mode. Board 19's
 * cards change the same setting; this is the one-tap version for the operating screen.
 */
@Composable
private fun ModeSegments(
    mode: String,
    onModeChange: (String) -> Unit,
) {
    val p = palette
    val phone = mode.equals("Phone", ignoreCase = true)
    Row(
        Modifier
            .background(p.sunken, RoundedCornerShape(Tokens.RadiusPill))
            .padding(3.dp)
            .semantics { contentDescription = if (phone) "Open line mode" else "Push to talk mode" },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf("PTT" to !phone, "Phone" to phone).forEach { (label, selected) ->
            Text(
                label,
                fontSize = Tokens.Label,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) p.periwinkle.deep else p.muted,
                modifier =
                    Modifier
                        .then(
                            if (selected) {
                                Modifier.background(p.onAccent, RoundedCornerShape(Tokens.RadiusPill))
                            } else {
                                Modifier
                            },
                        )
                        .clickable(enabled = !selected) { onModeChange(label) }
                        .padding(horizontal = 13.dp, vertical = 8.dp)
                        .semantics {
                            contentDescription =
                                if (label == "PTT") "Switch to push to talk" else "Switch to the open line"
                        },
            )
        }
    }
}

/**
 * The language, in its own script, never a code.
 *
 * A speaker of Odia is looking for ଓଡ଼ିଆ; "or" means nothing to anyone. It is a menu and not
 * a cycle — a cycle made Odia eight taps from Hindi through eight scripts nobody wanted, and
 * announced nothing before arriving.
 */
@Composable
private fun LanguageChip(
    state: OperatingState,
    onLanguageSelected: (String) -> Unit,
) {
    val p = palette
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Tokens.RadiusPill)
    Box {
        Box(
            Modifier
                .heightIn(min = Tokens.TouchTarget)
                .clickable { open = true }
                .semantics(mergeDescendants = true) {
                    contentDescription = "Language ${state.language}. Change."
                },
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier
                    .heightIn(min = 38.dp)
                    .background(p.orchid.tint, shape)
                    .border(Tokens.Hairline, p.orchid.mid, shape)
                    .padding(start = 12.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    state.language,
                    fontSize = Tokens.Callout,
                    fontWeight = FontWeight.SemiBold,
                    color = p.orchid.deep,
                    // The 1.4 line box Indic conjuncts need. Declared in Tokens since week
                    // one and never applied until this rebuild, which is why Odia and
                    // Malayalam matrās were clipping against the default box.
                    lineHeight = Tokens.Callout * Tokens.INDIC_LINE_HEIGHT,
                )
                Icon(Icons.Caret, contentDescription = null, tint = p.orchid.core, modifier = Modifier.size(18.dp))
            }
        }
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
                                (if (current) "✓ " else "") + option.nativeName,
                                fontSize = Tokens.Body,
                                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                                color = p.ink,
                                lineHeight = Tokens.Body * Tokens.INDIC_LINE_HEIGHT,
                            )
                            Text(option.englishName, fontSize = Tokens.Label, color = p.muted)
                        }
                    },
                )
            }
        }
    }
}

// ── the thread ───────────────────────────────────────────────────────────────

/**
 * Task **2.2**. The conversation, replacing the traffic table.
 *
 * Received on the left with an initial disc, this unit's own on the right with none. That is
 * the whole navigation: *mine is on the right* is learned once, after which the sender line
 * is read only when it matters who.
 */
@Composable
private fun ThreadPane(
    state: OperatingState,
    onReplay: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = palette
    if (state.messages.isEmpty()) {
        EmptyState(
            icon = Icons.Transmit,
            title = "No traffic yet",
            body = "Hold the circle to speak.",
            modifier = modifier,
        )
        return
    }
    val list = rememberLazyListState()
    val reduced = reducedMotion
    // Newest at the bottom, the way a conversation reads, and the thread follows it there:
    // a message arriving under the fold of a list that does not move is a message the
    // operator finds later. Keyed on the count, so a change to an existing bubble -- a
    // delivery mark turning over -- does not yank the thread out from under a reader.
    LaunchedEffect(state.messages.size) {
        val last = state.messages.lastIndex
        if (last < 0) return@LaunchedEffect
        if (reduced) list.scrollToItem(last) else list.animateScrollToItem(last)
    }
    LazyColumn(
        modifier,
        state = list,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(state.messages) { message ->
            MessageBubble(
                message = message,
                speaking = state.speakingFrom != null && state.speakingFrom == message.from,
                onReplay = onReplay,
            )
        }
    }
}

// ── the live strip ───────────────────────────────────────────────────────────

/**
 * The partial hypothesis, task **W1.32**.
 *
 * Rule 6: recognised text is shown so a **literate** operator can verify what the machine
 * heard, without the system requiring literacy to work. Confidence sits beside it, because
 * a hesitant recognition of the right sentence and a confident one of the wrong sentence
 * look identical without it.
 */
@Composable
private fun PartialStrip(state: OperatingState) {
    val p = palette
    val shape = RoundedCornerShape(Tokens.RadiusControl)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = Tokens.Grid)
            .background(p.sky.tint, shape)
            .border(Tokens.Hairline, p.sky.mid, shape)
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = state.partial?.let { "Heard: $it" } ?: "Listening."
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            state.partial?.let { "“$it”" } ?: "…",
            fontSize = Tokens.Body,
            color = p.ink,
            lineHeight = Tokens.Body * Tokens.INDIC_LINE_HEIGHT,
            modifier = Modifier.weight(1f),
        )
        state.confidence?.let { level -> ConfidenceDots(level, p.sky.core) }
    }
}

/** Four 6 dp discs. Read without reading — which is the whole point of not printing a number. */
@Composable
private fun ConfidenceDots(
    level: Int,
    colour: Color,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.decorative()) {
        repeat(4) { index ->
            Box(
                Modifier
                    .size(6.dp)
                    .then(
                        if (index < level) {
                            Modifier.background(colour, CircleShape)
                        } else {
                            Modifier.border(1.dp, colour, CircleShape)
                        },
                    ),
            )
        }
    }
}

/**
 * What happened instead of recognition, said plainly. Task **W1.32**.
 *
 * A press can fall back to a template the operator did not choose. That substitution is
 * invisible in the thread, where a canned sentence and a recognised one are both simply
 * text, and it is exactly the kind of thing an interface should never do quietly.
 */
@Composable
private fun SpeechNote(note: String) {
    val p = palette
    Text(
        note,
        fontSize = Tokens.Label,
        color = p.apricot.deep,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

// ── the dock ─────────────────────────────────────────────────────────────────

/**
 * Task **2.3**. The raised shelf: a hint line, then ALERT, the circle, and LAST.
 *
 * The shelf's 28 dp top corners and its upward shadow are what separate the controls from
 * the conversation without a rule across the screen — the thread scrolls *under* it.
 */
@Composable
private fun Dock(
    state: OperatingState,
    onTransmitChange: (Boolean) -> Unit,
    onAlert: () -> Unit,
    onReplay: (String) -> Unit,
    onLocate: () -> Unit,
) {
    val p = palette
    val dock = dockStateOf(state)
    // Dimmed while this unit holds the floor: replaying a message into a live microphone is
    // never what was meant, and the control says so by being unavailable rather than by
    // quietly doing nothing.
    val busySpeaking = dock == DockState.SEIZED || dock == DockState.LIVE
    val lastReceived = state.messages.lastOrNull { it.delivery == LoggedMessage.Delivery.RECEIVED }

    Column(
        Modifier
            .fillMaxWidth()
            .background(p.paper, RoundedCornerShape(topStart = Tokens.RadiusDock, topEnd = Tokens.RadiusDock))
            .padding(start = Tokens.ScreenMargin, end = Tokens.ScreenMargin, top = 14.dp, bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DockHint(dock, state)
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Board 10 swaps the left flank for HOLD. Everywhere else it is ALERT, and
            // ALERT is never dimmed — alert frames pre-empt the transmit queue.
            if (dock == DockState.PHONE) {
                // HOLD pauses the microphone; the next press resumes it. The engine reads a
                // press on the open line as that toggle.
                FlankButton(
                    if (state.openLinePaused) Icons.Play else Icons.Pause,
                    if (state.openLinePaused) "RESUME" else "HOLD",
                    p.butter,
                    enabled = true,
                    onClick = { onTransmitChange(true) },
                )
            } else {
                FlankButton(Icons.Alert, "ALERT", p.blush, enabled = true, strong = true, onClick = onAlert)
            }

            TransmitCircle(state, dock, onTransmitChange)

            // LOCATE: who is on the channel, and the walk to one of them. It replaced LAST,
            // which replayed the last message; that lives on in the message log.
            FlankButton(
                icon = Icons.Globe,
                label = "LOCATE",
                family = p.aqua,
                enabled = true,
                onClick = onLocate,
            )
        }
    }
}

/** The line above the circle. It is the label the circle does not carry. */
@Composable
private fun DockHint(
    dock: DockState,
    state: OperatingState,
) {
    val p = palette
    when (dock) {
        DockState.IDLE ->
            Text(
                "HOLD TO TALK · OR VOLUME DOWN",
                fontSize = Tokens.Instrument,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = p.muted,
            )
        DockState.SEIZED ->
            Text("opening the microphone…", fontSize = Tokens.Body, color = p.periwinkle.deep)
        DockState.LIVE ->
            Text(
                "SPEAK NOW",
                fontSize = Tokens.BodySmall,
                fontWeight = FontWeight.Bold,
                color = p.periwinkle.deep,
            )
        DockState.BUSY ->
            Pill(
                text = "CHANNEL BUSY · ${state.speakingFrom.orEmpty().uppercase(Locale.ROOT)}",
                fill = p.butter.tint,
                border = p.butter.mid,
                ink = p.butter.deep,
            )
        DockState.PHONE ->
            Text(
                if (state.openLinePaused) "LINE ON HOLD · TAP TO RESUME" else "LINE IS OPEN · SPEAK ANY TIME",
                fontSize = Tokens.Instrument,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = p.muted,
            )
    }
}

/** One 64 dp square beside the circle. Icon over word — rule 3, both carriers, always. */
@Composable
private fun FlankButton(
    icon: ImageVector,
    label: String,
    family: ItantraPalette.Family,
    enabled: Boolean,
    strong: Boolean = false,
    onClick: () -> Unit,
) {
    val p = palette
    val shape = RoundedCornerShape(Tokens.RadiusCard)
    Column(
        Modifier
            .sizeIn(minWidth = Tokens.DockFlank, minHeight = Tokens.DockFlank)
            .background(if (enabled) family.tint else p.ground, shape)
            .border(
                if (strong) Tokens.SignalBorder else Tokens.Hairline,
                if (enabled) family.mid else p.hairline,
                shape,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .then(if (enabled) Modifier else Modifier.alpha(0.4f))
            .semantics(mergeDescendants = true) { contentDescription = label },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) family.core else p.muted,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            fontSize = Tokens.Instrument,
            fontWeight = FontWeight.Bold,
            color = if (enabled) family.deep else p.muted,
        )
    }
}

/**
 * Tasks **2.4** and **2.6**. One circle, five states, one position.
 *
 * The states come from [dockStateOf] — the engine — and never from a local `pressed` flag,
 * because `PushToTalkKey` and `VolumeKeyCapture` can seize the floor with the screen off and
 * this composable would never hear about it. Motion starts at [DockState.LIVE] and nowhere
 * else; see the class KDoc for why [DockState.SEIZED] is deliberately still.
 */
@Composable
private fun TransmitCircle(
    state: OperatingState,
    dock: DockState,
    onTransmitChange: (Boolean) -> Unit,
) {
    val p = palette
    val filled = dock == DockState.SEIZED || dock == DockState.LIVE

    Box(Modifier.size(Tokens.TransmitCircle), contentAlignment = Alignment.Center) {
        // Two rings, half a period apart, so it reads as emission rather than as a pulse.
        if (dock == DockState.LIVE) {
            listOf(0, Tokens.HALO_MILLIS / 2).forEach { offset ->
                val t = haloProgress(offset)
                Box(
                    Modifier
                        .size(Tokens.TransmitCircle)
                        .graphicsLayer {
                            val scale = HALO_FROM + (HALO_TO - HALO_FROM) * t
                            scaleX = scale
                            scaleY = scale
                            alpha = HALO_ALPHA * (1f - t)
                        }
                        .border(Tokens.SignalBorder, p.periwinkle.core, CircleShape),
                )
            }
        }

        Box(
            Modifier
                .size(Tokens.TransmitCircle)
                .clip(CircleShape)
                .then(
                    when (dock) {
                        DockState.BUSY ->
                            Modifier
                                .alpha(0.5f)
                                .background(p.butter.tint)
                                .dashedEdge(p.butter.mid, Tokens.TransmitCircle / 2)
                        DockState.PHONE ->
                            Modifier
                                .background(p.periwinkle.tint)
                                .border(Tokens.SignalBorder, p.periwinkle.mid, CircleShape)
                        else ->
                            if (filled) {
                                Modifier.background(p.periwinkle.core)
                            } else {
                                Modifier
                                    .background(p.periwinkle.tint)
                                    .border(3.dp, p.periwinkle.core, CircleShape)
                            }
                    },
                )
                .then(
                    // On the open line the circle is HOLD: a tap pauses the microphone and
                    // the next tap resumes it. Nothing is sent by pressing; the pause sends.
                    if (dock == DockState.PHONE) {
                        Modifier.clickable { onTransmitChange(true) }
                    } else {
                        Modifier.pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    onTransmitChange(true)
                                    // Returns on release *and* on cancellation — a thumb
                                    // that slides off must give the floor back, or the unit
                                    // transmits silence until the app is restarted.
                                    tryAwaitRelease()
                                    onTransmitChange(false)
                                },
                            )
                        }
                    },
                )
                .semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription =
                        when (dock) {
                            DockState.IDLE -> "Push to talk."
                            DockState.SEIZED -> "Opening the microphone. Wait."
                            DockState.LIVE -> "Listening. Speak now, release to send."
                            DockState.BUSY -> "Channel busy. ${state.speakingFrom} is speaking."
                            DockState.PHONE ->
                                if (state.openLinePaused) {
                                    "Open line on hold. Tap to resume."
                                } else {
                                    "Open line. Tap to hold."
                                }
                        }
                    // A press-and-hold gesture is unreachable through a screen reader, so
                    // the same message goes out on a double tap. Rule 7, task W7.23.
                    if (dock != DockState.PHONE) {
                        onClick(label = "Send") {
                            onTransmitChange(true)
                            onTransmitChange(false)
                            true
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (dock == DockState.PHONE) 8.dp else 9.dp),
            ) {
                Icon(
                    if (dock == DockState.PHONE) Icons.OpenLine else Icons.Transmit,
                    contentDescription = null,
                    tint =
                        when {
                            filled -> p.onAccent
                            dock == DockState.BUSY -> p.butter.core
                            else -> p.periwinkle.core
                        },
                    // Larger when the circle is otherwise empty; smaller when bars share it.
                    modifier = Modifier.size(if (dock == DockState.LIVE || dock == DockState.PHONE) 46.dp else 60.dp),
                )
                if (dock == DockState.LIVE) {
                    Equaliser(bars = Tokens.EQ_BARS, height = 20.dp, colour = p.onAccent.copy(alpha = 0.92f))
                }
                if (dock == DockState.PHONE) {
                    if (!state.openLinePaused) Equaliser(bars = 5, height = 14.dp, colour = p.periwinkle.core)
                    Text(
                        if (state.openLinePaused) "ON HOLD" else "OPEN LINE",
                        fontSize = Tokens.Instrument,
                        fontWeight = FontWeight.SemiBold,
                        color = p.periwinkle.deep,
                    )
                }
            }
        }
    }
}

/**
 * The microphone level, while the floor is live.
 *
 * The one question an operator has mid-sentence is whether the unit can hear them at all,
 * and a filled circle does not answer it — it fills identically for a shout and for a
 * covered microphone. Deliberately not a number: it is read at arm's length, in motion, by
 * someone who is speaking.
 */
@Composable
private fun Equaliser(
    bars: Int,
    height: androidx.compose.ui.unit.Dp,
    colour: Color,
) {
    val heights = equaliserBars()
    Row(
        // Cleared, not merged: a screen reader announcing seven bars one at a time is worse
        // than useless. The spoken channel for "am I being heard" is the partial hypothesis,
        // which is a live region already.
        Modifier.height(height).clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        repeat(bars) { index ->
            val fraction = heights.getOrElse(index) { 1f }.coerceIn(0.25f, 1f)
            Box(
                Modifier
                    .width(4.dp)
                    .height(height * fraction)
                    .background(colour, RoundedCornerShape(2.dp)),
            )
        }
    }
}

// ── instrumentation ──────────────────────────────────────────────────────────

/**
 * Task **W1.34** and **2.7**. Always present, never a toggle.
 *
 * Two lines at rest and one while the floor is held, because during an utterance the only
 * two facts worth the width are that the handset is listening and what it is costing.
 * An absent figure is `—` rather than `0`: a zero where nothing was measured is a claim the
 * project did not make, and this is the strip a jury photographs.
 */
@Composable
private fun InstrumentStrip(state: OperatingState) {
    val p = palette
    val m = state.metrics
    val live = state.transmitting
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Tokens.InstrumentBand)
            .background(p.sky.tint)
            .padding(start = Tokens.ScreenMargin, end = Tokens.ScreenMargin, top = 6.dp, bottom = 8.dp)
            .semantics { contentDescription = spokenMetrics(m) },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (live) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Instrument(if (state.listening) "LISTENING …" else "FLOOR HELD · MIC OPENING")
                Instrument("CPU  ${cpuLabel(m.cpuCores, m.cpuCoreCount)}")
            }
            return@Column
        }
        Instrument("STT ${ms(m.sttMillis)} · LINK ${ms(m.linkMillis)} · TTS ${ms(m.ttsMillis)}")
        Instrument(
            buildString {
                append("TOTAL ${ms(m.totalMillis)}")
                append(" · RTF ${m.realTimeFactor?.let { "%.2f".format(Locale.ROOT, it) } ?: "—"}")
                append(" · CPU ${cpuLabel(m.cpuCores, m.cpuCoreCount)}")
                // The compression figure, on screen, on every message. Demonstration step 3
                // points at this rather than at a slide.
                m.lastFrameBytes?.let { append(" · $it B ${m.compressionRatio}×") }
            },
        )
    }
}

@Composable
private fun Instrument(text: String) {
    val p = palette
    Text(
        text,
        fontSize = Tokens.Instrument,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        color = p.sky.deep,
    )
}

private fun ms(value: Long?): String = value?.let { "$it ms" } ?: "—"

/**
 * Processor use for the strip: `1.07/8 cores`, or `1.07 cores` when the count is unknown.
 *
 * The denominator is the point. On an eight-core handset 1.07 cores is about an eighth of
 * the device, and printing the two together answers "how much of this handset was that"
 * without silently scaling the measurement by a number the reader cannot see.
 */
internal fun cpuLabel(
    cores: Double?,
    of: Int?,
): String {
    if (cores == null) return "—"
    val used = "%.2f".format(Locale.ROOT, cores)
    return if (of != null && of > 0) "$used/$of cores" else "$used cores"
}

/** The strip read aloud as a sentence, since a monospace line is unreadable glyph by glyph. */
internal fun spokenMetrics(m: BandFMetrics): String {
    val parts = ArrayList<String>()
    m.totalMillis?.let { parts += "Total $it milliseconds" }
    m.sttMillis?.let { parts += "Recognition $it" }
    m.linkMillis?.let { parts += "Link $it" }
    m.ttsMillis?.let { parts += "Speech $it" }
    m.lastFrameBytes?.let {
        parts += "Last frame $it bytes, ${m.compressionRatio} times smaller than audio"
    }
    // The strip shows these two; a listener who cannot see it was being told less than a
    // sighted operator standing next to them.
    m.realTimeFactor?.let { parts += "Real time factor ${"%.2f".format(Locale.ROOT, it)}" }
    m.cpuCores?.let {
        val used = "%.2f".format(Locale.ROOT, it)
        parts += m.cpuCoreCount
            ?.let { n -> "Processor $used of $n cores" }
            ?: "Processor $used cores"
    }
    // Silence is stated. An empty announcement leaves a listener unsure whether the strip
    // was read at all or simply had nothing in it.
    if (parts.isEmpty()) return "Nothing measured yet."
    return "Latency. " + parts.joinToString(". ") + "."
}

// ── small parts ──────────────────────────────────────────────────────────────

@Composable
private fun Hairline() {
    val p = palette
    Box(Modifier.fillMaxWidth().height(Tokens.Hairline).background(p.hairline))
}

/** The halo's scale and opacity envelope, matching the canvas's `@keyframes halo`. */
private const val HALO_FROM = 0.86f
private const val HALO_TO = 1.62f
private const val HALO_ALPHA = 0.5f
