package org.itantra.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
    LICENCES("LICENCES"),
}

/** Everything the shell needs from the engine, so this file holds no Android and no net. */
data class AppState(
    val operating: OperatingState,
    val traces: List<UtteranceTrace> = emptyList(),
    val languages: List<LanguageOption> = emptyList(),
    val transports: List<TransportOption> = emptyList(),
    val packs: List<PackRow> = emptyList(),
    val licences: List<LicenceRow> = emptyList(),
)

/** What the shell can ask the engine to do. */
data class AppActions(
    val onTransmitChange: (Boolean) -> Unit,
    val onAlert: () -> Unit,
    val onPosition: () -> Unit,
    val onLanguageCycle: () -> Unit,
    val onLanguageChosen: (String) -> Unit,
)

@Composable
fun ItantraApp(
    state: AppState,
    actions: AppActions,
    modifier: Modifier = Modifier,
) {
    var where by remember { mutableStateOf(Destination.OPERATING) }

    // The system gesture and the control on screen must do the same thing. An operator who
    // swipes back and lands outside the application has left the net.
    BackHandler(enabled = where != Destination.OPERATING) {
        where = if (where == Destination.MENU) Destination.OPERATING else Destination.MENU
    }

    if (where == Destination.OPERATING) {
        OperatingScreen(
            state = state.operating,
            onTransmitChange = actions.onTransmitChange,
            onAlert = actions.onAlert,
            onPosition = actions.onPosition,
            onLanguage = actions.onLanguageCycle,
            onMenu = { where = Destination.MENU },
            modifier = modifier,
        )
        return
    }

    SubScreen(
        title = where.title,
        onBack = {
            where = if (where == Destination.MENU) Destination.OPERATING else Destination.MENU
        },
        modifier = modifier,
    ) {
        when (where) {
            Destination.MENU -> MenuScreen(onOpen = { where = it })

            Destination.MESSAGES ->
                MessageLogScreen(messages = state.operating.messages, onReplay = { })

            Destination.LANGUAGE ->
                LanguageScreen(
                    languages = state.languages,
                    selected = selectedLanguage(state),
                    onSelect = actions.onLanguageChosen,
                )

            Destination.METRICS -> MetricsScreen(traces = state.traces, onExportCsv = { })

            Destination.MODE ->
                ModeAndTransportScreen(
                    pushToTalk = true,
                    onModeChange = { },
                    transport = state.operating.transportName,
                    transports = state.transports,
                    onTransportChange = { },
                )

            Destination.STORAGE -> StorageScreen(packs = state.packs, onDelete = { })

            Destination.LICENCES -> AboutScreen(components = state.licences)

            Destination.OPERATING -> Unit
        }
    }
}

/**
 * Band B carries the language in its own script, which is what the operator recognises; the
 * language screen selects on the code. Matching one to the other here keeps
 * [OperatingState] free of a second representation of the same fact.
 */
private fun selectedLanguage(state: AppState): String =
    state.languages.firstOrNull { it.nativeName == state.operating.language }?.code
        ?: state.languages.firstOrNull()?.code.orEmpty()

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

/** The list behind ☰. One row per screen, and nothing that is not a screen. */
@Composable
private fun MenuScreen(onOpen: (Destination) -> Unit) {
    val rows =
        listOf(
            Destination.MESSAGES to "Everything sent and received, last 24 hours",
            Destination.LANGUAGE to "What this unit speaks and reads",
            Destination.METRICS to "Measured latency, from real utterances",
            Destination.MODE to "Push-to-talk, and the radio in use",
            Destination.STORAGE to "Language packs on this handset",
            Destination.LICENCES to "What this application is built from",
        )
    LazyColumn(Modifier.fillMaxSize().padding(Tokens.ScreenMargin)) {
        item {
            // The heading the other six screens each carry for themselves.
            Text("SETTINGS", fontSize = Tokens.Title, fontWeight = FontWeight.Bold, color = Tokens.Ink)
            Spacer(Modifier.height(Tokens.Grid))
        }
        items(rows) { (destination, blurb) ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Tokens.TouchTarget)
                    .clickable { onOpen(destination) }
                    .semantics { contentDescription = destination.title + ". " + blurb }
                    .padding(vertical = Tokens.Grid),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(destination.title, fontSize = Tokens.Body, fontWeight = FontWeight.Bold, color = Tokens.Ink)
                Text(blurb, fontSize = Tokens.Status, color = Tokens.Muted)
            }
            Spacer(Modifier.height(1.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tokens.Rule))
        }
    }
}
