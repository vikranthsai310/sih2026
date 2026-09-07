package org.itantra.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Settings, in four screens. Tasks W7.19, W7.20 and W7.21.
//
// Every row is at least 64 dp and every icon has a spoken description, because the
// operator may be gloved, in the dark, or unable to read (task W7.23).

/**
 * Task **W7.19**, board 19. Mode and radio — every choice with its cost beside it.
 *
 * ## What the board asks for and what the radio can honour
 *
 * Board 19 draws mode as two selectable cards and transport as four radio rows. Neither
 * selection exists, and the two are unavailable for different reasons that matter:
 *
 * - **Mode** is push-to-talk everywhere. `MessageEngine` sets `mode` to the literal `"PTT"`
 *   in both places it is set and `DuplexPolicy` in `core-audio` has never had a caller. So
 *   the cards are drawn — with their costs, which is the board's real contribution — and
 *   the unavailable one says it is not in this build rather than taking a tap and doing
 *   nothing.
 * - **Transport is not a chooser at all**, and drawing radios there would be worse than
 *   unimplemented, it would be *wrong*. Every channel runs at once; a frame goes down every
 *   one that is up and the replay window discards whichever copy arrives second. A selected
 *   radio would tell the operator their message left on one radio when it left on four.
 *
 * ## The cost chips
 *
 * "Push-to-talk" means nothing to someone choosing for the first time. `half duplex ·
 * 800–1200 ms · lowest power` does, and it is the same three facts for both modes, in the
 * same order, so they can be compared rather than read.
 */
@Composable
fun ModeAndTransportScreen(
    transports: List<TransportOption>,
    modifier: Modifier = Modifier,
) {
    val p = palette
    Column(
        modifier
            .fillMaxSize()
            .background(p.ground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Mode and radio",
            fontSize = Tokens.Title,
            fontWeight = FontWeight.Bold,
            color = p.ink,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )

        SettingsLabel("MODE")
        ModeCard(
            name = "Push to talk",
            icon = Icons.Transmit,
            selected = true,
            costs = listOf("half duplex", "800–1200 ms", "lowest power"),
            note = null,
        )
        ModeCard(
            name = "Phone",
            icon = Icons.OpenLine,
            selected = false,
            costs = listOf("full duplex", "1050–1500 ms", "higher power"),
            note = "Open conversation — both sides at once — is not in this build.",
        )

        SettingsLabel("TRANSPORT", top = 8.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .background(p.paper, RoundedCornerShape(Tokens.RadiusTile))
                .border(Tokens.Hairline, p.hairline, RoundedCornerShape(Tokens.RadiusTile)),
        ) {
            transports.forEachIndexed { index, option ->
                if (index > 0) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(p.sunken))
                }
                ChannelRow(option)
            }
            if (transports.isEmpty()) {
                Text(
                    "No radio is running on this handset.",
                    fontSize = Tokens.BodySmall,
                    color = p.muted,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }

        Column(
            Modifier.padding(start = 4.dp, top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            SettingsInstrument("four transports behind one interface")
            SettingsInstrument("a frame is a frame on all of them")
        }
    }
}

/** One mode, with the three costs that let it be compared with the other. */
@Composable
private fun ModeCard(
    name: String,
    icon: ImageVector,
    selected: Boolean,
    costs: List<String>,
    note: String?,
) {
    val p = palette
    val shape = RoundedCornerShape(Tokens.RadiusTile)
    Column(
        Modifier
            .fillMaxWidth()
            .background(p.paper, shape)
            .border(
                if (selected) Tokens.SignalBorder else Tokens.Hairline,
                if (selected) p.periwinkle.core else p.hairline,
                shape,
            )
            .padding(16.dp)
            .semantics(mergeDescendants = true) {
                contentDescription =
                    name + (if (selected) ", in use" else ", not in this build") +
                    ". " + costs.joinToString(", ")
            },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Marker(selected, p.periwinkle.core)
            Text(
                name,
                fontSize = Tokens.Callout,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = p.ink,
                modifier = Modifier.weight(1f),
            )
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) p.periwinkle.core else p.hairlineStrong,
                modifier = Modifier.size(22.dp),
            )
        }
        Row(
            Modifier.padding(start = 33.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            costs.forEach { cost ->
                Text(
                    cost,
                    fontSize = Tokens.Instrument,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = if (selected) p.periwinkle.deep else p.muted,
                    modifier =
                        Modifier
                            .background(
                                if (selected) p.periwinkle.tint else p.sunken,
                                RoundedCornerShape(Tokens.RadiusInset),
                            )
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                )
            }
        }
        note?.let {
            Text(
                it,
                fontSize = Tokens.Label,
                lineHeight = Tokens.Label * 1.4f,
                color = p.muted,
                modifier = Modifier.padding(start = 33.dp),
            )
        }
    }
}

