package org.itantra.app.ui

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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Board 17 — the control room, and board 23 — text size. `docs/REDESIGN.md` phase 4.
 *
 * ## Why ☰ stopped opening a list
 *
 * The menu behind ☰ was six rows of a title and a sentence describing what the screen was
 * for. Every one of those sentences was a definition the operator already knew — "Language:
 * what this unit speaks and reads" — and none of them carried a fact. Opening the menu to
 * find out which language was selected meant opening the menu *and then* the language
 * screen.
 *
 * Board 17 puts the answer on the row: **Language · हिन्दी**, **Storage · 412 MB**,
 * **Metrics · 780 ms**, **Licences · 2 restrictive**. The menu now answers questions instead
 * of only routing to the screens that answer them, and the screens are still one tap away.
 * That is rule 8 unchanged and rule 8 made useful.
 */
@Composable
fun ControlRoomScreen(
    state: AppState,
    onOpen: (Destination) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Whether traffic is going out under the fixed development key.
     *
     * Defaulted to true and not read from [AppState] because it is true: there is no key
     * exchange in this build, every unit holds the same key, and `docs/REDESIGN.md` records
     * pairing as W6.11. When a real key exchange lands this becomes a field rather than a
     * constant — inventing the field now would only let the banner lie earlier.
     */
    unsecured: Boolean = true,
) {
    val p = palette
    val operating = state.operating
    Column(
        modifier
            .fillMaxSize()
            .background(p.ground)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        BackHeader("Control room", onBack)

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            UnitHeroCard(operating)

            Column(
                Modifier
                    .fillMaxWidth()
                    .background(p.paper, RoundedCornerShape(Tokens.RadiusTile))
                    .border(Tokens.Hairline, p.hairline, RoundedCornerShape(Tokens.RadiusTile)),
            ) {
                val rows = controlRoomRows(state, p)
                rows.forEachIndexed { index, row ->
                    if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(p.sunken))
                    DestinationRow(row) { onOpen(row.destination) }
                }
            }
        }

        if (unsecured) UnsecuredBar()
    }
}

/** One row of the control room: a coloured glyph, a name, and the answer. */
private data class ControlRow(
    val destination: Destination,
    val icon: ImageVector,
    val tint: Color,
    val label: String,
    val value: String,
    /** True when the value is a figure and should be set in the instrument face. */
    val mono: Boolean = true,
    /** Ink for the value, when the value itself carries a warning. */
    val valueInk: Color? = null,
)

@Composable
private fun controlRoomRows(
    state: AppState,
    p: ItantraPalette,
): List<ControlRow> {
    val operating = state.operating
    val restrictive = state.licences.count { it.isRestrictive && it.shipped }
    val storedBytes = state.packs.sumOf { it.bytes }
    val scale = LocalDensity.current.fontScale
    return listOf(
        ControlRow(
            Destination.MESSAGES,
            Icons.Replay,
            p.aqua.core,
            "Messages",
            "${operating.messages.size} · 24 h",
        ),
        ControlRow(
            Destination.LANGUAGE,
            Icons.Globe,
            p.orchid.core,
            "Language",
            operating.language,
            mono = false,
            valueInk = p.orchid.deep,
        ),
        ControlRow(
            Destination.METRICS,
            Icons.Chart,
            p.sky.core,
            "Metrics",
            operating.metrics.totalMillis?.let { "$it ms" } ?: "—",
        ),
        ControlRow(
            Destination.MODE,
            Icons.OpenLine,
            p.periwinkle.core,
            "Mode and radio",
            "${operating.mode} · ${operating.transportName}",
        ),
        ControlRow(
            Destination.STORAGE,
            Icons.Storage,
            p.butter.core,
            "Storage",
            if (storedBytes > 0) megabytes(storedBytes) else "—",
        ),
        ControlRow(
            Destination.TEST_ALERT,
            Icons.Alert,
            p.blush.core,
            "Test alert",
            "this handset",
            mono = false,
        ),
        ControlRow(
            Destination.TEXT_SIZE,
            Icons.Theme,
            p.muted,
            "Text size",
            "${Math.round(scale * 100)} %",
        ),
        ControlRow(
            Destination.LICENCES,
            Icons.Document,
            p.muted,
            "Licences",
            if (restrictive > 0) "$restrictive restrictive" else "all permissive",
            mono = false,
            valueInk = if (restrictive > 0) p.blush.deep else null,
        ),
    )
}

