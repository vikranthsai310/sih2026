package org.itantra.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Settings, in four screens. Tasks W7.19, W7.20 and W7.21.
//
// Every row is at least 64 dp and every icon has a spoken description, because the
// operator may be gloved, in the dark, or unable to read (task W7.23).

/**
 * Task **W7.19**. Mode and transport.
 *
 * Both are shown with their consequence, not just their name. "Push-to-talk" means
 * nothing to someone choosing for the first time; "one at a time, longest battery" does.
 */
@Composable
fun ModeAndTransportScreen(
    pushToTalk: Boolean,
    onModeChange: (Boolean) -> Unit,
    transport: String,
    transports: List<TransportOption>,
    onTransportChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(Paper).padding(16.dp)) {
        Text("MODE", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(8.dp))

        ChoiceRow(
            selected = pushToTalk,
            title = "Push to talk",
            detail = "One at a time. Hold the key to speak. Longest battery life.",
            onClick = { onModeChange(true) },
        )
        ChoiceRow(
            selected = !pushToTalk,
            title = "Open conversation",
            detail = "Both sides at once, like a phone call. Uses more power.",
            onClick = { onModeChange(false) },
        )

        Spacer(Modifier.height(24.dp))
        Text("TRANSPORT", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(8.dp))

        for (option in transports) {
            ChoiceRow(
                selected = option.id == transport,
                title = option.name,
                // Range and endurance are the two things that decide this, so they are
                // what the row shows.
                detail = "${option.range} · ${option.endurance}",
                onClick = { onTransportChange(option.id) },
            )
        }
    }
}

data class TransportOption(
    val id: String,
    val name: String,
    val range: String,
    val endurance: String,
)

/**
 * Task **W7.20**. Language.
 *
 * ## Own script first
 *
 * A speaker of Odia looking for their language is looking for **ଓଡ଼ିଆ**, not for the word
 * "Odia" written in Latin script. The English gloss is second, for the operator setting
 * up someone else's handset.
 *
 * ## The licence warning is in the product
 *
 * Where a language's voice carries a non-commercial licence, that is surfaced **here**,
 * on the row, at the moment of choosing — not only in a document nobody reads. Four of
 * the ten languages have no permissively licensed voice at all, and a pack that cannot be
 * deployed commercially is a fact the operator is entitled to before they depend on it.
 */
@Composable
fun LanguageScreen(
    languages: List<LanguageOption>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(Paper).padding(16.dp)) {
        Text("LANGUAGE", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(languages) { language ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = ROW_DP.dp)
                        .border(
                            if (language.code == selected) 2.dp else 1.dp,
                            if (language.code == selected) Ink else Muted,
                            RoundedCornerShape(6.dp),
                        )
                        .clickable { onSelect(language.code) }
                        .padding(12.dp)
                        .semantics {
                            contentDescription =
                                "${language.englishName}. ${language.availability()}"
                        },
                ) {
                    // Own script, at the largest size on the row.
                    Text(language.nativeName, fontSize = 20.sp, color = Ink)
                    Text(language.englishName, fontSize = 13.sp, color = Muted)

                    if (!language.canSpeak) {
                        Text(
                            "No voice available — messages arrive as text only",
                            fontSize = 12.sp,
                            color = Danger,
                        )
                    }
                    language.recognition?.let {
                        Text("Speech in: $it", fontSize = 12.sp, color = Muted)
                    }
                    if (language.nonCommercialVoice) {
                        Text(
                            "Voice licensed for non-commercial use only (CC-BY-NC)",
                            fontSize = 12.sp,
                            color = Danger,
                        )
                    }
                }
            }
        }
    }
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

/**
 * Task **W7.21**. Storage, with the per-pack licence on the row.
 *
 * The licence belongs beside the size because that is where the decision is made. An
 * operator freeing space chooses which pack to delete, and "this one cannot be deployed
 * commercially anyway" is exactly the information that decides it.
 */