/**
 * One channel, with what it is doing rather than a control that pretends to switch it.
 *
 * The status word carries the meaning, so it is the coloured one: an operator glancing here
 * is asking "is anything getting out", not reading a list of radio names.
 *
 * **No signal bars.** Board 19 draws a four-bar meter per transport, which reads as range.
 * `TransportOption` carries a `detail` string and a link state and no reach at all, and a
 * bar chart derived from either would be a measurement this application has not made — on a
 * screen whose neighbours are all real numbers. The state is said in words instead.
 */
@Composable
private fun ChannelRow(option: TransportOption) {
    val p = palette
    val family = if (option.carrying) p.mint else p.butter
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Tokens.SecondaryAction)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "${option.name}. ${option.status}. ${option.detail}"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(
            Modifier
                .size(10.dp)
                .background(if (option.carrying) family.core else p.hairlineStrong, CircleShape),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                option.name,
                fontSize = Tokens.Body,
                fontWeight = if (option.carrying) FontWeight.SemiBold else FontWeight.Medium,
                color = p.ink,
            )
            SettingsInstrument(option.detail)
        }
        Text(
            option.status,
            fontSize = Tokens.Instrument,
            fontWeight = FontWeight.SemiBold,
            color = if (option.carrying) family.deep else p.muted,
            modifier =
                Modifier
                    .background(
                        if (option.carrying) family.tint else p.sunken,
                        RoundedCornerShape(Tokens.RadiusInset),
                    )
                    .padding(horizontal = 8.dp, vertical = 5.dp),
        )
    }
}

/**
 * A channel as the settings screen shows it.
 *
 * [carrying] is deliberately not "enabled": nothing here can be enabled or disabled by the
 * operator, and a control that looks switchable and is not is the defect this replaced.
 */
data class TransportOption(
    val id: String,
    val name: String,
    val status: String,
    val detail: String,
    val carrying: Boolean,
)

/**
 * Task **W7.20**, board 18. Language.
 *
 * ## Own script first
 *
 * A speaker of Odia looking for their language is looking for **ଓଡ଼ିଆ**, not for the word
 * "Odia" written in Latin script, and certainly not for `or`. The native name is the row's
 * heading at 19 sp with the 1.4 × Indic line box; the English gloss is second and smaller,
 * for the operator setting up someone else's handset.
 *
 * ## The two ways a language fails, kept apart
 *
 * `canSpeak` and `recognition` fail independently. **Text only** on a row means a message
 * arrives written and is never spoken aloud — it is not a quality warning, it is a
 * statement that half the product does not happen for that language. The footer says so in
 * words rather than leaving a chip to be guessed at.
 *
 * ## The licence warning is in the product
 *
 * Where a voice carries a non-commercial licence, that is surfaced **here**, on the row, at
 * the moment of choosing — not only in a document nobody reads.
 *
 * ## What board 18 asks for that this cannot show
 *
 * The board splits the list into ON THIS HANDSET and NOT INSTALLED, with a size and a
 * download control on each uninstalled row. [LanguageOption] carries no installed flag —
 * what is on disk is `Download`/`PackRow`, which board 20 owns and this screen is not
 * given. Rather than infer installedness from `canSpeak`, which is a different fact, the
 * list stays flat and the download path stays on the storage screen where the data is.
 */
@Composable
fun LanguageScreen(
    languages: List<LanguageOption>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = palette
    val voices = languages.count { it.canSpeak }
    Column(
        modifier
            .fillMaxSize()
            .background(p.ground),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Language", fontSize = Tokens.Title, fontWeight = FontWeight.Bold, color = p.ink)
            Spacer(Modifier.weight(1f))
            SettingsInstrument("$voices of ${languages.size} speak")
        }

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(languages) { language ->
                LanguageRow(language, language.code == selected) { onSelect(language.code) }
            }
            item { Spacer(Modifier.height(4.dp)) }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(p.paper)
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.padding(top = 6.dp).size(7.dp).background(p.butter.core, CircleShape))
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = p.ink)) {
                        append("Text only")
                    }
                    append(
                        " means a message arrives written but is never spoken. " +
                            "Recognition and voice fail independently.",
                    )
                },
                fontSize = Tokens.Label,
                lineHeight = Tokens.Label * 1.45f,
                color = p.muted,
            )
        }
    }
}