@Composable
private fun DestinationRow(
    row: ControlRow,
    onOpen: () -> Unit,
) {
    val p = palette
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Tokens.SecondaryAction)
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "${row.label}, ${row.value}"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(row.icon, contentDescription = null, tint = row.tint, modifier = Modifier.size(22.dp))
        Text(
            row.label,
            fontSize = Tokens.Body,
            fontWeight = FontWeight.Medium,
            color = p.ink,
            modifier = Modifier.weight(1f),
        )
        Text(
            row.value,
            fontSize = if (row.mono) Tokens.Label else Tokens.BodySmall,
            fontFamily = if (row.mono) FontFamily.Monospace else FontFamily.Default,
            fontWeight = if (row.mono) FontWeight.Medium else FontWeight.SemiBold,
            lineHeight = Tokens.BodySmall * Tokens.INDIC_LINE_HEIGHT,
            color = row.valueInk ?: p.muted,
        )
        Icon(Icons.Forward, contentDescription = null, tint = p.hairlineStrong, modifier = Modifier.size(18.dp))
    }
}

/**
 * This unit, as the one card that is not a row.
 *
 * It carries the cipher because a jury asks what the encryption is and the answer should be
 * on the screen rather than in a slide, and the paired count because "how many can hear me"
 * is the question the operating screen's pill answers in one glance and this screen should
 * answer in words.
 */
