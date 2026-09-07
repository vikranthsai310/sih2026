package org.itantra.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.itantra.app.engine.MessageEngine
import org.itantra.app.platform.DataStoreEpochStore
import org.itantra.app.platform.EspeakData
import org.itantra.app.platform.Heading
import org.itantra.app.platform.InstallIndex
import org.itantra.app.platform.ModelStore
import org.itantra.app.platform.NodeIdentity
import org.itantra.app.platform.PackInstaller
import org.itantra.app.platform.PiperVoiceMetadata
import org.itantra.app.platform.PositionSource
import org.itantra.app.platform.PushToTalkKey
import org.itantra.app.platform.ReportExport
import org.itantra.app.platform.SherpaSpeech
import org.itantra.app.platform.SmallArtefacts
import org.itantra.app.platform.Speaker
import org.itantra.app.platform.UnitPreferences
import org.itantra.app.ui.AppActions
import org.itantra.app.ui.AppState
import org.itantra.app.ui.Download
import org.itantra.app.ui.ItantraApp
import org.itantra.app.ui.ItantraTheme
import org.itantra.app.ui.LicenceRow
import org.itantra.app.ui.LocalReducedMotion
import org.itantra.app.ui.PackRow
import org.itantra.app.ui.TransportOption
import org.itantra.app.ui.describeSize
import org.itantra.app.ui.rememberReducedMotion
import org.itantra.asr.BiasingLexicon
import org.itantra.audio.EngineState
import org.itantra.link.LinkState
import org.itantra.proto.Language
import org.itantra.proto.TemplateProfile
import java.io.File

/**
 * The operating screen, hosted, over a real Bluetooth net. Tasks **W1.11**, **W3.12**.
 *
 * ## What this class does, and deliberately no more
 *
 * Holds the window, asks for the permissions, routes the hardware key, and hands a state
 * flow to a composable. [MessageEngine] owns the net; the screen owns the pixels; this owns
 * neither. The week-2 bring-up screen that used to live here — a text field and two
 * Bluetooth buttons — was deleted in W3.12 and is not coming back.
 *
 * ## Why the engine is Compose state
 *
 * It is created *after* the permission answer, which arrives long after the first
 * composition. Held in a plain field it was invisible to Compose: the screen composed once
 * against `null`, fell back to [startingState], and stayed there for the life of the
 * process — showing `node 00`, `0 units` and `NO LINK` on a handset whose net was up. Every
 * control was live and every one of them was talking to an engine the screen could not see.
 *
 * `mutableStateOf` is the whole fix, and the bug is worth naming because nothing about the
 * symptom points at it.
 *
 * ## What works on two or more handsets today
 *
 * Install on every unit, bond them in Android's Bluetooth settings, and open the app. Each
 * one listens for connections and dials the units it is paired with, and the transmit
 * control sends a template code over the real path: matched, sealed with the transport's tag
 * length, framed, transmitted, verified, replay-checked and rendered in the **receiver's**
 * language.
 *
 * What is absent is speech at either end, because there are no model files. Band F's frame
 * size is real; its latency figures stay as dashes until there is a recogniser to measure.
 */
class MainActivity : ComponentActivity() {
    /** Compose state, not a field. See the class comment — this was a real defect. */
    private var engine by mutableStateOf<MessageEngine?>(null)

    /** Set once the operator has said no, since Android will not ask a second time. */
    private var permissionRefused by mutableStateOf(false)

    /** What the language-pack copy is doing, for the storage screen. */
    private var packStatus by mutableStateOf<String?>(null)

    /** Bumped whenever the disk may have changed behind the screen's back, so it looks again. */
    private var diskVersion by mutableStateOf(0)

    /**
     * When the engine started, for the soak duration the report conditions require.
     *
     * `elapsedRealtime` rather than `currentTimeMillis`: the wall clock can be moved by the
     * network or by hand mid-run, and a soak that appears to last minus four minutes is not
     * a soak that can be reported.
     */
    private var engineStartedAt = SystemClock.elapsedRealtime()

