package org.itantra.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

// The locate feature: who is on the channel, and the walk to one of them. Plus the one
// setting it depends on, the unit's own name.

/**
 * Every unit this handset has heard, nearest first, to pick one to walk to.
 *
 * A unit that has gone quiet is still listed, greyed, because "where was it last" is a
 * question worth answering even when "where is it now" cannot be. The name is whatever the
 * unit calls itself; a unit that has never said is its node number.
 */
@Composable
fun LocateListScreen(
    units: List<UnitInfo>,
    onSelect: (UnitInfo) -> Unit,
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
        BackHeader("Locate a unit", onBack)
        if (units.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Nobody heard yet.", fontSize = Tokens.Callout, fontWeight = FontWeight.SemiBold, color = p.ink)
                Text(
                    "A unit appears here as soon as this handset hears it on the channel. " +
                        "Ask them to open the application within range.",
                    fontSize = Tokens.BodySmall,
                    color = p.muted,
                )
            }
            return
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(units, key = { it.src }) { unit -> UnitRow(unit) { onSelect(unit) } }
        }
    }
}

@Composable
private fun UnitRow(
    unit: UnitInfo,
    onClick: () -> Unit,
) {
    val p = palette
    val present = unit.heardMillisAgo < 35_000
    val ago = ago(unit.heardMillisAgo)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Tokens.TouchTarget)
            .background(p.paper, RoundedCornerShape(Tokens.RadiusTile))
            .border(Tokens.Hairline, p.hairline, RoundedCornerShape(Tokens.RadiusTile))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {
                contentDescription =
                    "${unit.name}, node ${unit.src}, heard $ago" +
                    (unit.bars?.let { ", signal $it of 5" } ?: "") + ". Locate."
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(12.dp)
                .background(if (present) p.mint.core else p.hairlineStrong, CircleShape),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                unit.name,
                fontSize = Tokens.Callout,
                fontWeight = FontWeight.SemiBold,
                color = if (present) p.ink else p.muted,
            )
            Text(
                "node ${"%02d".format(unit.src)} · heard $ago" +
                    (if (unit.openLine) " · open line" else ""),
                fontSize = Tokens.Instrument,
                fontFamily = FontFamily.Monospace,
                color = p.muted,
            )
        }
        SignalBars(unit.bars, p)
        Text("LOCATE ›", fontSize = Tokens.Label, fontWeight = FontWeight.Bold, color = p.periwinkle.deep)
    }
}

@Composable
private fun SignalBars(
    bars: Int?,
    p: ItantraPalette,
) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 1..5) {
            Box(
                Modifier
                    .width(4.dp)
                    .height((6 + i * 3).dp)
                    .background(
                        if (bars != null && i <= bars) p.periwinkle.core else p.sunken,
                        RoundedCornerShape(1.dp),
                    ),
            )
        }
    }
}

private fun ago(millis: Long): String =
    when {
        millis < 5_000 -> "just now"
        millis < 60_000 -> "${millis / 1000} s ago"
        millis < 3_600_000 -> "${millis / 60_000} min ago"
        else -> "${millis / 3_600_000} h ago"
    }

/**
 * The walk to one unit.
 *
 * One instrument, because a phone can measure one thing about another radio: how
 * strongly it hears it. The bar fills as the signal strengthens; the figure under it is
 * the distance that signal implies, with its honest width; and the sound, heard rather
 * than seen, does the same. Which way is left to the ear -- the target's chirp -- because
 * an arrow from two GPS fixes wandered by more than the distance between the units for
 * the whole of the part of the search where it would have mattered, and was taken off.
 * When the instrument has nothing to say the screen says why, in words.
 */
