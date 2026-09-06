package org.itantra.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
    transports: List<TransportOption>,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(Paper).padding(16.dp)) {
        Text("MODE", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(8.dp))

        // Stated, not offered. This was two selectable rows with an empty click handler
        // behind both: the engine is push-to-talk everywhere -- `mode = "PTT"` is a
        // constant in MessageEngine -- and `DuplexPolicy` in core-audio has never been
        // wired to it. A row that reads "Open conversation", takes a tap and changes
        // nothing is worse than a screen that says which mode this build runs.
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = ROW_DP.dp)
                .border(1.dp, Ink, RoundedCornerShape(6.dp))
                .padding(12.dp)
                .semantics {
                    contentDescription =
                        "Push to talk. One at a time. Hold the key to speak. " +
                        "This build has no other mode."
                },
        ) {
            Text("Push to talk", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text(
                "One at a time. Hold the key to speak. Longest battery life.",
                fontSize = 13.sp,
                color = Muted,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Open conversation — both sides at once — is not in this build.",
            fontSize = 13.sp,
            color = Muted,
        )

        Spacer(Modifier.height(24.dp))
        Text("CHANNELS", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(4.dp))
        // Not a chooser. Every channel runs at once and a frame goes down all of them; the
        // replay window discards the copy that arrives second. This section used to offer a
        // selection between one option, with an empty click handler behind it.
        Text(
            "All of these run at the same time. A message goes out on every one that is " +
                "up, and arrives once.",
            fontSize = 13.sp,
            color = Muted,
        )
        Spacer(Modifier.height(8.dp))

        for (option in transports) {
            ChannelRow(option)
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * One channel, with what it is doing rather than a control that pretends to switch it.
 *
 * The status word carries the meaning, so it is first and it is bold: an operator glancing
 * at this screen is asking "is anything getting out", not reading a list of radio names.
 */
@Composable
private fun ChannelRow(option: TransportOption) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_DP.dp)
            .border(1.dp, if (option.carrying) Ink else Muted, RoundedCornerShape(6.dp))
            .padding(12.dp)
            .semantics { contentDescription = "${option.name}. ${option.status}. ${option.detail}" },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (option.carrying) "●" else "○", fontSize = 18.sp, color = Ink)
            Text("  ${option.name}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink)
        }
        Text(option.status, fontSize = 14.sp, color = if (option.carrying) Ink else Muted)
        Text(option.detail, fontSize = 13.sp, color = Muted)
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
 * on the row, at the moment of choosing — not only in a document nobody reads. Three of
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
 * Task **W7.21**. Storage: every language, every file, and what to do about each.
 *
 * ## Why every language is listed, not just the chosen one
 *
 * This screen used to show only what the *current* language was missing — two rows, at
 * most. An operator provisioning a handset for a net that speaks Tamil and Hindi and
 * Odia had to switch language, come back, download, switch, come back, download. The
 * list of what a handset can be given is fixed and small — ten recognisers, seven voices
 * — so it is shown whole, grouped by language, with each file's state on its row.
 *
 * ## What a row does
 *
 * A file this handset does not have is a download: the tap hands its address to the
 * browser. A file it does have says so, and offers to delete it, with its licence beside
 * the size because that is where the decision is made. Three languages have no
 * permissively licensed voice at all; their row says that rather than showing nothing,
 * because a missing row reads as an oversight and a stated absence reads as a decision.
 *
 * The application cannot fetch any of these itself — it has no HTTP client, per
 * constraint C2 — so every download is done by the browser and verified here by SHA-256
 * when the operator taps install.
 */
@Composable
fun StorageScreen(
    packs: List<PackRow>,
    onDelete: (PackRow) -> Unit,
    /** Opens the file picker. Null hides the control, for a build without an installer. */
    onImport: (() -> Unit)? = null,
    /** What the last import did, or what it is doing now. */
    status: String? = null,
    /** Every file any language can use, for all ten, with whether this handset has it. */
    downloads: List<Download> = emptyList(),
    /** The ten languages in their fixed order, for the names on the headings. */
    languages: List<LanguageOption> = emptyList(),
    /** The language chosen on the operating screen, marked so the operator finds it first. */
    currentLanguage: String = "",
    /** Hands one address to the browser. Null leaves the rows as plain text. */
    onDownload: ((Download) -> Unit)? = null,
    /** Hands several addresses to the browser, one after another. */
    onDownloadAll: ((List<Download>) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val missing = downloads.filter { !it.installed }
    val groups = languageGroups(downloads, languages)
    // Rows this list of languages does not explain: espeak's data, shared by every voice.
    val shared =
        packs.filter { pack ->
            downloads.none { it.languageCode == pack.languageCode && it.kind == pack.kind }
        }

    LazyColumn(
        modifier.fillMaxSize().background(Paper),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("STORAGE", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text(
                "%.1f MB used by language packs".format(packs.sumOf { it.bytes } / 1_048_576.0),
                fontSize = 14.sp,
                color = Muted,
                fontFamily = FontFamily.Monospace,
            )
        }

        if (onImport != null) {
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .border(2.dp, Ink, RoundedCornerShape(6.dp))
                        .clickable { onImport() }
                        .padding(12.dp)
                        .semantics {
                            contentDescription = "Install language pack files you have downloaded"
                        },
                ) {
                    Text("INSTALL DOWNLOADED FILES", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink)
                    // The two steps, in order, because the second one is useless without the
                    // first and an operator who taps this with an empty Download folder should
                    // be told why nothing happened before it happens.
                    Text(
                        "1. Tap DOWNLOAD on the files below. Your browser saves them.\n" +
                            "2. Tap here, open Downloads, and select them all.",
                        fontSize = 12.sp,
                        color = Muted,
                    )
                }
                if (status != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(status, fontSize = 13.sp, color = Ink, fontFamily = FontFamily.Monospace)
                }
            }
        }

        if (missing.size > 1 && onDownloadAll != null) {
            item {
                ActionRow(
                    title = "DOWNLOAD EVERYTHING MISSING",
                    detail =
                        "${missing.size} files, ${describeSize(missing.sumOf { it.bytes })}. " +
                            "Opens each in your browser in turn.",
                    description =
                        "Download all ${missing.size} missing files, " +
                            describeSize(missing.sumOf { it.bytes }) + ". Opens your browser.",
                    onClick = { onDownloadAll(missing) },
                )
            }
        }

        item {
            Spacer(Modifier.height(4.dp))
            Text("LANGUAGE PACKS", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Muted)
            Text(
                "Each language needs a recogniser to hear speech and a voice to speak it. " +
                    "Tap a file to download it.",
                fontSize = 12.sp,
                color = Muted,
            )
        }

        items(groups, key = { it.code }) { group ->
            LanguagePackCard(
                group = group,
                current = group.code == currentLanguage,
                packs = packs,
                onDelete = onDelete,
                onDownload = onDownload,
                onDownloadAll = onDownloadAll,
            )
        }

        if (shared.isNotEmpty()) {
            item {
                Spacer(Modifier.height(4.dp))
                Text("SHARED BY EVERY LANGUAGE", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Muted)
            }
            items(shared, key = { it.name }) { pack -> InstalledRow(pack, onDelete) }
        }
    }
}