    /**
     * The file picker, and the whole answer to "no language installed" on a handset that
     * has never met a developer's machine.
     *
     * **Files, not a folder.** `ACTION_OPEN_DOCUMENT_TREE` cannot be granted over
     * `Download` on Android 11 and later — the picker lists the folder, lists the files
     * inside it, and then refuses with "Can't use this folder" — and `Download` is exactly
     * where a browser puts things. Picking the files themselves carries no such
     * restriction and is one step shorter besides.
     *
     * Nothing is declared in the manifest for this: the system grants access to precisely
     * the files chosen, for this copy. The packs can reach the handset any way at all.
     */
    private val choosePackFiles =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { files ->
            if (files.isEmpty()) {
                // Cancelled. A control that sometimes does nothing and says nothing is one
                // nobody presses twice.
                packStatus = "No files chosen."
                return@registerForActivityResult
            }
            packStatus = "Checking " + files.size + " file(s)…"
            lifecycleScope.launch {
                val result =
                    PackInstaller(applicationContext).installFiles(files) { packStatus = it }
                packStatus = result.describe()
                // A newly installed pack is only found on the next look, and the engine
                // caches what it loaded. Re-asking costs nothing and saves a restart.
                engine?.restartIfIdle()
                engine?.onLanguageChosen(currentLanguageCode())
            }
        }

    /** The operator's name for this unit, its mode and its text size, kept across restarts. */
    private val preferences by lazy { UnitPreferences(applicationContext, unitName()) }

    private val identity by lazy { NodeIdentity.of(installationId(), preferences.unitName) }

    /** Position and compass, for finding a unit. Both idle until somebody is looking. */
    private val positions by lazy { PositionSource(applicationContext) }
    private val heading by lazy { Heading(applicationContext) }

    /** The text size factor, mirrored into Compose state so a change redraws at once. */
    private var textScale by mutableStateOf(1f)

    private val transmitKey =
        PushToTalkKey(
            onPress = { engine?.onTransmit(true) },
            onRelease = { engine?.onTransmit(false) },
        )

    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Bluetooth cannot be enumerated until it is granted, so the net is built after
            // the answer rather than before the question.
            permissionRefused = !startEngine()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Token tables and voice configs, out of the installer and onto the disk. Free
        // after the first run, and it means a language pack is one or two downloads
        // rather than four, two of which a browser refuses to save.
        SmallArtefacts(applicationContext).ensure()

        // espeak's data, out of the installer and onto the disk, before anything asks
        // whether a language can be spoken. It used to be expanded lazily inside the voice
        // loader, behind a check that required it to already be there.
        EspeakData(applicationContext).ensure()

        // A voice installed straight from Piper's repository before the importer learned
        // to stamp it has been sitting here unusable, shown as "download" ever since. Off
        // the main thread: a stamp is a sixty-megabyte copy, and there may be six.
        lifecycleScope.launch(Dispatchers.IO) {
            val voices = File(applicationContext.getExternalFilesDir(null), "models/tts")
            val repaired = PiperVoiceMetadata.ensureAll(voices)
            if (repaired > 0) {
                withContext(Dispatchers.Main) {
                    packStatus = "$repaired voice${if (repaired == 1) "" else "s"} made usable."
                    diskVersion++
                    engine?.onLanguageChosen(currentLanguageCode())
                }
            }
        }

        textScale = preferences.textScale

        if (!startEngine()) permissions.launch(requiredPermissions())

