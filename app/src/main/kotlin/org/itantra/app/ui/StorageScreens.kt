package org.itantra.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// Storage and pack import. Task W7.21; docs/REDESIGN.md phase 4, boards 20 and 21.
//
// They are one screen because they are one question. "What is on this handset" and "how do
// I put something else on it" were previously answered in the same LazyColumn by two
// unrelated layouts; here the capacity bar is the answer to the first and the numbered
// steps are the answer to the second, in that order, with the installed list between them.

/**
 * Boards 20 and 21.
 *
 * ## The bar is also the legend
 *
 * A capacity meter that says `412 MB used` and nothing else answers the wrong question. The
 * operator deleting something needs to know *which language* is taking the space, so the bar
 * is segmented by language and the legend under it is the same colours with the same figures
 * — one object read two ways, rather than a chart and a table that can disagree.
 *
 * ## Why the import is two numbered steps
 *
 * The application holds no `INTERNET` permission — constraint **C2**, asserted in CI — so it
 * genuinely cannot fetch a model. The browser fetches and iTantra verifies by SHA-256. That
 * is a two-actor flow, and a single "Import" button hid it: an operator pressed it, saw a
 * file picker over an empty Downloads folder, and had no way to learn that the downloading
 * was theirs to do. The steps are numbered, the current one is filled, and the sentence
 * under them says which half of the work belongs to which program.
 *
 * ## What board 20 shows that this cannot
 *
 * `11.2 GB free`. Free space is a filesystem query and nothing in `AppState` carries it;
 * inventing a denominator under a real numerator would make the used figure read as a
 * fraction of a number this screen made up. Used is shown alone until something measures the
 * rest.
 */
@OptIn(ExperimentalLayoutApi::class)
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
    /** The language chosen on the operating screen, so its files are offered first. */
    currentLanguage: String = "",
    onDownload: ((Download) -> Unit)? = null,
    onDownloadAll: ((List<Download>) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val p = palette
    val groups = storageGroups(packs, languages)
    val total = packs.sumOf { it.bytes }
    val wanted =
        downloads
            .filter { !it.installed }
            .sortedBy { if (it.languageCode == currentLanguage) 0 else 1 }

    Column(
        modifier
            .fillMaxSize()
            .background(p.ground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Storage",
            fontSize = Tokens.Title,
            fontWeight = FontWeight.Bold,
            color = p.ink,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )

        CapacityCard(groups, total)

        if (groups.isNotEmpty()) {
            StorageLabel("INSTALLED PACKS")
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(p.paper, RoundedCornerShape(Tokens.RadiusTile))
                    .border(Tokens.Hairline, p.hairline, RoundedCornerShape(Tokens.RadiusTile)),
            ) {
                groups.forEachIndexed { index, group ->
                    if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(p.sunken))
                    InstalledPackRow(group, onDelete)
                }
            }
        }

        if (wanted.isNotEmpty() || onImport != null) {
            ImportSteps(hasFiles = wanted.isNotEmpty())
        }

        if (wanted.isNotEmpty()) {
            StorageLabel("FILES TO DOWNLOAD")
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(p.paper, RoundedCornerShape(Tokens.RadiusTile))
                    .border(Tokens.Hairline, p.hairline, RoundedCornerShape(Tokens.RadiusTile)),
            ) {
                wanted.forEachIndexed { index, file ->
                    if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(p.sunken))
                    DownloadFileRow(file, languages) { onDownload?.invoke(file) }
                }
            }
            onDownloadAll?.let { all ->
                TextAction("Open all ${wanted.size} in the browser") { all(wanted) }
            }
        }

        // The verifier's own line, verbatim. It is the only place SHA-256 is visible, and
        // the import is the one operation where a silent failure means a handset that looks
        // provisioned and cannot speak.
        status?.let { StatusCard(it) }

        onImport?.let { pick ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Tokens.SecondaryAction)
                    .background(p.periwinkle.core, RoundedCornerShape(Tokens.RadiusCard))
                    .clickable(onClick = pick)
                    .padding(horizontal = 16.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Select files from Downloads and verify them"
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp, Alignment.CenterHorizontally),
            ) {
                Icon(Icons.Plus, contentDescription = null, tint = p.onAccent, modifier = Modifier.size(22.dp))
                Text(
                    "Select files",
                    fontSize = Tokens.Body,
                    fontWeight = FontWeight.SemiBold,
                    color = p.onAccent,
                )
            }
        }

        if (groups.isEmpty()) {
            EmptyState(
                icon = Icons.Storage,
                title = "No language packs",
                body =
                    "Transmit sends a template, and nothing is spoken aloud until one is " +
                        "installed.",
                family = p.orchid,
                actionLabel = onImport?.let { "Install a language pack" },
                onAction = onImport,
            )
        }
    }
}