/**
 * One language.
 *
 * Selection carries three ways at once — a filled marker, a 2 dp signal border and a weight
 * change — because `docs/UX.md` rule 3 and the greyscale law both apply, and a ring alone is
 * the first thing to disappear in sunlight.
 */
@Composable
private fun LanguageRow(
    language: LanguageOption,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val p = palette
    val shape = RoundedCornerShape(Tokens.RadiusTile)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Tokens.SecondaryAction)
            .background(p.paper, shape)
            .border(
                if (selected) Tokens.SignalBorder else Tokens.Hairline,
                if (selected) p.orchid.core else p.hairline,
                shape,
            )
            .clickable(onClick = onSelect)
            .padding(14.dp)
            .semantics(mergeDescendants = true) {
                contentDescription =
                    language.nativeName + ", " + language.englishName + ". " +
                    language.availability() + if (selected) ". Current." else ""
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Marker(selected, p.orchid.core)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                language.nativeName,
                fontSize = Tokens.Subtitle,
                fontWeight = FontWeight.SemiBold,
                // The whole reason INDIC_LINE_HEIGHT exists: ଓଡ଼ିଆ and മലയാളം clip against
                // the default box at this size, and this is the screen that shows all ten.
                lineHeight = Tokens.Subtitle * Tokens.INDIC_LINE_HEIGHT,
                color = p.ink,
            )
            Text(
                language.englishName + (language.recognition?.let { " · $it" } ?: ""),
                fontSize = Tokens.Label,
                lineHeight = Tokens.Label * 1.3f,
                color = p.muted,
            )
        }
        when {
            !language.canSpeak -> RowTag("Text only", p.butter)
            language.nonCommercialVoice -> RowTag("CC-BY-NC", p.blush)
            else -> Unit
        }
    }
}