        setContent {
            val running = engine
            // What is on disk changes only when an import or a delete says so, and both
            // rewrite the status line. Read once per change rather than on every
            // recomposition: the level meter recomposes this fifty times a second while
            // the operator speaks, and ten languages' worth of stat calls each time is
            // not what the main thread is for.
            val onDisk = remember(packStatus, diskVersion) { installedPacks() to allDownloads() }
            // The palette and the reduced-motion answer are put in force here, once, for
            // the whole tree. Until this existed both composition locals fell back to their
            // static defaults everywhere -- which meant Spectrum was correct by accident and
            // `reducedMotion` was permanently false, so every "and it stops when asked"
            // branch in Motion.kt was unreachable code that could never have been shown to
            // work. Field Mode has no control yet; when it gets one it sets `fieldMode`
            // here and nothing else in the application changes.
            CompositionLocalProvider(LocalReducedMotion provides rememberReducedMotion()) {
                ItantraTheme(fieldMode = false) {
                    ItantraApp(
                        state =
                            AppState(
                                operating = running?.state?.collectAsState()?.value ?: startingState(),
                                traces = running?.traces?.collectAsState()?.value.orEmpty(),
                                languages = running?.languageOptions().orEmpty().ifEmpty { languageNames() },
                                transports = transports(),
                                packs = onDisk.first,
                                licences = licences(),
                                distributionNotice = DISTRIBUTION_NOTICE,
                                packStatus = packStatus,
                                downloads = onDisk.second,
                                textScale = textScale,
                                locate = running?.locate?.collectAsState()?.value,
                                unitsHeard = running?.unitsEverHeard().orEmpty(),
                                defaultUnitName = unitName(),
                            ),
                        actions =
                            AppActions(
                                onTransmitChange = { running?.onTransmit(it) },
                                onAlert = { running?.onAlert() },
                                onLanguageChosen = { running?.onLanguageChosen(it) },
                                onReplay = { running?.onReplay(it) },
                                onImportPacks = ::pickPackFolder,
                                onDownload = ::openInBrowser,
                                onDownloadAll = ::openAllInBrowser,
                                onDeletePack = ::deletePack,
                                onExportCsv = ::exportReport,
                                readLicence = ::readLicence,
                                onModeChange = { running?.setMode(it) ?: preferences.setMode(it) },
                                onUnitName = { running?.setUnitName(it) ?: preferences.setUnitName(it) },
                                onStartLocating = { running?.startLocating(it) },
                                onStopLocating = { running?.stopLocating() },
                                onLocateSiren = { running?.setLocateSiren(it) },
                                onTextScale = {
                                    preferences.setTextScale(it)
                                    textScale = preferences.textScale
                                },
                            ),
                    )
                }
            }
        }
    }

    /**
     * The radios, and the truth about which of them works.
     *
     * `core-link` holds a `BleLink` as well, and it is not wired to anything — listing it as
     * a choice would offer an operator a switch that silently does nothing. Only what runs
     * appears here; `docs/TRANSPORT.md` keeps the roadmap. (`WifiLink`, a TCP design that
     * was never wired either, was deleted on 2026-09-06: it was the one class in the
     * codebase that opened an outbound network connection, and constraint C2 is easier to state
     * without it. `WifiBroadcastLink` is the Wi-Fi transport that ships.)
     */
    private fun transports(): List<TransportOption> {
        val running = engine ?: return emptyList()
        return running.channels().map { channel ->
            TransportOption(
                id = channel.id,
                name = channel.name,
                status =
                    when (channel.state) {
                        LinkState.CONNECTED -> "Carrying traffic"
                        LinkState.DISCOVERING -> "Looking for units"
                        LinkState.DEGRADED -> "Up, reaching nobody"
                        LinkState.ERROR -> "Failed. Not retrying"
                        LinkState.IDLE -> "Not started"
                        null -> "Not running on this handset"
                    },
                detail = channel.detail,
                carrying = channel.state == LinkState.CONNECTED,
            )
        }
    }

    /**
     * Language packs on this handset, read from disk.
     *
     * This returned an empty list unconditionally, so a handset carrying 2.2 GB of models
     * reported "0.0 MB used by language packs" — the one screen whose job is to say what is
     * taking up space was the one place that never looked.
     */
    private fun installedPacks(): List<PackRow> =
        ModelStore(applicationContext).installedPacks().map {
            PackRow(
                name = "${it.languageCode} · ${it.kind}",
                bytes = it.bytes,
                languageCode = it.languageCode,
                kind = it.kind,
                // espeak's data is bundled in the installer, shared by every language, and
                // re-expanded on the next synthesis, so a delete control over it would be
                // a button that frees nothing.
                deletable = it.kind == "recogniser" || it.kind == "voice",
                // Apache-2.0 for the IndicConformer models, GPL-3.0 for espeak's data --
                // the row a jury looks at is the last one -- and the voices are not all one
                // licence: six are Piper under MIT, Gujarati is Mimic 3 under the CMU
                // Festvox terms. Saying "MIT" for all seven was wrong on the one row where
                // being wrong about a licence matters.
                licence =
                    when {
                        it.kind == "voice" && it.languageCode == "gu" -> "CMU/Festvox"
                        it.kind == "voice" -> "MIT"
                        it.kind == "espeak data" -> "GPL-3.0"
                        else -> "Apache-2.0"
                    },
            )
        }

    /**
     * Every file every language can use, with whether this handset already has it.
     *
     * All ten languages, not the current one: this used to list only what the chosen
     * language lacked, so an operator provisioning a handset for a mixed net had to change
     * language and come back for each one. The application cannot follow these addresses
     * itself — it has no HTTP client, per constraint C2 — so they are for the operator's
     * browser, and "installed" is judged the way the engine judges it: a recogniser is
     * installed when [ModelStore.hasPack] says so, a voice when [ModelStore.hasVoice] does,
     * so a truncated copy shows as still needed rather than as done.
     */
    private fun allDownloads(): List<Download> {
        val store = ModelStore(applicationContext)
        val index = InstallIndex(applicationContext)
        val models = File(applicationContext.getExternalFilesDir(null), "models")
        return Language.entries.flatMap { language ->
            index.downloadableFor(language.code).map { item ->
                Download(
                    kind = item.kind,
                    bytes = item.bytes,
                    url = item.url,
                    languageCode = language.code,
                    installed =
                        when (item.kind) {
                            "recogniser" -> store.hasPack(language.code)
                            "voice" -> store.hasVoice(language.code)
                            else -> File(models, item.install).isFile
                        },
                )
            }
        }
    }

    /** The ten languages by name, for the storage screen before the engine is running. */
    private fun languageNames() = MessageEngine.languageOptions(speech = null)

    private fun currentLanguageCode(): String = engine?.state?.value?.languageCode.orEmpty().ifEmpty { "hi" }

    /**
     * What is inside the installer, and what was looked at and left out.
     *
     * The two are separated because the screen previously showed them in one list, so
     * `espeak-ng GPL-3.0` and `Meta MMS CC-BY-NC` sat side by side in red and read as two
     * restrictive dependencies this application ships. One of them it does ship — see
     * [DISTRIBUTION_NOTICE] — and the other it does not use at all.
     */
    private fun licences() =
        listOf(
            LicenceRow(
                "k2-fsa / sherpa-onnx",
                "Apache-2.0",
                "Recognition and synthesis library. Its acoustic models are fetched at setup",
                licenceFile = "Apache-2.0.txt",
            ),
            LicenceRow(
                "espeak-ng, inside libsherpa-onnx-jni.so",
                "GPL-3.0",
                "Phonemisation, statically linked into the sherpa-onnx native library. " +
                    "Copyleft, and the reason for the notice above",
                licenceFile = "GPL-3.0.txt",
            ),
            LicenceRow("microsoft / onnxruntime", "MIT", "Inference engine beneath sherpa-onnx"),
            LicenceRow(
                "AI4Bharat IndicConformer",
                "Apache-2.0",
                "The recogniser's acoustic models, all ten languages",
                licenceFile = "Apache-2.0.txt",
            ),
            LicenceRow("rhasspy / piper", "MIT", "The voices for six of the ten languages"),
            LicenceRow(
                "Mimic 3 / CMU Indic",
                "CMU/Festvox",
                "The Gujarati voice, which Piper does not have. Permissive: use, copy and " +
                    "modify granted without fee",
            ),
            LicenceRow(
                "AndroidX, Jetpack Compose",
                "Apache-2.0",
                "Interface toolkit",
                licenceFile = "Apache-2.0.txt",
            ),
            LicenceRow(
                "JetBrains kotlinx",
                "Apache-2.0",
                "Coroutines and serialisation",
                licenceFile = "Apache-2.0.txt",
            ),
            LicenceRow(
                "zxing / core",
                "Apache-2.0",
                "QR encoding, for pairing (W6.11)",
                licenceFile = "Apache-2.0.txt",
            ),
            LicenceRow(
                "Meta MMS",
                "CC-BY-NC",
                "Considered for Tamil, Kannada and Odia, where no permissive voice " +
                    "exists. Not used: those three ship recognise-only instead, so nothing " +
                    "non-commercial is in this build",
                shipped = false,
            ),
        )

    /**
     * The alert lexicon for a language, from the assets the build copies out of `models/`.
     *
     * Null rather than empty when a file is missing: an empty lexicon and an absent one look
     * identical to a corrector, and only one of them is a packaging fault worth noticing.
     */
    private fun lexiconFor(code: String): BiasingLexicon? {
        val domain = readAsset("lexicon/alert-lexicon.$code.txt") ?: return null
        // Negation is optional only in the sense that a missing file must not stop the
        // domain terms loading; every language in this repository ships one.
        val negation = readAsset("lexicon/negation.$code.txt").orEmpty()
        return BiasingLexicon.of(domain, negation)
    }

    private fun readAsset(path: String): String? =
        runCatching { assets.open(path).bufferedReader().use { it.readText() } }.getOrNull()

    /** Verbatim, from `assets/licences/`. */
    private fun readLicence(file: String): String? =
        runCatching { assets.open("licences/$file").bufferedReader().use { it.readText() } }.getOrNull()

    /**
     * Retries the two things an operator most often leaves and comes back from: switching
     * Bluetooth on, and pairing the other handset. Both are fixed in Settings, and returning
     * here is the only signal that they might have been.
     */
    override fun onResume() {
        super.onResume()
        val running = engine
        if (running == null) startEngine() else running.restartIfIdle()
        // Coming back from the browser, or from a cable: whatever is on disk now is what
        // the storage screen should say.
        diskVersion++
    }

    /** @return false when a permission is still needed, so the caller can ask for it. */
    private fun startEngine(): Boolean {
        if (engine != null) return true
        if (!hasBluetoothPermission()) return false

        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

        engine =
            MessageEngine(
                scope = lifecycleScope,
                adapter = adapter,
                identity = identity,
                epochStore = DataStoreEpochStore(applicationContext),
                templates = deploymentProfile(),
                bondedDevices = { bonded(adapter) },
                speech = SherpaSpeech(ModelStore(applicationContext)),
                lexicons = ::lexiconFor,
                wifiContext = applicationContext,
                speaker = Speaker(ModelStore(applicationContext), applicationContext),
                preferences = preferences,
                positions = positions,
                heading = heading,
            ).also { it.start() }
        // The soak clock starts with the workload, not with the process.
        engineStartedAt = SystemClock.elapsedRealtime()
        return true
    }

    /**
     * Writes the three result files, or says which condition stopped it.
     *
     * The soak is measured from when the engine started rather than from process start:
     * `docs/EVALUATION.md` section 1 is asking how long the system has been *running the
     * workload*, and an application sitting on the settings screen for half an hour has
     * soaked nothing.
     *
     * Most presses will refuse, and that is the control working. See [ReportExport].
     */
    private fun exportReport() {
        val running = engine
        if (running == null) {
            packStatus = "The engine is not running, so there is nothing to report."
            return
        }
        val soakMinutes =
            ((SystemClock.elapsedRealtime() - engineStartedAt) / 60_000L).toInt()
        packStatus =
            ReportExport(applicationContext)
                .export(traces = running.traces.value, soakMinutes = soakMinutes)
    }

    /**
     * Removes one installed artefact, and says what happened.
     *
     * No confirmation dialog, deliberately: a modal is the one thing that cannot be
     * dismissed by an operator wearing gloves in the dark, and the cost of a mistake here
     * is a re-download rather than a lost message. The status line names what went and how
     * much it freed, which is the acknowledgement the press needs.
     *
     * The list is read from disk again afterwards rather than edited in memory, because
     * the disk is what the screen claims to be showing.
     */
    private fun deletePack(pack: PackRow) {
        if (!pack.deletable) return
        val store = ModelStore(applicationContext)
        val freed = pack.bytes
        packStatus =
            if (store.delete(pack.languageCode, pack.kind)) {
                "Deleted ${pack.name}. ${describeSize(freed)} freed."
            } else {
                "Could not delete ${pack.name}. It may already be gone."
            }
    }

    /**
     * Hands one address to whatever browser this handset has.
     *
     * Not a network call: this application contains no HTTP client and opens no outbound
     * connection. It passes a URL to another program, which fetches under its own
     * permissions and its own user's instruction — which is what constraint C2 means by a
     * pack being "fetched once during setup". The verifying is still done here, by SHA-256.
     */
    private fun openInBrowser(download: Download) {
        packStatus = "Opening your browser…"
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(download.url)))
            packStatus = "Downloading in your browser. Come back and tap install."
        }.onFailure { packStatus = "No browser on this handset to open that with." }
    }

    /**
     * Hands several addresses to the browser, one after another.
     *
     * Spaced out rather than fired in a burst: each one is a separate intent the browser
     * has to open a tab for, and a burst of seventeen arrives as one ignored. The whole run
     * fits inside the window Android allows an application that has just left the
     * foreground to keep starting activities, which is why the spacing is short.
     */
    private fun openAllInBrowser(downloads: List<Download>) {
        if (downloads.isEmpty()) return
        packStatus = "Opening ${downloads.size} downloads in your browser…"
        lifecycleScope.launch {
            var opened = 0
            for (download in downloads) {
                val ok = runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(download.url))) }.isSuccess
                if (!ok) break
                opened++
                delay(BROWSER_HANDOFF_MILLIS)
            }
            packStatus =
                when (opened) {
                    0 -> "No browser on this handset to open that with."
                    downloads.size ->
                        "$opened downloads started in your browser. Come back and tap install when they finish."
                    else -> "$opened of ${downloads.size} downloads started. Tap the rest one at a time."
                }
        }
    }

    /**
     * Opens the system file picker, and says so.
     *
     * The status is set **before** the launch, so the press is acknowledged even if no
     * activity answers the intent. A handset with no document provider throws
     * `ActivityNotFoundException` here, and an unhandled one would be a crash where a
     * sentence is wanted.
     */
    private fun pickPackFolder() {
        packStatus = "Opening the file picker…"
        // Any type: a model is an .onnx and a token table is a .txt, and a handset that
        // filters by MIME will hide one or the other.
        runCatching { choosePackFiles.launch(arrayOf("*/*")) }
            .onFailure { packStatus = "This handset has no file picker to open." }
    }

    /**
     * The deployment profile, from the asset the build copies out of `models/`.
     *
     * A failure here is fatal by choice. Every unit must hold the same table, and a handset
     * that started with an empty one would send bytes that mean nothing on arrival — which
     * is worse than not starting, because it looks like it is working.
     */
    private fun deploymentProfile() =
        TemplateProfile
            .parse(assets.open(PROFILE_ASSET).bufferedReader().use { it.readText() })
            .toTable()

    /** Stable per device, per signing key, across restarts. See [NodeIdentity]. */
    @SuppressLint("HardwareIds")
    private fun installationId(): String =
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: Build.MODEL

    /** The handset's own Bluetooth name is what the operator already calls this unit. */
    private fun unitName(): String = (Build.MODEL ?: "UNIT").uppercase()

    private fun bonded(adapter: BluetoothAdapter?): List<BluetoothDevice> =
        try {
            if (hasBluetoothPermission()) adapter?.bondedDevices?.toList().orEmpty() else emptyList()
        } catch (denied: SecurityException) {
            // The platform can revoke between the check and the call. A crash here would
            // take out the whole screen for a permission problem.
            emptyList()
        }

    override fun onKeyDown(
        keyCode: Int,
        event: android.view.KeyEvent,
    ): Boolean =
        transmitKey.onKey(keyCode, event.action, event.repeatCount) ||
            super.onKeyDown(keyCode, event)

    override fun onKeyUp(
        keyCode: Int,
        event: android.view.KeyEvent,
    ): Boolean =
        transmitKey.onKey(keyCode, event.action, event.repeatCount) ||
            super.onKeyUp(keyCode, event)

    override fun onPause() {
        super.onPause()
        // An operator interrupted mid-transmission must not leave the floor held.
        transmitKey.releaseIfHeld()
    }

    override fun onDestroy() {
        super.onDestroy()
        engine?.stopLocating()
        engine?.stop()
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                // Declared in the manifest since week 6 and never asked for, so every
                // advertisement was refused with a SecurityException the radio swallowed.
                // It is a runtime permission from Android 12 like the other two, and on a
                // broadcast channel it is the one that actually transmits.
                Manifest.permission.BLUETOOTH_ADVERTISE,
                // For finding a unit: this handset's position goes to a colleague who asks,
                // sealed, on the channel, and theirs comes back the same way. Asked for up
                // front so that being found never needs a dialog answered mid-incident.
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        } else {
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        }

    /**
     * All three Bluetooth permissions became runtime permissions in Android 12, and the
     * net needs all three: CONNECT for the bonded sockets, SCAN to hear the broadcast
     * channel, ADVERTISE to speak on it. Checking CONNECT alone let the engine start with
     * the other two refused, on a channel it could neither hear nor speak.
     */
    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            ).all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /**
     * Shown between launch and the permission answer, and for good if it was refused.
     *
     * It carries the real node id rather than a zero: the id comes from the installation
     * id and needs no permission, and `node 00` is a value [NodeIdentity] cannot produce —
     * so seeing one on screen means the engine is missing, which is not a fact worth
     * hiding behind a plausible-looking placeholder.
     */
    private fun startingState() =
        org.itantra.app.ui.OperatingState(
            unitName = identity.displayName,
            nodeId = identity.src,
            peerCount = 0,
            linkUp = false,
            transportName = "bluetooth",
            mode = "PTT",
            audience = "ALL UNITS",
            language = "हिन्दी",
            languageCode = "hi",
            // The band B menu is drawn from the first frame, before any permission answer.
            languages = MessageEngine.languageOptions(null),
            degraded =
                if (permissionRefused) EngineState.Degraded.Reason.PERMISSION_DENIED else null,
        )

    private companion object {
        const val PROFILE_ASSET = "templates.json"

        /** Between two addresses handed to the browser. Long enough to be two tabs, not one. */
        const val BROWSER_HANDOFF_MILLIS = 350L

        /** Android's own provider for the shared storage volumes. */
        const val EXTERNAL_STORAGE_PROVIDER = "com.android.externalstorage.documents"

        /**
         * The GPL-3.0 notice, shown because this build earns it.
         *
         * espeak-ng is compiled into `libsherpa-onnx-jni.so`, which ships in the installer —
         * its data-path constant and its own runtime error strings are in the binary. Under
         * GPL-3.0 section 5 that makes the whole installer a combined work conveyed under
         * GPL-3.0, and section 4 obliges this build to carry the licence and offer the
         * corresponding source. `LICENSES.md` section 6 said this obligation would be
         * "discharged by shipping the licence text in the app's about screen", and no
         * licence text was in the application at all. Now it is.
         */
        const val DISTRIBUTION_NOTICE =
            "This build links espeak-ng (GPL-3.0) inside libsherpa-onnx-jni.so, so the " +
                "installer as a whole is conveyed under GPL-3.0. Complete corresponding " +
                "source: github.com/vikranthsai310/sih2026"
    }
}