@Composable
fun StorageScreen(
    packs: List<PackRow>,
    onDelete: (PackRow) -> Unit,
    /** Opens the folder picker. Null hides the control, for a build without an installer. */
    onImport: (() -> Unit)? = null,
    /** What the last import did, or what it is doing now. */
    status: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(Paper).padding(16.dp)) {
        Text("STORAGE", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(4.dp))
        Text(
            "%.1f MB used by language packs".format(packs.sumOf { it.bytes } / 1_048_576.0),
            fontSize = 14.sp,
            color = Muted,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(12.dp))

        if (onImport != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(2.dp, Ink, RoundedCornerShape(6.dp))
                    .clickable { onImport() }
                    .padding(12.dp)
                    .semantics {
                        contentDescription = "Install a language pack from a folder on this device"
                    },
            ) {
                Text("INSTALL A LANGUAGE PACK", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink)
                // Said plainly, because the operator has to find the right folder and the
                // wrong one produces a copy of nothing.
                Text(
                    "Copy the models folder onto this phone, then pick it here. " +
                        "It is the folder containing asr and tts.",
                    fontSize = 12.sp,
                    color = Muted,
                )
            }
            if (status != null) {
                Spacer(Modifier.height(8.dp))
                Text(status, fontSize = 13.sp, color = Ink, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(12.dp))
        }

        if (packs.isEmpty()) {
            Text(
                "No language packs on this handset. Transmit sends a template and nothing " +
                    "is spoken aloud until one is installed.",
                fontSize = 14.sp,
                color = Muted,
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(packs) { pack ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = ROW_DP.dp)
                        .border(1.dp, Muted, RoundedCornerShape(6.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(pack.name, fontSize = 16.sp, color = Ink)
                        Text(
                            "%.1f MB · %s".format(pack.bytes / 1_048_576.0, pack.licence),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (pack.isRestrictive) Danger else Muted,
                        )
                    }
                    Text(
                        "DELETE",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Danger,
                        modifier =
                            Modifier
                                .clickable { onDelete(pack) }
                                .semantics { contentDescription = "Delete ${pack.name} pack" },
                    )
                }
            }
        }
    }
}

data class PackRow(
    val name: String,
    val bytes: Long,
    val licence: String,
) {
    /** Anything non-commercial or copyleft, which the row colours differently. */
    val isRestrictive: Boolean
        get() =
            licence.contains("NC", ignoreCase = true) ||
                licence.contains("GPL", ignoreCase = true)
}

/**
 * Task **W7.21**, the about screen.
 *
 * Every third-party component is listed with its licence, including the two restrictive
 * ones. Disclosing them in the product rather than only in a repository file is the
 * difference between a disclosure and a technicality.
 */
@Composable
fun AboutScreen(
    components: List<LicenceRow>,
    /** The one sentence a GPL-3.0 obligation is discharged by. Null when nothing is copyleft. */
    distributionNotice: String? = null,
    onOpenLicence: (LicenceRow) -> Unit = { },
    modifier: Modifier = Modifier,
) {
    val (inBuild, considered) = components.partition { it.shipped }
    Column(modifier.fillMaxSize().background(Paper).padding(16.dp)) {
        Text("LICENCES", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (distributionNotice != null) {
                item {
                    // Not a footnote. GPL-3.0 obliges the distributor to say this, and a
                    // reader looking for it should find it before the list rather than after.
                    Text(
                        distributionNotice,
                        fontSize = 13.sp,
                        color = Ink,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }

            item { SectionHeading("IN THIS BUILD") }
            items(inBuild) { row -> LicenceEntry(row, onOpenLicence) }

            if (considered.isNotEmpty()) {
                item { SectionHeading("CONSIDERED, NOT USED") }
                items(considered) { row -> LicenceEntry(row, onOpenLicence) }
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = Muted,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun LicenceEntry(
    row: LicenceRow,
    onOpen: (LicenceRow) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (row.licenceFile != null) Modifier.clickable { onOpen(row) } else Modifier)
            .semantics {
                contentDescription =
                    buildString {
                        append(row.component)
                        append(". ")
                        append(row.licence)
                        append(". ")
                        append(if (row.shipped) "In this build. " else "Not used. ")
                        row.note?.let { append(it) }
                        if (row.licenceFile != null) append(" Tap to read the licence.")
                    }
            },
    ) {
        Text(row.component, fontSize = 16.sp, color = if (row.shipped) Ink else Muted)
        Text(
            // A restrictive licence is only worth a warning colour when the thing is
            // actually here. Colouring an unused candidate red says the opposite of what
            // the row means.
            if (row.licenceFile != null) "${row.licence}  ›" else row.licence,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            color = if (row.isRestrictive && row.shipped) Danger else Muted,
        )
        if (row.note != null) {
            Text(row.note, fontSize = 12.sp, color = Muted)
        }
    }
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

@Composable
private fun ChoiceRow(
    selected: Boolean,
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_DP.dp)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) Ink else Muted,
                RoundedCornerShape(6.dp),
            )
            .clickable(onClick = onClick)
            .padding(12.dp)
            .semantics { contentDescription = "$title. $detail" },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (selected) "◉" else "○", fontSize = 18.sp, color = Ink)
            Spacer(Modifier.height(0.dp))
            Text(
                "  $title",
                fontSize = 16.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = Ink,
            )
        }
        Text(detail, fontSize = 13.sp, color = Muted)
    }
}

/** Rows are 64 dp, not 96: these are settings, not controls used under pressure. */
private const val ROW_DP = 64
private val Ink = Color(0xFF101010)
private val Paper = Color(0xFFFFFFFF)
private val Muted = Color(0xFF5F5F5F)
private val Danger = Color(0xFFB3261E)
