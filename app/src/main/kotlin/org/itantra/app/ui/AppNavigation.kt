package org.itantra.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.itantra.bench.UtteranceTrace

/**
 * Every screen in the application, and the one control that reaches them.
 *
 * ## What this fixed
 *
 * Six finished screens existed in this package and **not one of them could be opened**.
 * `MessageLogScreen`, `MetricsScreen`, `LanguageScreen`, `StorageScreen`, `AboutScreen` and
 * `ModeAndTransportScreen` were each referenced by nothing outside their own file — written,
 * tested, compiled into the APK, and unreachable. `docs/WIREFRAMES.md` section 19 makes ☰
 * their only door, and ☰ was wired to an empty lambda.
 *
 * ## Rule 8
 *
 * Nothing operational is more than one tap from the operating screen, and no screen is more
 * than two. Transmit, alert and language stay on the operating screen itself; everything
 * else is ☰ then one row. Back always returns here, from the system gesture as well as from
 * the control, because an operator who cannot find the way back stops exploring.
 *
 * ## What is deliberately absent
 *
 * **ADD A UNIT.** [PairingScreen] is written and it displays a pairing code — and pairing is
 * task W6.11, which does not exist. Every unit holds the same fixed development key, and
 * handsets are bonded in Android's own Bluetooth settings. A screen offering a code that
 * pairs nothing would be the most convincing lie in the application, so it stays unreachable
 * until there is a key exchange behind it.
 */
enum class Destination(val title: String) {
    OPERATING("iTantra"),
    MENU("SETTINGS"),
    MESSAGES("MESSAGES"),
    LANGUAGE("LANGUAGE"),
    METRICS("METRICS"),
    MODE("MODE & TRANSPORT"),
    STORAGE("STORAGE"),

    /** What this unit calls itself, as every other unit sees it. */
    UNIT_NAME("DEVICE NAME"),

    /** Who is on the channel, to pick one to walk to. Reached from the operating screen. */
    LOCATE("LOCATE"),

    /** The walk itself: the arrow, the distance, the siren. */
    LOCATE_UNIT("LOCATE"),

    /**
     * Text size. Board 23, and gap **G4** resolved.
     *
     * Purely additive: no existing route changes, and the screen sets nothing. Android owns
     * text scaling; what was missing was somewhere to *see* the interface at 200 % and watch
     * the Indic line box hold. See [TextSizeScreen].
     */
    TEXT_SIZE("TEXT SIZE"),

    LICENCES("LICENCES"),

    /**
     * A licence, in full.
     *
     * Three taps from the operating screen, where rule 8 asks for two. The rule is about
     * screens an operator uses under pressure; the GNU General Public License is a legal
     * document that has to be *carried*, not navigated to quickly, and burying it one level
     * under LICENCES is where a reader will look for it.
     */
    LICENCE_TEXT("LICENCE"),
}

/** Everything the shell needs from the engine, so this file holds no Android and no net. */
data class AppState(
    val operating: OperatingState,
    val traces: List<UtteranceTrace> = emptyList(),
    val languages: List<LanguageOption> = emptyList(),
    val transports: List<TransportOption> = emptyList(),
    val packs: List<PackRow> = emptyList(),
    val licences: List<LicenceRow> = emptyList(),
    /** The sentence GPL-3.0 obliges this build to show. Null when nothing copyleft ships. */
    val distributionNotice: String? = null,
    /** What the last language-pack import did, or is doing. */
    val packStatus: String? = null,
    /** Every file every language can use, with whether this handset has it, for the storage screen. */
    val downloads: List<Download> = emptyList(),
    /**
     * The colophon on the about screen — `build 1.0 · 27.1 MB · Apache-2.0`.
     *
     * Null rather than a placeholder: a build line nobody supplied is not a build line, and
     * board 24 prints nothing where this screen has nothing to say.
     */
    val buildLine: String? = null,
    /**
     * Whether the speech models are loaded and the transmit control means anything.
     *
     * False draws [SplashScreen]. Defaulted true so nothing that constructs an `AppState`
     * for a preview or a test has to know about a loading phase it is not exercising —
     * and so that a build whose loader never reports simply never shows a splash, rather
     * than showing one for ever.
     */
    val ready: Boolean = true,
    /** What is loading, in the language being loaded. Board 01's caption. */
    val loadingLabel: String? = null,
    /** 0..1, or null when the loader cannot say. */
    val loadingProgress: Float? = null,
    /** The application's own text size factor over the system's, 0.85 to 2.0. */
    val textScale: Float = 1f,
    /** The walk in progress, or null. */
    val locate: LocateState? = null,
    /** Every unit heard this run, nearest first, for the locate list. */
    val unitsHeard: List<UnitInfo> = emptyList(),
    /** The name a unit falls back to when the operator clears theirs. */
    val defaultUnitName: String = "",
)