@Composable
private fun UnitHeroCard(operating: OperatingState) {
    val p = palette
    Column(
        Modifier
            .fillMaxWidth()
            .background(p.periwinkle.core, RoundedCornerShape(Tokens.RadiusCard))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .background(Color.White.copy(alpha = 0.16f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Transmit, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    operating.unitName,
                    fontSize = Tokens.Subtitle,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Text(
                    "node ${"%02d".format(operating.nodeId)} · $CIPHER",
                    fontSize = Tokens.Instrument,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.75f),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Pairing is task W6.11 and does not exist: every unit holds the same fixed
            // development key and handsets are bonded in Android's own Bluetooth settings.
            // The board draws this tile, so it is drawn — unavailable, and saying so. A
            // control that offered a code which pairs nothing would be the most convincing
            // lie in the application.
            HeroTile(Icons.Qr, "ADD A UNIT", enabled = false, modifier = Modifier.weight(1f))
            HeroTile(
                Icons.Bluetooth,
                "${operating.peerCount} PAIRED",
                enabled = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun HeroTile(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    modifier: Modifier,
) {
    Column(
        modifier
            .heightIn(min = Tokens.DockFlank)
            .background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(Tokens.RadiusControl))
            .then(if (enabled) Modifier else Modifier.alpha(0.45f))
            .semantics(mergeDescendants = true) {
                contentDescription = if (enabled) label else "$label, not available in this build"
            },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = Tokens.Instrument, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}

/**
 * The unsecured bar, pinned under the list.
 *
 * Not dismissible and not a dialog. `SecurityBanners.kt` argues the point directly: a dialog
 * is acknowledged once and forgotten, where this condition persists for the whole life of
 * the build. It is the only saturated fill on the screen.
 */
@Composable
private fun UnsecuredBar() {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Tokens.AlertField.let { Color(0xFFBE123C) })
            .padding(horizontal = 14.dp, vertical = 11.dp)
            .semantics(mergeDescendants = true) {
                contentDescription =
                    "Unsecured. Development key. Anyone in range can read your messages."
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(34.dp)
                .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.LockOpen, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text("UNSECURED", fontSize = Tokens.Label, fontWeight = FontWeight.Bold, color = Color.White)
            Text(
                "Development key. Anyone in range can read them.",
                fontSize = Tokens.Instrument,
                lineHeight = Tokens.Instrument * 1.3f,
                color = Color.White,
            )
        }
    }
}

/** What the frames are sealed with, stated where a reader will ask for it. */
private const val CIPHER = "AES-256-GCM"

private fun megabytes(bytes: Long): String = String.format(Locale.ROOT, "%.0f MB", bytes / 1_000_000.0)

// ── board 23 ─────────────────────────────────────────────────────────────────

/**
 * Board 23 — text size. Resolves gap **G4**.
 *
 * ## It reports and previews; it does not set
 *
 * There is no slider to drag. Android already owns text scaling, and a second control that
 * disagreed with the system one would be a setting the operator has to keep in two places.
 * What was missing was not a control — it was an *answer*: at 200 % does this interface still
 * work, and does Devanagari still fit its line box?
 *
 * So the screen shows the scale in force, and then the type ramp rendered at it: display,
 * title, body, an Indic line at 1.4 ×, and the instrument face. An operator or a reviewer
 * turns the system setting up and watches this screen prove the claim rather than reading it.
 *
 * The claim being proved is `docs/UX.md` rule 9 and the reason every touch target in this
 * codebase is a `heightIn(min = …)` rather than a `size(…)`.
 */
@Composable
fun TextSizeScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = palette
    val scale = LocalDensity.current.fontScale
    val percent = Math.round(scale * 100)
    // 100 % sits at the left end and 200 % at the right, which is the range rule 9 names.
    val fraction = ((scale - 1f) / 1f).coerceIn(0f, 1f)

    Column(
        modifier
            .fillMaxSize()
            .background(p.ground)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        BackHeader("Text size", onBack)

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionLabel("TEXT SIZE")

            Column(
                Modifier
                    .fillMaxWidth()
                    .background(p.paper, RoundedCornerShape(Tokens.RadiusTile))
                    .border(Tokens.Hairline, p.hairline, RoundedCornerShape(Tokens.RadiusTile))
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("A", fontSize = Tokens.Status, color = p.muted)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .background(p.sunken, RoundedCornerShape(Tokens.RadiusPill)),
                        )
                        Box(
                            Modifier
                                .fillMaxWidth(fraction.coerceAtLeast(0.02f))
                                .height(5.dp)
                                .background(p.periwinkle.core, RoundedCornerShape(Tokens.RadiusPill)),
                        )
                    }
                    Text("A", fontSize = Tokens.Headline, color = p.ink)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Instrument("100 %", p.muted)
                    Instrument("$percent %", p.periwinkle.deep)
                    Instrument("200 %", p.muted)
                }
            }

            Text(
                "Follows the system setting. Every screen holds its layout to 200 % without truncating.",
                fontSize = Tokens.Label,
                lineHeight = Tokens.Label * 1.5f,
                color = p.muted,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )

            SectionLabel("PREVIEW AT THIS SIZE")

            Column(
                Modifier
                    .fillMaxWidth()
                    .background(p.paper, RoundedCornerShape(Tokens.RadiusTile))
                    .border(Tokens.Hairline, p.hairline, RoundedCornerShape(Tokens.RadiusTile)),
            ) {
                RampRow("ALERT", "display", Tokens.Display, FontWeight.Bold, first = true)
                RampRow("Push to talk", "title", Tokens.Title, FontWeight.Bold)
                RampRow("need help now, two injured", "body", Tokens.Body, FontWeight.Normal)
                RampRow("हिन्दी सहायता", "indic 1.4×", Tokens.Subtitle, FontWeight.SemiBold, indic = true)
                RampRow("TOTAL 780 ms · 44 B", "instrument", Tokens.Instrument, FontWeight.Medium, mono = true)
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(p.paper)
                .padding(
                    start = Tokens.ScreenMargin,
                    end = Tokens.ScreenMargin,
                    top = 12.dp,
                    bottom = Tokens.ScreenMargin,
                ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.padding(top = 6.dp).size(7.dp).background(p.orchid.core, CircleShape))
            Text(
                "Indic scripts reserve 1.4× the Latin line box at every size, so matras and " +
                    "conjuncts never clip.",
                fontSize = Tokens.Label,
                lineHeight = Tokens.Label * 1.45f,
                color = p.muted,
            )
        }
    }
}

@Composable
private fun RampRow(
    sample: String,
    role: String,
    size: androidx.compose.ui.unit.TextUnit,
    weight: FontWeight,
    first: Boolean = false,
    indic: Boolean = false,
    mono: Boolean = false,
) {
    val p = palette
    if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(p.sunken))
    Row(
        Modifier.fillMaxWidth().padding(14.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            sample,
            fontSize = size,
            fontWeight = weight,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            // The whole point of the row: the Indic sample gets the 1.4 box and is visibly
            // taller than the Latin one beside it at every scale.
            lineHeight = if (indic) size * Tokens.INDIC_LINE_HEIGHT else size * 1.2f,
            color = if (mono) p.sky.deep else p.ink,
            modifier = Modifier.weight(1f),
        )
        Instrument(role, p.muted)
    }
}

@Composable
private fun SectionLabel(text: String) {
    val p = palette
    Text(
        text,
        fontSize = Tokens.Instrument,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        color = p.muted,
        modifier = Modifier.padding(start = 4.dp),
    )
}

@Composable
private fun Instrument(
    text: String,
    colour: Color,
) {
    Text(
        text,
        fontSize = Tokens.Instrument,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        color = colour,
    )
}
