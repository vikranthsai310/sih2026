package org.itantra.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The alert path. Tasks **W5.17**–**W5.20**; `docs/REDESIGN.md` phase 3 — boards 12, 13, 14
 * and 22 of *iTantra Screens v2*.
 *
 * ## The one rule the redesign changed, and why it is an improvement
 *
 * The tiles used to be six saturated red rectangles. They are now **Paper on a hairline with
 * the emergency in the 32 dp icon alone**, and that is not a softening — it is the greyscale
 * test applied. Six red rectangles differ only in a glyph and a word, so under sunlight,
 * under a colour-vision deficiency, or in Field Mode they collapse into one another. A white
 * tile with a distinct silhouette separates by *shape*, which survives all three.
 *
 * `ALL CLEAR` is the exception that proves it: Mint border, Mint icon, and a Mint dot beside
 * the label. It is the only one of the six that is good news, and colouring it as an
 * emergency would be a lie told in the fastest-read part of the interface.
 *
 * ## Three rules from `docs/UX.md` that did not change
 *
 * 1. **Icon plus word, never a word alone.** The operator this product exists for may not
 *    read; the icon is the primary carrier and the word confirms it for those who do.
 * 2. **Targets are 96 dp** on anything that sends. Gloves, darkness, a moving vehicle.
 * 3. **The safe option is never smaller than the dangerous one.** See [AlertConfirmScreen].
 */

/** The six template alerts. One byte of payload; 21 bytes on the wire, authenticated. */
enum class AlertTemplate(val code: Int, val label: String) {
    MEDICAL(1, "Medical"),
    FIRE(2, "Fire"),
    FLOOD(3, "Flood"),
    EVACUATE(4, "Evacuate"),
    EXTRACT(5, "Extract"),
    ALL_CLEAR(6, "All clear"),
    ;

    /**
     * The glyph, as a vector rather than as a Unicode character.
     *
     * The six used to be `✚ ▲ ≈ ⌂ ⌖ ✓`, which render in whatever the system font has and are
     * announced by TalkBack as their character names — "heavy greek cross" for a medical
     * emergency. These are drawn, so they are identical on every handset and silent to a
     * screen reader, which reads [label] instead.
     */
    val icon: ImageVector
        get() =
            when (this) {
                MEDICAL -> Icons.Medical
                FIRE -> Icons.Fire
                FLOOD -> Icons.Flood
                EVACUATE -> Icons.Evacuate
                EXTRACT -> Icons.Extract
                ALL_CLEAR -> Icons.AllClear
            }

    /** All clear is the only good news among the six, and is never drawn as an emergency. */
    val isGoodNews: Boolean get() = this == ALL_CLEAR
}

// ── board 12 ─────────────────────────────────────────────────────────────────

/**
 * Task **W5.17**, board 12. Six templates, and a held control for anything they do not cover.
 *
 * The templates are the fastest path *and* the smallest frame *and* the cross-language path
 * — a template sent here is announced in whatever language each receiver has selected,
 * because the byte identifies the sentence rather than carrying it. That claim is printed
 * under the grid rather than left in a document.
 */
