package org.itantra.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

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
 * Two instruments, because a phone can only measure two things about another radio: how
 * strongly it hears it, and -- with both positions -- which way it lies. The arrow turns
 * with the handset in real time; the ring behind it fills as the signal strengthens; the
 * siren, heard rather than seen, does the same. When either instrument has nothing to say
 * the screen says why, in words, rather than pointing somewhere it does not know.
 */
@Composable
fun LocateScreen(
    state: LocateState,
    onStop: () -> Unit,
    onSiren: (Boolean) -> Unit,
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
            Text(
                when {
                    state.lost && !state.beaconing -> "WAITING FOR ${state.name.uppercase()}"
                    state.lost -> "SIGNAL LOST · KEEP MOVING"
                    else -> "SIGNAL"
                },
                fontSize = Tokens.Instrument,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = if (state.lost) p.blush.deep else p.muted,
            )

            Compass(state, p)

            val distance =
                when {
                    state.gpsMetres != null && state.gpsMetres >= 8 -> "${state.gpsMetres} m"
                    state.estimatedMetres != null -> "about ${state.estimatedMetres} m"
                    else -> "—"
                }
            Text(distance, fontSize = Tokens.Display, fontWeight = FontWeight.Bold, color = p.ink)
            Text(
                buildString {
                    state.rssi?.let { append("signal $it dBm") } ?: append("no signal yet")
                    state.gpsMetres?.let { append(" · gps ±${state.targetAccuracyMetres ?: 0} m") }
                },
                fontSize = Tokens.Instrument,
                fontFamily = FontFamily.Monospace,
                color = p.muted,
            )

            state.arrowNote?.let {
                Text(
                    it,
                    fontSize = Tokens.BodySmall,
                    color = p.muted,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }

            Spacer(Modifier.weight(1f))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Toggle(
                    label = if (state.sirenOn) "SIREN ON" else "SIREN OFF",
                    on = state.sirenOn,
                    modifier = Modifier.weight(1f),
                ) { onSiren(!state.sirenOn) }
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

/** The ring and the arrow. The ring fills with proximity; the arrow turns to the target. */
@Composable
private fun Compass(
    state: LocateState,
    p: ItantraPalette,
) {
    // The engine smooths the bearing along the shortest arc on every compass reading, some
    // fifty times a second, so it is drawn as it comes. An animation here would chase a
    // value that has already moved on, and would spin the long way round at north.
    val bearing = state.relativeBearingDeg
    val eased = bearing ?: 0f
    val fill by animateFloatAsState(targetValue = state.proximity, animationSpec = tween(300), label = "ring")
    val ring = if (state.lost) p.hairlineStrong else p.periwinkle.core
    val arrowColour = if (bearing == null) p.hairline else p.periwinkle.deep
    Box(
        Modifier
            .size(240.dp)
            .semantics {
                contentDescription =
                    bearing?.let { "Arrow pointing ${clockFace(it)}" } ?: "No direction yet"
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 14.dp.toPx()
            val radius = size.minDimension / 2 - stroke
            drawCircle(color = p.sunken, radius = radius, style = Stroke(stroke))
            drawArc(
                color = ring,
                startAngle = -90f,
                sweepAngle = 360f * fill,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            // Tick marks at the quarters, so "straight ahead" is a mark, not a guess.
            for (q in 0 until 4) {
                val a = Math.toRadians(q * 90.0 - 90.0)
                val inner = radius - stroke
                val outer = radius - stroke / 2
                drawLine(
                    color = p.hairlineStrong,
                    start = Offset(center.x + inner * cos(a).toFloat(), center.y + inner * sin(a).toFloat()),
                    end = Offset(center.x + outer * cos(a).toFloat(), center.y + outer * sin(a).toFloat()),
                    strokeWidth = 2.dp.toPx(),
                )
            }
            rotate(if (bearing == null) 0f else eased, pivot = center) {
                val length = radius * 0.62f
                val head = radius * 0.22f
                val path =
                    Path().apply {
                        moveTo(center.x, center.y - length)
                        lineTo(center.x - head * 0.6f, center.y - length + head)
                        lineTo(center.x + head * 0.6f, center.y - length + head)
                        close()
                    }
                drawPath(path, arrowColour)
                drawLine(
                    color = arrowColour,
                    start = Offset(center.x, center.y - length + head * 0.7f),
                    end = Offset(center.x, center.y + length * 0.45f),
                    strokeWidth = 10.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

private fun clockFace(relativeDeg: Float): String {
    val hour = ((relativeDeg / 30f).toInt() + 12) % 12
    return "${if (hour == 0) 12 else hour} o'clock"
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
 * The unit's name, as the other units will see it.
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
        BackHeader("Unit name", onBack)
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
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Unit name" },
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
                    .semantics { contentDescription = "Save the unit name" },
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