/** A small tag on the right of a row. Always a word, never a colour on its own. */
@Composable
private fun RowTag(
    text: String,
    family: ItantraPalette.Family,
) {
    Text(
        text,
        fontSize = Tokens.Instrument,
        fontWeight = FontWeight.SemiBold,
        color = family.deep,
        modifier =
            Modifier
                .background(family.tint, RoundedCornerShape(Tokens.RadiusInset))
                .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/**
 * The selected marker: a filled disc with a punched centre, or a hollow ring.
 *
 * Drawn rather than `RadioButton`, because Material's carries its own colour scheme and
 * `Tokens` exists so that there is exactly one source of colour in this application.
 */
@Composable
private fun Marker(
    selected: Boolean,
    colour: Color,
) {
    val p = palette
    Box(
        Modifier
            .size(20.dp)
            .then(
                if (selected) {
                    Modifier.background(colour, CircleShape)
                } else {
                    Modifier.border(2.dp, p.hairlineStrong, CircleShape)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(Modifier.size(7.dp).background(p.paper, CircleShape))
        }
    }
}

/** A section heading in the instrument face, as every board sets them. */
@Composable
private fun SettingsLabel(
    text: String,
    top: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val p = palette
    Text(
        text,
        fontSize = Tokens.Instrument,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        color = p.muted,
        modifier = Modifier.padding(start = 4.dp, top = top),
    )
}

/** A figure or a machine fact, in the instrument face. */
@Composable
private fun SettingsInstrument(text: String) {
    val p = palette
    Text(
        text,
        fontSize = Tokens.Instrument,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        color = p.muted,
    )
}

data class LanguageOption(
    val code: String,
    val nativeName: String,
    val englishName: String,
    /** Whether a **voice** exists to speak arriving messages aloud. Synthesis, not recognition. */
    val canSpeak: Boolean,
    val nonCommercialVoice: Boolean = false,
    /**
     * Whether this handset can **recognise** speech in this language, said in words.
     *
     * Separate from [canSpeak] because they fail independently and for different reasons:
     * a language can be understood and not spoken back, which is the state every language
     * is in on this build.
     */
    val recognition: String? = null,
) {
    fun availability(): String =
        when {
            !canSpeak -> "Text only, no voice available"
            nonCommercialVoice -> "Voice is non-commercial licensed"
            else -> "Available"
        }
}

/** Bytes an operator can act on: "0 MB" for a 66 kB file reads as nothing to download. */
internal fun describeSize(bytes: Long): String =
    when {
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.0f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024 -> "%.0f kB".format(bytes / 1_024.0)
        else -> "$bytes B"
    }

/**
 * One file a language can use, fetched by the operator's browser and verified here.
 *
 * Carries its language and whether it is already on this handset, so one list can
 * describe every file for every language and the screen can say which are still needed.
 */
data class Download(
    val kind: String,
    val bytes: Long,
    val url: String,
    val languageCode: String = "",
    val installed: Boolean = false,
)

data class PackRow(
    val name: String,
    val bytes: Long,
    val licence: String,
    /**
     * What this row is, kept separately from [name] because the delete control acts on it.
     *
     * [name] is "hi · voice", built for reading. Parsing it back to find out what to
     * delete would make a display string load-bearing, which is how a screen ends up
     * deleting the wrong thing after somebody improves the wording.
     */
    val languageCode: String = "",
    val kind: String = "",
    /** espeak's data is bundled and shared, so it has no delete control. */
    val deletable: Boolean = false,
) {
    /** Anything non-commercial or copyleft, which the row colours differently. */
    val isRestrictive: Boolean
        get() =
            licence.contains("NC", ignoreCase = true) ||
                licence.contains("GPL", ignoreCase = true)
}

/**
 * Task **W7.21**, board 24. About and licences — set as a document, not as a settings list.
 *
 * ## Why the copyleft notice is first and not last
 *
 * GPL-3.0 obliges this build to offer the corresponding source. An obligation discharged at
 * the bottom of a scrolling list is discharged in form only, so it is the first thing on the
 * screen, on a red rule, in the voice of a notice rather than a row.
 *
 * ## Why "considered, not used" exists at all
 *
 * A licence page that lists only what shipped answers "what is in this" and not "what did
 * you refuse". Whisper.cpp and a vendor voice SDK were both evaluated and both rejected —
 * one for speed on entry-tier hardware, one because it is proprietary — and a reader
 * checking whether anything closed-source is in here is better served by seeing the
 * rejection than by inferring it from an absence. The leader dots are the typographic device
 * a bibliography uses for exactly this: an entry and its disposition, coupled across a gap.
 *
 * ## The two that are marked
 *
 * A restrictive licence gets a dot **and** the licence name **and** a sentence saying what
 * it constrains. Three carriers, because the entire cost of getting this wrong is legal.
 */
@Composable
fun AboutScreen(
    components: List<LicenceRow>,
    /** The one sentence a GPL-3.0 obligation is discharged by. Null when nothing is copyleft. */
    distributionNotice: String? = null,
    onOpenLicence: (LicenceRow) -> Unit = { },
    /** Build identity for the colophon. Null lines are simply absent. */
    buildLine: String? = null,
    modifier: Modifier = Modifier,
) {
    val p = palette
    val (inBuild, considered) = components.partition { it.shipped }
    Column(
        modifier
            .fillMaxSize()
            .background(p.ground),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "About",
                fontSize = Tokens.Title,
                fontWeight = FontWeight.Bold,
                color = p.ink,
                modifier = Modifier.padding(start = 2.dp, bottom = 4.dp),
            )

            distributionNotice?.let { notice ->
                Column(
                    Modifier
                        .drawLeftRule(p.blush.core)
                        .padding(start = 14.dp, top = 2.dp, bottom = 2.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription = "Copyleft notice. $notice"
                        },
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "Copyleft notice",
                        fontSize = Tokens.Label,
                        fontWeight = FontWeight.SemiBold,
                        color = p.blush.deep,
                    )
                    Text(
                        notice,
                        fontSize = Tokens.Label,
                        lineHeight = Tokens.Label * 1.5f,
                        color = p.muted,
                    )
                }
            }

            if (inBuild.isNotEmpty()) {
                SettingsLabel("IN THIS BUILD", top = 8.dp)
                Column(Modifier.fillMaxWidth()) {
                    inBuild.forEach { row ->
                        Box(Modifier.fillMaxWidth().height(1.dp).background(p.hairline))
                        ComponentRow(row) { onOpenLicence(row) }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(p.hairline))
                }
            }

            if (considered.isNotEmpty()) {
                SettingsLabel("CONSIDERED, NOT USED", top = 8.dp)
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    considered.forEach { row -> RejectedRow(row) }
                }
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(p.paper)
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            buildLine?.let { SettingsInstrument(it) }
            SettingsInstrument("no proprietary voice SDK anywhere")
        }
    }
}