@Composable
fun AlertComposeScreen(
    onTemplate: (AlertTemplate) -> Unit,
    onHoldToSpeak: (Boolean) -> Unit,
    attachPosition: Boolean,
    onAttachPositionChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = palette
    Column(
        modifier
            .fillMaxSize()
            .background(p.ground)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        BackHeader("Send alert", onBack)

        Column(
            Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Announced at full volume on every unit, even locked and silenced.",
                fontSize = Tokens.BodySmall,
                lineHeight = Tokens.BodySmall * 1.45f,
                color = p.muted,
                modifier = Modifier.padding(horizontal = 2.dp),
            )

            AlertTemplate.entries.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { template ->
                        TemplateTile(template, Modifier.weight(1f)) { onTemplate(template) }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            Text(
                "21 B · reaches every unit in its own language",
                fontSize = Tokens.Instrument,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                lineHeight = Tokens.Instrument * 1.5f,
                color = p.muted,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }

        // The dock keeps the geometry of the operating screen's, so the thumb lands in the
        // same place on a screen the operator reached in a hurry. Task 3.2.
        DockShelf(hint = "OR HOLD TO SPEAK YOUR OWN ALERT") {
            GpsFlank(attachPosition, onAttachPositionChange)
            HoldToSpeakCircle(onHoldToSpeak)
            SquareFlank(Icons.Cross, "BACK", enabled = true, onClick = onBack)
        }
    }
}

/**
 * One template.
 *
 * Paper on a hairline; the colour is in the icon and nowhere else. Three carriers — the
 * silhouette, the hue, the word — so the grid still separates when any one is taken away.
 */
@Composable
private fun TemplateTile(
    template: AlertTemplate,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val p = palette
    val family = if (template.isGoodNews) p.mint else p.blush
    val shape = RoundedCornerShape(Tokens.RadiusCard)
    Column(
        modifier
            .heightIn(min = 116.dp)
            .background(p.paper, shape)
            .border(Tokens.Hairline, if (template.isGoodNews) family.mid else p.hairline, shape)
            .clickable(onClick = onClick)
            .padding(16.dp)
            .semantics(mergeDescendants = true) { contentDescription = template.label },
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(template.icon, contentDescription = null, tint = family.core, modifier = Modifier.size(32.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(
                template.label,
                fontSize = Tokens.Body,
                fontWeight = FontWeight.SemiBold,
                color = if (template.isGoodNews) family.deep else p.ink,
            )
            // The dot is the second carrier on the one tile whose meaning is the opposite of
            // its neighbours'. Without it, "All clear" in greyscale is just another tile.
            if (template.isGoodNews) {
                Box(Modifier.size(6.dp).background(family.core, CircleShape))
            }
        }
    }
}

/** The position checkbox, given the dock's left flank so it is never hunted for. */
@Composable
private fun GpsFlank(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val p = palette
    val shape = RoundedCornerShape(Tokens.RadiusCard)
    Column(
        Modifier
            .sizeIn(minWidth = Tokens.DockFlank, minHeight = Tokens.DockFlank)
            .background(p.ground, shape)
            .border(Tokens.Hairline, p.hairline, shape)
            .clickable { onChange(!checked) }
            .semantics(mergeDescendants = true) {
                contentDescription = if (checked) "Attach my position, on" else "Attach my position, off"
            },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .background(if (checked) p.mint.core else Color.Transparent, RoundedCornerShape(5.dp))
                .border(2.dp, if (checked) p.mint.core else p.muted, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(Icons.Tick, contentDescription = null, tint = p.onAccent, modifier = Modifier.size(14.dp))
            }
        }
        Spacer(Modifier.height(3.dp))
        Text("GPS", fontSize = Tokens.Instrument, fontWeight = FontWeight.SemiBold, color = p.muted)
    }
}

/** The free-form alert. Blush where the operating screen's is Periwinkle; same 132 dp. */
@Composable
private fun HoldToSpeakCircle(onHold: (Boolean) -> Unit) {
    val p = palette
    Box(Modifier.size(Tokens.TransmitCircle), contentAlignment = Alignment.Center) {
        val t = haloProgress()
        Box(
            Modifier
                .size(Tokens.TransmitCircle)
                .graphicsLayer {
                    val scale = 0.86f + (1.62f - 0.86f) * t
                    scaleX = scale
                    scaleY = scale
                    alpha = 0.5f * (1f - t)
                }
                .border(Tokens.SignalBorder, p.blush.core, CircleShape),
        )
        Column(
            Modifier
                .size(Tokens.TransmitCircle)
                .background(p.blush.tint, CircleShape)
                .border(3.dp, p.blush.core, CircleShape)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            onHold(true)
                            // Returns on release and on cancellation both: a thumb sliding
                            // off must give the floor back.
                            tryAwaitRelease()
                            onHold(false)
                        },
                    )
                }
                .semantics(mergeDescendants = true) {
                    contentDescription = "Hold to speak your own alert"
                },
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Transmit, contentDescription = null, tint = p.blush.core, modifier = Modifier.size(44.dp))
            Spacer(Modifier.height(5.dp))
            Text("HOLD", fontSize = Tokens.Instrument, fontWeight = FontWeight.Bold, color = p.blush.deep)
        }
    }
}

// ── board 13 ─────────────────────────────────────────────────────────────────