/** One language's installed footprint, from the rows on disk. */
internal data class StorageGroup(
    val code: String,
    val nativeName: String,
    val englishName: String,
    val bytes: Long,
    /** `ASR 34 · voice 26 · rules 1`, built from the kinds actually present. */
    val breakdown: String,
    /** `IndicConformer · Piper · permissive`, or the restrictive statement. */
    val provenance: String,
    val restrictive: Boolean,
    val rows: List<PackRow>,
)

/**
 * Groups what is on disk by language.
 *
 * Grouped on `languageCode` rather than by parsing `name`, because `name` is "hi · voice",
 * built for reading — the same reason `PackRow` keeps `kind` separately from it.
 */
internal fun storageGroups(
    packs: List<PackRow>,
    languages: List<LanguageOption>,
): List<StorageGroup> {
    val names = languages.associateBy { it.code }
    return packs
        .groupBy { it.languageCode }
        .map { (code, rows) ->
            val option = names[code]
            val kinds =
                rows
                    .filter { it.kind.isNotBlank() }
                    .groupBy { it.kind }
                    .map { (kind, of) -> "$kind ${of.sumOf { it.bytes } / 1_000_000}" }
            StorageGroup(
                code = code,
                nativeName = option?.nativeName ?: code.ifBlank { "shared" },
                englishName = option?.englishName.orEmpty(),
                bytes = rows.sumOf { it.bytes },
                breakdown = kinds.joinToString(" · "),
                provenance = rows.map { it.licence }.distinct().joinToString(" · "),
                restrictive = rows.any { it.isRestrictive },
                rows = rows,
            )
        }
        .sortedByDescending { it.bytes }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CapacityCard(
    groups: List<StorageGroup>,
    total: Long,
) {
    val p = palette
    Column(
        Modifier
            .fillMaxWidth()
            .background(p.paper, RoundedCornerShape(Tokens.RadiusTile))
            .border(Tokens.Hairline, p.hairline, RoundedCornerShape(Tokens.RadiusTile))
            .padding(16.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "${total / 1_000_000} megabytes used by language packs"
            },
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "${total / 1_000_000}",
                fontSize = Tokens.Display,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = p.ink,
            )
            Text(
                "MB used",
                fontSize = Tokens.Status,
                color = p.muted,
                modifier = Modifier.padding(bottom = 5.dp),
            )
        }

        // The bar and the legend are the same list read twice, so they cannot disagree.
        Row(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
                .background(p.sunken, RoundedCornerShape(Tokens.RadiusPill)),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            groups.forEachIndexed { index, group ->
                val share = if (total > 0) group.bytes.toFloat() / total else 0f
                Box(
                    Modifier
                        .fillMaxWidth(share)
                        .height(14.dp)
                        .background(swatch(index, p)),
                )
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            groups.forEachIndexed { index, group ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(swatch(index, p), RoundedCornerShape(3.dp)),
                    )
                    Text(
                        "${group.nativeName} ${group.bytes / 1_000_000}",
                        fontSize = Tokens.Instrument,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        lineHeight = Tokens.Instrument * Tokens.INDIC_LINE_HEIGHT,
                        color = p.muted,
                    )
                }
            }
        }
    }
}

/**
 * The segment colour for a language.
 *
 * Nine families cycled by position. This is the one place in the application where colour is
 * an index rather than a meaning, and it is legitimate because the legend carries the name
 * beside every swatch — the colour is a pointer into a key on the same card, not a claim.
 */
@Composable
private fun swatch(
    index: Int,
    p: ItantraPalette,
): Color {
    val wheel = listOf(p.orchid, p.aqua, p.fuchsia, p.mint, p.sky, p.butter, p.apricot, p.periwinkle, p.blush)
    return wheel[index % wheel.size].core
}