/** What the shell can ask the engine to do. */
data class AppActions(
    val onTransmitChange: (Boolean) -> Unit,
    val onAlert: () -> Unit,
    val onLanguageChosen: (String) -> Unit,
    /** Reads an asset under `licences/`. Null when the file is missing. */
    val readLicence: (String) -> String?,
    /** Speaks a logged message again. The control existed here with nothing behind it. */
    val onReplay: (String) -> Unit,
    /** Opens the folder picker so a pack can be copied onto this handset. */
    val onImportPacks: () -> Unit,
    /** Hands one download address to the browser. */
    val onDownload: (Download) -> Unit,
    /** Hands several download addresses to the browser, one after another. */
    val onDownloadAll: (List<Download>) -> Unit = { downloads -> downloads.forEach(onDownload) },
    /** Removes one installed artefact. The control existed with an empty lambda behind it. */
    val onDeletePack: (PackRow) -> Unit,
    /**
     * Writes the three result files, or reports which condition stopped it. The control
     * existed with an empty lambda behind it too.
     */
    val onExportCsv: () -> Unit,
    /** "PTT" or "Phone". */
    val onModeChange: (String) -> Unit = {},
    /** The operator renamed this unit. */
    val onUnitName: (String) -> Unit = {},
    /** Start walking towards the unit with this node id. */
    val onStartLocating: (Int) -> Unit = {},
    val onStopLocating: () -> Unit = {},
    val onLocateSiren: (Boolean) -> Unit = {},
    /** The text size factor, 0.85 to 2.0. */
    val onTextScale: (Float) -> Unit = {},
)

@Composable
fun ItantraApp(
    state: AppState,
    actions: AppActions,
    modifier: Modifier = Modifier,
) {
    // The application's own text size, on top of the system's, put in force for the whole
    // tree here. Every `sp` below this line is scaled by it, which is why the text size
    // screen can show the operator the real effect of the control they are pressing.
    val base = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(base.density, base.fontScale * state.textScale)) {
        Routed(state, actions, modifier)
    }
}