/** One language's files, in the order the operator needs them: hearing before speaking. */
internal data class LanguageGroup(
    val code: String,
    val nativeName: String,
    val englishName: String,
    val files: List<Download>,
) {
    val missing: List<Download> get() = files.filter { !it.installed }
    val hasVoice: Boolean get() = files.any { it.kind == "voice" }
    val canHear: Boolean get() = files.any { it.kind == "recogniser" && it.installed }
    val canSpeak: Boolean get() = files.any { it.kind == "voice" && it.installed }

    /** One line saying what this language can do on this handset right now. */
    fun readiness(): String =
        when {
            canHear && canSpeak -> "Ready: hears and speaks"
            canHear && !hasVoice -> "Ready: hears. No voice exists, so arrivals show as text"
            canHear -> "Hears. Download the voice to speak arrivals aloud"
            canSpeak -> "Speaks. Download the recogniser to hear speech"
            else -> "Nothing installed"
        }
}

/**
 * Groups the index by language, in the fixed language order.
 *
 * Every language is listed even where nothing is downloadable for it, so the count on
 * the screen is always ten and an absent voice is a stated fact on that language's card.
 */
internal fun languageGroups(
    downloads: List<Download>,
    languages: List<LanguageOption>,
): List<LanguageGroup> {
    val byLanguage = downloads.groupBy { it.languageCode }
    val order =
        languages.map { Triple(it.code, it.nativeName, it.englishName) }
            .ifEmpty { byLanguage.keys.map { Triple(it, it, it) } }
    return order.map { (code, native, english) ->
        LanguageGroup(
            code = code,
            nativeName = native,
            englishName = english,
            files = byLanguage[code].orEmpty().sortedBy { if (it.kind == "recogniser") 0 else 1 },
        )
    }
}