@Composable
private fun InstalledPackRow(
    group: StorageGroup,
    onDelete: (PackRow) -> Unit,
) {
    val p = palette
    val deletable = group.rows.filter { it.deletable }
    Row(
        Modifier.fillMaxWidth().padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    group.nativeName,
                    fontSize = Tokens.Callout,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = Tokens.Callout * Tokens.INDIC_LINE_HEIGHT,
                    color = p.ink,
                )
                if (group.englishName.isNotBlank()) {
                    Text(group.englishName, fontSize = Tokens.Status, color = p.muted)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    describeSize(group.bytes),
                    fontSize = Tokens.Status,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = p.ink,
                )
            }
            if (group.breakdown.isNotBlank()) {
                Text(
                    group.breakdown,
                    fontSize = Tokens.Instrument,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = p.muted,
                )
            }
            // A restrictive licence is stated on the row, with a dot as well as the words,
            // because this is where the decision to keep or delete it is actually made.
            if (group.restrictive) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Box(Modifier.size(6.dp).background(p.blush.core, CircleShape))
                    Text(
                        group.provenance,
                        fontSize = Tokens.Label,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = Tokens.Label * 1.35f,
                        color = p.blush.deep,
                    )
                }
            } else if (group.provenance.isNotBlank()) {
                Text(
                    group.provenance,
                    fontSize = Tokens.Label,
                    lineHeight = Tokens.Label * 1.35f,
                    color = p.muted,
                )
            }
        }
        if (deletable.isNotEmpty()) {
            Box(
                Modifier
                    .sizeIn(minWidth = Tokens.TouchTarget, minHeight = Tokens.TouchTarget)
                    .clickable { deletable.forEach(onDelete) }
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Delete ${group.nativeName}, ${describeSize(group.bytes)}"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Bin,
                    contentDescription = null,
                    tint = if (group.restrictive) p.blush.core else p.hairlineStrong,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** Board 21's two steps. The filled one is the one the operator has to do next. */
@Composable
private fun ImportSteps(hasFiles: Boolean) {
    val p = palette
    Column(
        Modifier
            .fillMaxWidth()
            .background(p.paper, RoundedCornerShape(Tokens.RadiusTile))
            .border(Tokens.Hairline, p.hairline, RoundedCornerShape(Tokens.RadiusTile))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        StepLine(1, "Download the files below in your browser.", active = hasFiles)
        StepLine(2, "Tap Select files, open Downloads, and select them all.", active = !hasFiles)
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.sunken))
        Text(
            "This app holds no INTERNET permission. The browser fetches; iTantra verifies by SHA-256.",
            fontSize = Tokens.Instrument,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            lineHeight = Tokens.Instrument * 1.5f,
            color = p.muted,
        )
    }
}

@Composable
private fun StepLine(
    number: Int,
    text: String,
    active: Boolean,
) {
    val p = palette
    Row(horizontalArrangement = Arrangement.spacedBy(13.dp)) {
        Box(
            Modifier
                .size(26.dp)
                .background(if (active) p.ink else p.sunken, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$number",
                fontSize = Tokens.Label,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = if (active) p.onAccent else p.muted,
            )
        }
        Text(
            text,
            fontSize = Tokens.Body,
            lineHeight = Tokens.Body * 1.4f,
            color = if (active) p.ink else p.muted,
        )
    }
}

/** One file the browser has to fetch, with the address it will be fetched from. */
@Composable
private fun DownloadFileRow(
    file: Download,
    languages: List<LanguageOption>,
    onOpen: () -> Unit,
) {
    val p = palette
    val language = languages.firstOrNull { it.code == file.languageCode }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Tokens.SecondaryAction)
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {
                contentDescription =
                    "${language?.englishName.orEmpty()} ${file.kind}, ${describeSize(file.bytes)}. " +
                    "Opens in the browser."
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Icon(Icons.Download, contentDescription = null, tint = p.sky.core, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                listOfNotNull(language?.englishName?.takeIf { it.isNotBlank() }, file.kind)
                    .joinToString(" "),
                fontSize = Tokens.BodySmall,
                fontWeight = FontWeight.SemiBold,
                lineHeight = Tokens.BodySmall * 1.3f,
                color = p.ink,
            )
            Text(
                file.url.removePrefix("https://").removePrefix("http://"),
                fontSize = Tokens.Instrument,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = p.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            describeSize(file.bytes),
            fontSize = Tokens.Label,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = p.ink,
        )
    }
}

/** What the importer is doing, in its own words. */
@Composable
private fun StatusCard(status: String) {
    val p = palette
    Row(
        Modifier
            .fillMaxWidth()
            .background(p.orchid.tint, RoundedCornerShape(Tokens.RadiusTile))
            .border(Tokens.Hairline, p.orchid.mid, RoundedCornerShape(Tokens.RadiusTile))
            .padding(16.dp)
            .semantics(mergeDescendants = true) { contentDescription = status },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Hourglass, contentDescription = null, tint = p.orchid.core, modifier = Modifier.size(20.dp))
        Text(
            status,
            fontSize = Tokens.Instrument,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            lineHeight = Tokens.Instrument * 1.5f,
            color = p.orchid.deep,
        )
    }
}

@Composable
private fun TextAction(
    label: String,
    onClick: () -> Unit,
) {
    val p = palette
    Text(
        label,
        fontSize = Tokens.Label,
        fontWeight = FontWeight.SemiBold,
        color = p.sky.deep,
        modifier =
            Modifier
                .heightIn(min = Tokens.TouchTarget)
                .clickable(onClick = onClick)
                .padding(horizontal = 4.dp, vertical = 20.dp)
                .semantics(mergeDescendants = true) { contentDescription = label },
    )
}

@Composable
private fun StorageLabel(text: String) {
    val p = palette
    Text(
        text,
        fontSize = Tokens.Instrument,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        color = p.muted,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}