/**
 * Task **W5.18**, board 13. The one place the system deliberately adds latency, because an
 * alert is the only message that can cause physical harm if it is wrong.
 *
 * Two rules, both load-bearing and both visual:
 *
 * - The text is **spoken aloud on open**, so the confirmation works for an operator who
 *   cannot read it. [onSpeak] fires once when the screen appears; the row below merely
 *   repeats it, and says so rather than pretending to be the first offer.
 * - **RETAKE and SEND are exactly equal**: `weight(1f)` on both and one height, so they are
 *   equal by construction rather than by two numbers that can drift. Only the fill differs,
 *   and SEND is the filled one because it is the action — not because it is preferred.
 *
 * The recognised text sits on **Paper, never on a pastel**. The operator is verifying what
 * the machine heard; tinting that container would imply a judgement the system has not made.
 */
@Composable
fun AlertConfirmScreen(
    text: String,
    onSpeak: (String) -> Unit,
    onRetake: () -> Unit,
    onSend: () -> Unit,
    confidence: Int = 1,
    frameBytes: Int? = null,
    language: String? = null,
    modifier: Modifier = Modifier,
) {
    val p = palette
    // Spoken on open, not on a press: the operator who most needs this is the one who would
    // not know to ask for it.
    LaunchedEffect(text) { onSpeak(text) }

    Column(
        modifier
            .fillMaxSize()
            .background(p.ground)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Tokens.StatusBand)
                .background(p.paper)
                .padding(horizontal = Tokens.ScreenMargin),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.size(28.dp).background(p.blush.tint, RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Alert, contentDescription = null, tint = p.blush.core, modifier = Modifier.size(18.dp))
            }
            Text(
                "Check before sending",
                fontSize = Tokens.Subtitle,
                fontWeight = FontWeight.Bold,
                color = p.ink,
            )
        }
        Hairline()

        Column(
            Modifier.weight(1f).padding(horizontal = Tokens.ScreenMargin),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text(
                "WHAT THE MACHINE HEARD",
                fontSize = Tokens.Instrument,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = p.muted,
                modifier = Modifier.padding(start = 4.dp),
            )

            Column(
                Modifier
                    .fillMaxWidth()
                    .background(p.paper, RoundedCornerShape(22.dp))
                    .border(Tokens.Hairline, p.hairline, RoundedCornerShape(22.dp))
                    .padding(horizontal = 24.dp, vertical = 26.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Text(
                    text,
                    fontSize = Tokens.Headline,
                    lineHeight = Tokens.Headline * Tokens.INDIC_LINE_HEIGHT,
                    color = p.ink,
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(p.sunken))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.decorative()) {
                        repeat(4) { index ->
                            Box(
                                Modifier
                                    .size(9.dp)
                                    .then(
                                        if (index < confidence) {
                                            Modifier.background(p.butter.core, CircleShape)
                                        } else {
                                            Modifier.border(1.5.dp, p.butter.core, CircleShape)
                                        },
                                    ),
                            )
                        }
                    }
                    Text(
                        if (confidence >= 3) "high confidence" else "low confidence",
                        fontSize = Tokens.Status,
                        fontWeight = FontWeight.SemiBold,
                        color = p.butter.deep,
                    )
                    Spacer(Modifier.weight(1f))
                    if (frameBytes != null || language != null) {
                        Text(
                            listOfNotNull(frameBytes?.let { "$it B" }, language).joinToString(" · "),
                            fontSize = Tokens.Instrument,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            lineHeight = Tokens.Instrument * Tokens.INDIC_LINE_HEIGHT,
                            color = p.muted,
                        )
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Tokens.SecondaryAction)
                    .background(p.paper, RoundedCornerShape(Tokens.RadiusTile))
                    .border(Tokens.Hairline, p.aqua.mid, RoundedCornerShape(Tokens.RadiusTile))
                    .clickable { onSpeak(text) }
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    Modifier.size(40.dp).background(p.aqua.tint, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Play, contentDescription = null, tint = p.aqua.core, modifier = Modifier.size(18.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Hear it back", fontSize = Tokens.BodySmall, fontWeight = FontWeight.SemiBold, color = p.aqua.deep)
                    Text("Already spoken once on open", fontSize = Tokens.Instrument, color = p.muted)
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(p.paper, RoundedCornerShape(topStart = Tokens.RadiusDock, topEnd = Tokens.RadiusDock))
                .padding(Tokens.ScreenMargin),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ConfirmTarget(Icons.Cross, "RETAKE", filled = false, modifier = Modifier.weight(1f), onClick = onRetake)
            ConfirmTarget(Icons.Tick, "SEND", filled = true, modifier = Modifier.weight(1f), onClick = onSend)
        }
    }
}

/** 96 dp, equal by construction. Only the fill tells them apart. */
@Composable
private fun ConfirmTarget(
    icon: ImageVector,
    label: String,
    filled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val p = palette
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .heightIn(min = 96.dp)
            .background(if (filled) p.blush.core else p.paper, shape)
            .then(if (filled) Modifier else Modifier.border(Tokens.SignalBorder, p.hairline, shape))
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (filled) p.onAccent else p.ink,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            fontSize = Tokens.BodySmall,
            fontWeight = FontWeight.SemiBold,
            color = if (filled) p.onAccent else p.ink,
        )
    }
}