@Composable
private fun Routed(
    state: AppState,
    actions: AppActions,
    modifier: Modifier = Modifier,
) {
    var where by remember { mutableStateOf(Destination.OPERATING) }
    var licence by remember { mutableStateOf<LicenceRow?>(null) }

    // The system gesture and the control on screen must do the same thing. An operator who
    // swipes back and lands outside the application has left the net.
    BackHandler(enabled = where != Destination.OPERATING) {
        if (where == Destination.LOCATE_UNIT) actions.onStopLocating()
        where = back(where)
    }

    // Risk T-11: the transmit control must never be live over an unloaded recogniser.
    // The splash is that interval made visible, and it is the only screen that outranks
    // the navigation state entirely.
    if (!state.ready) {
        SplashScreen(
            loading = state.loadingLabel,
            progress = state.loadingProgress,
            modifier = modifier,
        )
        return
    }

    if (where == Destination.OPERATING) {
        OperatingScreen(
            state = state.operating,
            onTransmitChange = actions.onTransmitChange,
            onAlert = actions.onAlert,
            onLanguageSelected = actions.onLanguageChosen,
            onMenu = { where = Destination.MENU },
            onReplay = actions.onReplay,
            onModeChange = actions.onModeChange,
            onLocate = { where = Destination.LOCATE },
            modifier = modifier,
        )
        return
    }

    // Three screens draw their own header because their boards do: the control room's title
    // sits over a hero card, and the self-test and text-size headers are part of the sheet
    // they head. Wrapping them in SubScreen's bar would show two back controls.
    if (where in OwnHeader) {
        when (where) {
            Destination.MENU ->
                ControlRoomScreen(
                    state = state,
                    onOpen = { where = it },
                    onBack = { where = back(where) },
                    modifier = modifier,
                )

            Destination.UNIT_NAME ->
                UnitNameScreen(
                    current = state.operating.unitName,
                    defaultName = state.defaultUnitName,
                    onSave = {
                        actions.onUnitName(it)
                        where = back(where)
                    },
                    onBack = { where = back(where) },
                    modifier = modifier,
                )

            Destination.LOCATE ->
                LocateListScreen(
                    units = state.unitsHeard,
                    onSelect = {
                        actions.onStartLocating(it.src)
                        where = Destination.LOCATE_UNIT
                    },
                    onBack = { where = back(where) },
                    modifier = modifier,
                )

            Destination.LOCATE_UNIT -> {
                val walk = state.locate
                if (walk != null) {
                    LocateScreen(
                        state = walk,
                        onStop = {
                            actions.onStopLocating()
                            where = Destination.LOCATE
                        },
                        onSiren = actions.onLocateSiren,
                        modifier = modifier,
                    )
                } else {
                    LocateListScreen(
                        units = state.unitsHeard,
                        onSelect = { actions.onStartLocating(it.src) },
                        onBack = { where = back(where) },
                        modifier = modifier,
                    )
                }
            }

            Destination.TEXT_SIZE ->
                TextSizeScreen(
                    onBack = { where = back(where) },
                    scale = state.textScale,
                    onScale = actions.onTextScale,
                    modifier = modifier,
                )

            else -> Unit
        }
        return
    }

    SubScreen(title = where.title, onBack = { where = back(where) }, modifier = modifier) {
        when (where) {
            Destination.MESSAGES ->
                MessageLogScreen(
                    messages = state.operating.messages,
                    onReplay = { actions.onReplay(it.text) },
                )

            Destination.LANGUAGE ->
                LanguageScreen(
                    languages = state.languages,
                    selected = state.operating.languageCode,
                    onSelect = actions.onLanguageChosen,
                )

            Destination.METRICS ->
                MetricsScreen(
                    traces = state.traces,
                    onExportCsv = actions.onExportCsv,
                    status = state.packStatus,
                )

            Destination.MODE ->
                ModeAndTransportScreen(
                    transports = state.transports,
                    mode = state.operating.mode,
                    onModeChange = actions.onModeChange,
                )

            Destination.STORAGE ->
                StorageScreen(
                    packs = state.packs,
                    onDelete = actions.onDeletePack,
                    onImport = actions.onImportPacks,
                    status = state.packStatus,
                    downloads = state.downloads,
                    languages = state.languages,
                    currentLanguage = state.operating.languageCode,
                    onDownload = actions.onDownload,
                    onDownloadAll = actions.onDownloadAll,
                )

            Destination.LICENCES ->
                AboutScreen(
                    components = state.licences,
                    distributionNotice = state.distributionNotice,
                    buildLine = state.buildLine,
                    onOpenLicence = {
                        licence = it
                        where = Destination.LICENCE_TEXT
                    },
                )

            Destination.LICENCE_TEXT ->
                licence?.let { row ->
                    LicenceTextScreen(
                        title = row.licence,
                        text =
                            row.licenceFile?.let(actions.readLicence)
                                ?: "This licence text is not bundled with this build.",
                    )
                }

            Destination.OPERATING,
            Destination.MENU,
            Destination.UNIT_NAME,
            Destination.LOCATE,
            Destination.LOCATE_UNIT,
            Destination.TEXT_SIZE,
            -> Unit
        }
    }
}

/** Destinations whose board draws its own back header. */
private val OwnHeader =
    setOf(Destination.MENU, Destination.UNIT_NAME, Destination.LOCATE, Destination.LOCATE_UNIT, Destination.TEXT_SIZE)

/** One step towards the operating screen, wherever we are. */
private fun back(from: Destination): Destination =
    when (from) {
        Destination.OPERATING, Destination.MENU, Destination.LOCATE -> Destination.OPERATING
        Destination.LOCATE_UNIT -> Destination.LOCATE
        Destination.LICENCE_TEXT -> Destination.LICENCES
        else -> Destination.MENU
    }

/**
 * A back row and the screen beneath it.
 *
 * A scaffold rather than a back control added to each of the six, because those screens were
 * written as plain columns of content and are the better for it — none of them knows it is
 * on a stack, and none of them had to change to become reachable.
 */
@Composable
private fun SubScreen(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(Tokens.Paper)
            .windowInsetsPadding(WindowInsets.safeDrawing),
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
                    .clickable { onBack() }
                    // The title is spoken here rather than drawn twice. Every one of these
                    // screens already opens with its own heading, and a bar repeating it
                    // showed "LICENCES" above "LICENCES" on a real handset.
                    .semantics { contentDescription = "Back from " + title },
                contentAlignment = Alignment.CenterStart,
            ) {
                Text("‹", fontSize = 34.sp, color = Tokens.Ink)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tokens.Rule))
        content()
    }
}