/** One component that shipped. Restrictive ones carry a dot, a name and a sentence. */
@Composable
private fun ComponentRow(
    row: LicenceRow,
    onOpen: () -> Unit,
) {
    val p = palette
    val openable = row.licenceFile != null
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .then(if (openable) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(horizontal = 2.dp, vertical = 11.dp)
            .semantics(mergeDescendants = true) {
                contentDescription =
                    buildString {
                        append("${row.component}, ${row.licence}")
                        row.note?.let { append(". $it") }
                        if (openable) append(". Opens the licence text.")
                    }
            },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                row.component,
                fontSize = Tokens.Body,
                fontWeight = FontWeight.Medium,
                lineHeight = Tokens.Body * 1.3f,
                color = p.ink,
                modifier = Modifier.weight(1f),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                if (row.isRestrictive) {
                    Box(Modifier.size(6.dp).background(p.blush.core, CircleShape))
                }
                Text(
                    row.licence,
                    fontSize = Tokens.Instrument,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = if (row.isRestrictive) p.blush.deep else p.muted,
                )
            }
        }
        row.note?.let {
            Text(it, fontSize = Tokens.Label, lineHeight = Tokens.Label * 1.4f, color = p.muted)
        }
    }
}

/** One component that was evaluated and rejected, with why, coupled by leader dots. */
@Composable
private fun RejectedRow(row: LicenceRow) {
    val p = palette
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "${row.component}, not used. ${row.note ?: row.licence}"
            },
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            row.component,
            fontSize = Tokens.Status,
            lineHeight = Tokens.Status * 1.35f,
            color = p.muted,
        )
        Box(
            Modifier
                .weight(1f)
                .padding(bottom = 5.dp)
                .height(1.dp)
                .background(p.hairline),
        )
        Text(
            row.note ?: row.licence,
            fontSize = Tokens.Instrument,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = p.muted,
        )
    }
}

/** A 2 dp rule down the left edge — the notice's own margin mark. */
private fun Modifier.drawLeftRule(colour: Color): Modifier =
    drawBehind {
        drawRect(
            color = colour,
            size = Size(2.dp.toPx(), size.height),
        )
    }

/**
 * A licence, in full and verbatim.
 *
 * GPL-3.0 section 4 obliges anyone conveying the work to give every recipient a copy of the
 * licence along with it. `LICENSES.md` section 6 named that obligation and said it would be
 * discharged "by shipping the licence text in the app's about screen" — and no licence text
 * was in the application at all. This is that, read from `assets/licences/`.
 */
@Composable
fun LicenceTextScreen(
    title: String,
    text: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(Paper).padding(16.dp)) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(12.dp))
        LazyColumn {
            item {
                Text(
                    text,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Ink,
                )
            }
        }
    }
}

data class LicenceRow(
    val component: String,
    val licence: String,
    val note: String? = null,
    /**
     * Whether this component is actually inside the installer.
     *
     * The distinction is the whole point of the screen. A reader who sees `GPL-3.0` in red
     * beside `CC-BY-NC` in red, in one undivided list, concludes that the application ships
     * both -- which was true of neither, and is a worse impression than the truth in one
     * case and a better one than the truth in the other.
     */
    val shipped: Boolean = true,
    /** An asset under `licences/`, when the licence obliges this build to carry its text. */
    val licenceFile: String? = null,
) {
    val isRestrictive: Boolean
        get() =
            licence.contains("NC", ignoreCase = true) ||
                licence.contains("GPL", ignoreCase = true)
}

/** Rows are 64 dp, not 96: these are settings, not controls used under pressure. */
private const val ROW_DP = 64
private val Ink = Color(0xFF101010)
private val Paper = Color(0xFFFFFFFF)
private val Muted = Color(0xFF5F5F5F)
private val Danger = Color(0xFFB3261E)