// ── board 14 ─────────────────────────────────────────────────────────────────

/**
 * Task **W5.19**, board 14. The full-screen intent, over a locked handset.
 *
 * **Identical in both palettes.** This is the one surface Field Mode does not touch: full
 * bleed `#C62828` with a `#9F1239` inner rule, in Spectrum and in Field alike. Everything
 * else in the product gives up its hue under sunlight; an alert does not.
 *
 * **There is no swipe-to-dismiss, and that is deliberate** — a swipe is something a pocket
 * can do. Dismissal requires the [onAcknowledge] target, which also drives the acknowledged
 * count on the sender's screen, so an alert nobody acknowledged is visibly different from
 * one everybody did.
 *
 * The text is rendered in the **receiver's** language when the alert arrived as a template
 * code, which is what the evidence line under the position is saying.
 */
@Composable
fun IncomingAlertScreen(
    from: String,
    text: String,
    position: String? = null,
    repeatOf: Pair<Int, Int>? = null,
    evidence: String? = null,
    onAcknowledge: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = palette
    Column(
        modifier
            .fillMaxSize()
            .background(AlertField)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(8.dp)
            .semantics { liveRegion = LiveRegionMode.Assertive },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .border(5.dp, AlertRule)
                .padding(start = 18.dp, end = 18.dp, top = 44.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
                    val t = haloProgress()
                    Box(
                        Modifier
                            .size(88.dp)
                            .graphicsLayer {
                                val scale = 0.86f + (1.62f - 0.86f) * t
                                scaleX = scale
                                scaleY = scale
                                alpha = 1f - t
                            }
                            .border(3.dp, Color.White.copy(alpha = 0.5f), CircleShape),
                    )
                    Icon(
                        Icons.Alert,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(80.dp),
                    )
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "ALERT",
                        fontSize = Tokens.Display,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                    )
                    Text(
                        "FROM ${from.uppercase()}",
                        fontSize = Tokens.Label,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.8f),
                    )
                }
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(24.dp))
                    .padding(horizontal = 24.dp, vertical = 26.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(
                    text,
                    fontSize = Tokens.Headline,
                    fontWeight = FontWeight.Bold,
                    lineHeight = Tokens.Headline * Tokens.INDIC_LINE_HEIGHT,
                    color = p.ink,
                )
                if (position != null || evidence != null) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(p.sunken))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        position?.let {
                            Text(
                                it,
                                fontSize = Tokens.Status,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium,
                                color = p.ink,
                            )
                        }
                        evidence?.let {
                            Text(
                                it,
                                fontSize = Tokens.Instrument,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium,
                                lineHeight = Tokens.Instrument * 1.5f,
                                color = p.muted,
                            )
                        }
                    }
                }
            }

            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                if (repeatOf != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Icon(
                            Icons.Play,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            "SPEAKING · REPEAT ${repeatOf.first} OF ${repeatOf.second}",
                            fontSize = Tokens.Label,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            color = Color.White.copy(alpha = 0.85f),
                        )
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp)
                        .background(Color.White, RoundedCornerShape(24.dp))
                        .clickable(onClick = onAcknowledge)
                        .semantics(mergeDescendants = true) { contentDescription = "Acknowledge" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "ACKNOWLEDGE",
                        fontSize = Tokens.Figure,
                        fontWeight = FontWeight.Bold,
                        color = AlertField,
                    )
                }
            }
        }
    }
}