@Composable
fun LocateScreen(
    state: LocateState,
    onStop: () -> Unit,
    onSound: (SoundFrom) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = palette
    Column(
        modifier
            .fillMaxSize()
            .background(p.ground)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        BackHeader(state.name, onStop)

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(18.dp))

            // The trend is on the same line as the label, in the family the siren means:
            // mint closing, blush further. The ear has it from the beep rate; the eye has
            // it here, and a colleague looking over a shoulder has it too.
            Text(
                when {
                    state.lost && !state.beaconing -> "WAITING FOR ${state.name.uppercase()}"
                    state.lost -> "SIGNAL LOST · KEEP MOVING"
                    state.trend == Trend.CLOSING -> "SIGNAL · CLOSING"
                    state.trend == Trend.FURTHER -> "SIGNAL · FURTHER"
                    else -> "SIGNAL"
                },
                fontSize = Tokens.Instrument,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color =
                    when {
                        state.lost -> p.blush.deep
                        state.trend == Trend.CLOSING -> p.mint.deep
                        state.trend == Trend.FURTHER -> p.blush.deep
                        else -> p.muted
                    },
            )

            SignalBar(state, p)

            // The distance, large. GPS while the two fixes are further apart than their
            // error, because out there it is the better figure; the signal's own estimate
            // once they are not, which is where the signal is the better one.
            val cm = state.estimatedCentimetres
            val distance =
                when {
                    state.gpsApart && state.gpsMetres != null -> "${state.gpsMetres} m"
                    cm == null -> "—"
                    cm < 300 -> "about $cm cm"
                    cm < 1_000 -> "about ${cm / 100}.${cm / 10 % 10} m"
                    else -> "about ${cm / 100} m"
                }
            Text(
                distance,
                fontSize = Tokens.Display,
                fontWeight = FontWeight.Bold,
                color = p.ink,
                modifier = Modifier.semantics { contentDescription = "Distance $distance" },
            )
            Text(
                buildString {
                    state.rssi?.let { append("signal $it dBm") } ?: append("no signal yet")
                    state.spreadCentimetres?.let { append(" · ${span(it)}") }
                    // "calibrated" is the one word that changes what the big figure means:
                    // measured against this phone, not assumed for every phone.
                    if (state.distanceCalibrated) append(" · calibrated")
                    if (state.gpsMetres != null) {
                        append(" · gps ${state.gpsMetres} m apart")
                        append(" · ±${state.ownAccuracyMetres ?: 0} here ±${state.targetAccuracyMetres ?: 0} there")
                    }
                },
                fontSize = Tokens.Instrument,
                fontFamily = FontFamily.Monospace,
                color = p.muted,
            )

            state.note?.let {
                Text(
                    it,
                    fontSize = Tokens.BodySmall,
                    color = p.muted,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }

            Spacer(Modifier.weight(1f))

            // Which handset sounds. Three, not a switch: the searcher's own siren says how
            // close; the target's chirp says which way, which no radio on a handset can.
            SoundFromRow(state, onSound)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(
                    Modifier
                        .weight(1f)
                        .heightIn(min = Tokens.TouchTarget)
                        .background(p.blush.tint, RoundedCornerShape(Tokens.RadiusControl))
                        .clickable(onClick = onStop)
                        .padding(14.dp)
                        .semantics { contentDescription = "Stop locating ${state.name}" },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("STOP", fontSize = Tokens.Label, fontWeight = FontWeight.Bold, color = p.blush.deep)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * How strongly the target is heard, as a bar that fills as the operator closes in. Tall,
 * because with the arrow gone it is the one thing on the screen an eye can read from
 * arm's length in sunlight.
 */
@Composable
private fun SignalBar(
    state: LocateState,
    p: ItantraPalette,
) {
    val fill by animateFloatAsState(targetValue = state.proximity, animationSpec = tween(300), label = "signal")
    val colour = if (state.lost) p.hairlineStrong else p.periwinkle.core
    Box(
        Modifier
            .fillMaxWidth()
            .height(22.dp)
            .background(p.sunken, RoundedCornerShape(Tokens.RadiusPill))
            .semantics { contentDescription = "Signal ${Math.round(state.proximity * 100)} percent" },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth(fill.coerceIn(0.02f, 1f))
                .height(22.dp)
                .background(colour, RoundedCornerShape(Tokens.RadiusPill)),
        )
    }
}

/** A range of centimetres in the unit that suits it: "40–110 cm", "1.2–3.5 m". */
private fun span(cm: IntRange): String =
    if (cm.last < 300) {
        "${cm.first}–${cm.last} cm"
    } else {
        "${cm.first / 100}.${cm.first / 10 % 10}–${cm.last / 100}.${cm.last / 10 % 10} m"
    }

/**
 * The sound selector: this phone, their phone, or nothing.
 *
 * Under it, one line saying what the choice is doing right now. "Their phone" is a
 * request over the radio, and the line says *chirping* only when the target has said so
 * in its own presence frame -- a control that reports its own wish as a fact would say
 * "chirping" over a target that never heard it.
 */
@Composable
private fun SoundFromRow(
    state: LocateState,
    onSound: (SoundFrom) -> Unit,
) {
    val p = palette
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "SOUND FROM",
            fontSize = Tokens.Instrument,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = p.muted,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Toggle("THIS PHONE", state.sound == SoundFrom.THIS_PHONE, Modifier.weight(1f)) {
                onSound(SoundFrom.THIS_PHONE)
            }
            Toggle("THEIR PHONE", state.sound == SoundFrom.THEIR_PHONE, Modifier.weight(1f)) {
                onSound(SoundFrom.THEIR_PHONE)
            }
            Toggle("OFF", state.sound == SoundFrom.OFF, Modifier.weight(0.6f)) {
                onSound(SoundFrom.OFF)
            }
        }
        val line =
            when (state.sound) {
                SoundFrom.THIS_PHONE -> "This handset beeps faster as the signal rises."
                SoundFrom.THEIR_PHONE ->
                    when {
                        state.theirChirping -> "${state.name} is chirping, faster as you close in. Follow the sound."
                        state.beaconing -> "Asking ${state.name} to chirp…"
                        else -> "Asking ${state.name} to chirp. No answer yet: it may be out of range."
                    }
                SoundFrom.OFF -> "No sound. The screen alone."
            }
        Text(
            line,
            fontSize = Tokens.Label,
            lineHeight = Tokens.Label * Tokens.INDIC_LINE_HEIGHT,
            color = if (state.theirChirping) p.mint.deep else p.muted,
            modifier = Modifier.semantics { contentDescription = line },
        )
    }
}