@Composable
private fun LanguagePackCard(
    group: LanguageGroup,
    current: Boolean,
    packs: List<PackRow>,
    onDelete: (PackRow) -> Unit,
    onDownload: ((Download) -> Unit)?,
    onDownloadAll: ((List<Download>) -> Unit)?,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(if (current) 2.dp else 1.dp, if (current) Ink else Muted, RoundedCornerShape(6.dp))
            .padding(12.dp)
            .semantics { contentDescription = "${group.englishName}. ${group.readiness()}." },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(group.nativeName, fontSize = 20.sp, color = Ink, modifier = Modifier.weight(1f))
            if (current) {
                Text("CURRENT", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Ink)
            }
        }
        Text(group.englishName + " · " + group.readiness(), fontSize = 12.sp, color = Muted)
        Spacer(Modifier.height(6.dp))

        for (file in group.files) {
            val installed = packs.firstOrNull { it.languageCode == file.languageCode && it.kind == file.kind }
            if (file.installed && installed != null) {
                InstalledRow(installed, onDelete)
            } else {
                DownloadRow(file, onDownload)
            }
            Spacer(Modifier.height(6.dp))
        }

        if (!group.hasVoice) {
            Text(
                "voice · none exists under a permissive licence. Messages arriving in " +
                    "${group.englishName} are shown as text.",
                fontSize = 12.sp,
                color = Danger,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
            Spacer(Modifier.height(6.dp))
        }

        val missing = group.missing
        if (missing.size > 1 && onDownloadAll != null) {
            ActionRow(
                title = "DOWNLOAD BOTH",
                detail = describeSize(missing.sumOf { it.bytes }) + ", one after the other.",
                description = "Download both files for ${group.englishName}. Opens your browser.",
                onClick = { onDownloadAll(missing) },
            )
        }
    }
}

/** A file this handset does not have. The tap hands its address to the browser. */
@Composable
private fun DownloadRow(
    file: Download,
    onDownload: ((Download) -> Unit)?,
) {
    // Tappable, because a row that looks like an item and does nothing when pressed is
    // worse than no row at all. This application has no HTTP client and opens no outbound
    // connection: the fetching is done by a program whose job it is, and the verifying is
    // still done here, by SHA-256.
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_DP.dp)
            .border(1.dp, Ink, RoundedCornerShape(6.dp))
            .then(if (onDownload == null) Modifier else Modifier.clickable { onDownload(file) })
            .padding(10.dp)
            .semantics {
                contentDescription =
                    "Download the " + file.kind + ", " + describeSize(file.bytes) + ". Opens your browser."
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                file.kind + " · " + describeSize(file.bytes),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Ink,
            )
            Text(file.url, fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Muted, maxLines = 2)
        }
        Text("DOWNLOAD ›", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ink)
    }
}

/** A file this handset has, with its licence, and the control to remove it where that frees space. */
@Composable
private fun InstalledRow(
    pack: PackRow,
    onDelete: (PackRow) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_DP.dp)
            .border(1.dp, Muted, RoundedCornerShape(6.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                pack.kind.ifEmpty { pack.name } + " · installed",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Ink,
            )
            Text(
                "%.1f MB · %s".format(pack.bytes / 1_048_576.0, pack.licence),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = if (pack.isRestrictive) Danger else Muted,
            )
        }
        // Shown only where it does something. espeak's data is bundled in the installer and
        // shared by every language: deleting it frees nothing, because the next synthesis
        // expands it again.
        if (pack.deletable) {
            Text(
                "DELETE",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Danger,
                modifier =
                    Modifier
                        .clickable { onDelete(pack) }
                        .semantics { contentDescription = "Delete ${pack.name}, ${describeSize(pack.bytes)}" },
            )
        } else {
            Text(
                "IN APP",
                fontSize = 14.sp,
                color = Muted,
                modifier =
                    Modifier.semantics {
                        contentDescription = "${pack.name} ships inside the app and cannot be deleted"
                    },
            )
        }
    }
}

@Composable
private fun ActionRow(
    title: String,
    detail: String,
    description: String,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_DP.dp)
            .border(2.dp, Ink, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(12.dp)
            .semantics { contentDescription = description },
    ) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text(detail, fontSize = 12.sp, color = Muted)
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

/** Rows are 64 dp, not 96: these are settings, not controls used under pressure. */
private const val ROW_DP = 64
private val Ink = Color(0xFF101010)
private val Paper = Color(0xFFFFFFFF)
private val Muted = Color(0xFF5F5F5F)
private val Danger = Color(0xFFB3261E)