/**
 * The alert field, and the rule inside it.
 *
 * Literal rather than taken from [ItantraPalette] on purpose: every other colour in this
 * file collapses under Field Mode and these two must not. `#C62828` is already
 * [Tokens.AlertField] and has been since before the redesign — this screen is the reason
 * that token exists.
 */
private val AlertField = Tokens.AlertField
private val AlertRule = Color(0xFF9F1239)

// ── board 22 ─────────────────────────────────────────────────────────────────

/** One measured step of the alert delivery path. */
data class AlertTestStep(
    val label: String,
    /** What the step did that a reader would otherwise have to take on trust. */
    val detail: String? = null,
    /** How long it took, already formatted. `null` prints `—` rather than a zero. */
    val timing: String? = null,
    val state: State = State.PASSED,
) {
    enum class State { PASSED, RUNNING, FAILED }
}

/**
 * Task **W5.20**, board 22. The alert path, run against this handset only.
 *
 * ## Why it is six steps rather than one button
 *
 * It used to be a button and a sentence. Pressing it either worked or did not, and when it
 * did not there was nothing on screen to say *which part* did not — routing, volume, focus,
 * the wake lock, the vibration, or the repeat. Vendor audio policy varies more than the
 * documentation admits, so "it did not sound" is a symptom with six causes and the operator
 * standing in a field cannot tell them apart.
 *
 * Each step is listed with what it did and how long it took. The one that fails is the one
 * that is wrong, which turns an unreproducible complaint into a line to read out.
 *
 * **Nothing is transmitted.** That is stated twice — once at the top and once in the verdict
 * — because a test that a jury mistakes for a live alert is worse than no test.
 */
@Composable
fun AlertSelfTestScreen(
    steps: List<AlertTestStep>,
    lastRun: String?,
    verdict: String?,
    device: String?,
    onRun: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = palette
    Column(
        modifier
            .fillMaxSize()
            .background(p.ground)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        BackHeader("Test alert", onBack)

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Sounds on this handset only. Nothing is transmitted and no other unit hears it.",
                fontSize = Tokens.BodySmall,
                lineHeight = Tokens.BodySmall * 1.45f,
                color = p.muted,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Text(
                lastRun?.let { "LAST RUN · $it" } ?: "NOT RUN ON THIS HANDSET",
                fontSize = Tokens.Instrument,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = p.muted,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )

            if (steps.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(p.paper, RoundedCornerShape(Tokens.RadiusTile))
                        .border(Tokens.Hairline, p.hairline, RoundedCornerShape(Tokens.RadiusTile)),
                ) {
                    steps.forEachIndexed { index, step ->
                        if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(p.sunken))
                        StepRow(step)
                    }
                }
            }

            if (verdict != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(p.paper, RoundedCornerShape(Tokens.RadiusTile))
                        .border(Tokens.Hairline, p.mint.mid, RoundedCornerShape(Tokens.RadiusTile))
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier.size(38.dp).background(p.mint.tint, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.AllClear, contentDescription = null, tint = p.mint.core, modifier = Modifier.size(22.dp))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(verdict, fontSize = Tokens.BodySmall, fontWeight = FontWeight.SemiBold, color = p.mint.deep)
                        device?.let {
                            Text(it, fontSize = Tokens.Label, lineHeight = Tokens.Label * 1.35f, color = p.muted)
                        }
                    }
                }
            }

            Text(
                "Vendor audio policy varies. Run this on every handset before a deployment.",
                fontSize = Tokens.Label,
                lineHeight = Tokens.Label * 1.45f,
                color = p.muted,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(p.paper)
                .padding(start = Tokens.ScreenMargin, end = Tokens.ScreenMargin, top = 12.dp, bottom = Tokens.ScreenMargin),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Tokens.SecondaryAction)
                    .background(p.paper, RoundedCornerShape(Tokens.RadiusCard))
                    .border(Tokens.SignalBorder, p.blush.core, RoundedCornerShape(Tokens.RadiusCard))
                    .clickable(onClick = onRun)
                    .semantics(mergeDescendants = true) { contentDescription = "Run the alert test" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp, Alignment.CenterHorizontally),
            ) {
                Icon(Icons.Alert, contentDescription = null, tint = p.blush.core, modifier = Modifier.size(24.dp))
                Text("Run the test", fontSize = Tokens.Body, fontWeight = FontWeight.SemiBold, color = p.blush.deep)
            }
        }
    }
}