@Composable
private fun Toggle(
    label: String,
    on: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val p = palette
    Column(
        modifier
            .heightIn(min = Tokens.TouchTarget)
            .background(if (on) p.periwinkle.core else p.paper, RoundedCornerShape(Tokens.RadiusControl))
            .border(
                Tokens.Hairline,
                if (on) p.periwinkle.core else p.hairline,
                RoundedCornerShape(Tokens.RadiusControl),
            )
            .clickable(onClick = onClick)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, fontSize = Tokens.Label, fontWeight = FontWeight.Bold, color = if (on) p.onAccent else p.ink)
    }
}

/**
 * The device's name, as the other units will see it.
 *
 * Twenty-four bytes: a call sign, not a sentence. It is sent in every presence frame and
 * shown on every other handset's list, so it is the one setting that is really about the
 * other operators.
 */
@Composable
fun UnitNameScreen(
    current: String,
    defaultName: String,
    onSave: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = palette
    var draft by remember(current) { mutableStateOf(current) }
    val bytes = draft.trim().toByteArray(Charsets.UTF_8).size
    val tooLong = bytes > 24
    Column(
        modifier
            .fillMaxSize()
            .background(p.ground)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        BackHeader("Device name", onBack)
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("WHAT OTHER UNITS SEE", fontSize = Tokens.Caption, fontWeight = FontWeight.Bold, color = p.muted)
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(p.paper, RoundedCornerShape(Tokens.RadiusTile))
                    .border(
                        Tokens.SignalBorder,
                        if (tooLong) p.blush.core else p.periwinkle.core,
                        RoundedCornerShape(Tokens.RadiusTile),
                    )
                    .padding(horizontal = 16.dp, vertical = 18.dp),
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it.take(40) },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = Tokens.Subtitle, fontWeight = FontWeight.SemiBold, color = p.ink),
                    cursorBrush = SolidColor(p.periwinkle.core),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (!tooLong) onSave(draft) }),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Device name" },
                )
            }
            Text(
                if (tooLong) {
                    "Too long: $bytes of 24 bytes."
                } else {
                    "$bytes of 24 bytes. Leave empty to use $defaultName."
                },
                fontSize = Tokens.Label,
                color = if (tooLong) p.blush.deep else p.muted,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Tokens.TouchTarget)
                    .background(if (tooLong) p.sunken else p.periwinkle.core, RoundedCornerShape(Tokens.RadiusControl))
                    .clickable(enabled = !tooLong) { onSave(draft) }
                    .padding(14.dp)
                    .semantics { contentDescription = "Save the device name" },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "SAVE",
                    fontSize = Tokens.Label,
                    fontWeight = FontWeight.Bold,
                    color = if (tooLong) p.muted else p.onAccent,
                )
            }
            Text(
                "Every unit in range hears the new name at once, and it is what they see on their locate list.",
                fontSize = Tokens.Label,
                color = p.muted,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
    }
}

@Suppress("unused")
private val KeepColorImport: Color = Color.Unspecified