@Composable
private fun StepRow(step: AlertTestStep) {
    val p = palette
    val running = step.state == AlertTestStep.State.RUNNING
    val failed = step.state == AlertTestStep.State.FAILED
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Tokens.StatusBand)
            .background(if (running) p.butter.tint else Color.Transparent)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {
                contentDescription =
                    buildString {
                        append(step.label)
                        append(
                            when (step.state) {
                                AlertTestStep.State.PASSED -> ", passed"
                                AlertTestStep.State.RUNNING -> ", running"
                                AlertTestStep.State.FAILED -> ", failed"
                            },
                        )
                        step.detail?.let { append(". $it") }
                        step.timing?.let { append(". $it") }
                    }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // A hollow ring for the step still running: it is not a failure and must not be
        // drawn as one, and it is not a pass either.
        when {
            running ->
                Box(Modifier.size(19.dp).border(2.dp, p.butter.core, CircleShape))
            failed ->
                Icon(Icons.Cross, contentDescription = null, tint = p.blush.core, modifier = Modifier.size(19.dp))
            else ->
                Icon(Icons.Tick, contentDescription = null, tint = p.mint.core, modifier = Modifier.size(19.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(step.label, fontSize = Tokens.BodySmall, lineHeight = Tokens.BodySmall * 1.3f, color = p.ink)
            step.detail?.let {
                Text(
                    it,
                    fontSize = Tokens.Instrument,
                    lineHeight = Tokens.Instrument * 1.3f,
                    color = if (running) p.butter.deep else p.muted,
                )
            }
        }
        Text(
            step.timing ?: "—",
            fontSize = Tokens.Instrument,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = if (running) p.butter.deep else p.muted,
        )
    }
}

// ── shared ───────────────────────────────────────────────────────────────────

/** The board's back header: a 44 dp glyph in a 64 dp target, then the title. */
@Composable
internal fun BackHeader(
    title: String,
    onBack: () -> Unit,
) {
    val p = palette
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Tokens.StatusBand)
            .background(p.paper)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .sizeIn(minWidth = Tokens.TouchTarget, minHeight = Tokens.TouchTarget)
                .clickable(onClick = onBack)
                .semantics(mergeDescendants = true) { contentDescription = "Back" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Back, contentDescription = null, tint = p.ink, modifier = Modifier.size(26.dp))
        }
        Text(title, fontSize = Tokens.Title, fontWeight = FontWeight.Bold, color = p.ink)
    }
    Hairline()
}

/** The raised shelf. Same geometry as the operating screen's, so the thumb lands the same. */
@Composable
internal fun DockShelf(
    hint: String,
    content: @Composable () -> Unit,
) {
    val p = palette
    Column(
        Modifier
            .fillMaxWidth()
            .background(p.paper, RoundedCornerShape(topStart = Tokens.RadiusDock, topEnd = Tokens.RadiusDock))
            .padding(start = Tokens.ScreenMargin, end = Tokens.ScreenMargin, top = 14.dp, bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            hint,
            fontSize = Tokens.Instrument,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = p.muted,
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            content()
        }
    }
}

/** A 64 dp flank. Neutral by default, because most flanks are not the emergency. */
@Composable
internal fun SquareFlank(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val p = palette
    val shape = RoundedCornerShape(Tokens.RadiusCard)
    Column(
        Modifier
            .sizeIn(minWidth = Tokens.DockFlank, minHeight = Tokens.DockFlank)
            .background(p.ground, shape)
            .border(Tokens.Hairline, p.hairline, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .then(if (enabled) Modifier else Modifier.alpha(0.4f))
            .semantics(mergeDescendants = true) { contentDescription = label },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = p.muted, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, fontSize = Tokens.Instrument, fontWeight = FontWeight.SemiBold, color = p.muted)
    }
}

@Composable
private fun Hairline() {
    val p = palette
    Box(Modifier.fillMaxWidth().height(Tokens.Hairline).background(p.hairline))
}
