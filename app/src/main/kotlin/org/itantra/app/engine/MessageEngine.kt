package org.itantra.app.engine

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.itantra.app.platform.NodeIdentity
import org.itantra.app.platform.SpeechInput
import org.itantra.app.ui.BandFMetrics
import org.itantra.app.ui.LanguageOption
import org.itantra.app.ui.LoggedMessage
import org.itantra.app.ui.OperatingState
import org.itantra.audio.EngineState
import org.itantra.bench.UtteranceClock
import org.itantra.bench.UtteranceTrace
import org.itantra.link.BluetoothNet
import org.itantra.link.LinkState
import org.itantra.link.MeshLink
import org.itantra.link.Session
import org.itantra.proto.EpochCounter
import org.itantra.proto.Language
import org.itantra.proto.MessageType
import org.itantra.proto.TemplateTable

/**
 * Everything the operating screen needs, joined to everything that does the work.
 *
 * ## What this is, and what it is not
 *
 * It owns a [MeshLink] over every bonded handset and one [Session] on top of it, and it
 * turns what arrives into an [OperatingState]. That is the whole of it: the screen holds no
 * logic and the protocol holds no Android.
 *
 * It is **not** the engine service from task W1.30. There is no recogniser and no
 * synthesiser here, because there are no model files — so a press of the transmit control
 * sends a **template code** rather than recognised speech, and an arriving message is
 * displayed rather than spoken. Everything between those two points is the real path:
 * script packing or template matching, AEAD with the transport's tag length, the epoch, the
 * replay window, fragmentation, relay, and the outbox when the link is down.
 *
 * That is worth being precise about, because it is exactly the half a demonstration shows.
 * The byte counter in band F is a real frame size off a real link.
 *
 * ## Why a template rather than a text field
 *
 * The debug text field was deleted in W3.12 and is not coming back. An application whose
 * fastest route to sending a message is to type it is the thing this project exists not to
 * be, and a field that exists "just for testing" is the one that gets used when the
 * recogniser is being difficult in front of a jury.
 *
 * Cycling the profile's templates also demonstrates the property that matters most: set two
 * handsets to different languages and the same byte arrives as Hindi on one and Tamil on
 * the other, with no translation anywhere.
 */
class MessageEngine(
    private val scope: CoroutineScope,
    private val adapter: BluetoothAdapter?,
    private val identity: NodeIdentity,
    private val epochStore: EpochCounter.Store,
    private val templates: TemplateTable,
    private val bondedDevices: () -> List<BluetoothDevice>,
    /** Null on a handset with no recogniser at all; every send then uses a template. */
    private val speech: SpeechInput? = null,
) {
    private val mesh = MeshLink(scope)

    private var bluetoothNet: BluetoothNet? = null

    /** Guards the loops that must exist exactly once, however often [start] is called. */
    private var started = false

    private val session =
        Session(
            link = mesh,
            key = NodeIdentity.developmentKey(),
            localSrc = identity.src,
            epochs = EpochCounter(epochStore),
            templates = templates,
            language = Language.HINDI,
        )

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<OperatingState> = _state.asStateFlow()

    /** Which template the next press sends. Cycles, so a demonstration is not one sentence. */
    private var nextTemplate = 0

    private val templateIds: List<Int> = templates.ids

    /** The language this unit renders in. Tapping band B cycles it. */
    private var language: Language = Language.HINDI

    /** Timing for the utterance in progress. Null between presses. */
    private var utterance: UtteranceClock? = null

    /** Completed by the recogniser, or by the release when there is no recogniser. */
    private var pendingSpeech: CompletableDeferred<SpeechInput.Result?>? = null

    /** Why the last attempt produced no words, for the note under band C. */
    private var lastSpeechProblem: String? = null

    private val _traces = MutableStateFlow<List<UtteranceTrace>>(emptyList())

    /**
     * Real latency traces, for the metrics screen.
     *
     * Only utterances that were actually recognised. A template send has no recognition
     * stage to time, and padding the series with zeroes would make the median a lie in
     * exactly the direction that flatters the project.
     */
    val traces: StateFlow<List<UtteranceTrace>> = _traces.asStateFlow()

    private fun initialState() =
        OperatingState(
            unitName = identity.displayName,
            nodeId = identity.src,
            peerCount = 0,
            linkUp = false,
            transportName = "bluetooth",
            mode = "PTT",
            audience = "ALL UNITS",
            language = displayNameFor(Language.HINDI),
        )

    // ── the net ──────────────────────────────────────────────────────────────

    /**
     * Brings the net up: one listener, and a dial loop per bonded handset.
     *
     * The roster belongs to [BluetoothNet] rather than to this class, because who dials, who
     * listens and when to retry are decisions about the whole net. This method's only job is
     * to say whether there is a radio to run it on, and to make the answer visible: a
     * handset with Bluetooth switched off and a handset with nobody paired both used to
     * show `NO LINK` and "reconnecting automatically", which is true of neither.
     */
    fun start() {
        if (!started) {
            started = true
            scope.launch { receiveLoop() }
            startRefreshTicker()
        }
        if (bluetoothNet != null) return

        val bluetooth = adapter
        if (bluetooth == null || !isEnabled(bluetooth)) {
            // Not a link failure, and nothing retries its way out of a radio that is off.
            // refresh() says which of the two it is; the tick above notices when the
            // operator comes back from Settings having fixed it.
            refresh()
            return
        }

        val net = BluetoothNet(bluetooth, scope, mesh, bondedDevices)
        bluetoothNet = net
        net.start()

        scope.launch {
            mesh.connect()
            watchLink()
        }
        refresh()
    }

    /**
     * Called when the operator returns to the application, which is how the two commonest
     * problems get fixed: Bluetooth switched on, or another handset paired.
     *
     * [start] is idempotent, so this is a retry rather than a second engine.
     */
    fun restartIfIdle() = start()

    /**
     * Peers appear and vanish without the mesh's own state changing — two units down to one
     * is still CONNECTED — and a StateFlow conflates that away, so the screen would keep
     * showing a count that had stopped being true. A tick is the honest way to display a
     * number that changes for reasons nothing emits.
     */
    private fun startRefreshTicker() {
        scope.launch {
            while (true) {
                delay(REFRESH_MILLIS)
                refresh()
            }
        }
    }

    fun stop() {
        bluetoothNet?.stop()
        bluetoothNet = null
        started = false
        scope.launch { mesh.disconnect() }
    }

    private fun isEnabled(bluetooth: BluetoothAdapter): Boolean =
        runCatching { bluetooth.isEnabled }.getOrDefault(false)

    private suspend fun watchLink() {
        mesh.state.collect { linkState ->
            if (linkState == LinkState.CONNECTED) {
                // Anything written while the net was down goes now, in the order it was
                // spoken. The operator is not asked to resend.
                session.flushOutbox(System.currentTimeMillis())
            }
            refresh()
        }
    }

    // ── receive ──────────────────────────────────────────────────────────────

    private suspend fun receiveLoop() {
        mesh.incoming.collect { wire ->
            when (val received = session.receive(wire, System.currentTimeMillis())) {
                is Session.Received.Message -> onMessage(received)
                is Session.Received.Dropped -> onDropped(received)
                else -> Unit
            }

            // A unit out of direct range is reached by this handset rebroadcasting. The
            // frame is both read and relayed, which is why the relay decision is taken
            // aside rather than returned.
            session.takeRelay()?.let { relay ->
                scope.launch {
                    delay(relay.delayMillis)
                    mesh.send(relay.frame.encode())
                }
            }
        }
    }

    private fun onMessage(message: Session.Received.Message) {
        val entry =
            LoggedMessage(
                from = "node ${message.from}",
                text = message.text,
                age = "now",
                frameBytes = message.wireBytes,
                language = displayNameFor(language),
                wasTemplate = message.wasTemplate,
                isAlert = message.frame.type == MessageType.ALERT,
                delivery = LoggedMessage.Delivery.RECEIVED,
                confidence = message.frame.confidence,
            )
        _state.value =
            _state.value.copy(
                messages = (listOf(entry) + _state.value.messages).take(MAX_ON_SCREEN),
                metrics = _state.value.metrics.copy(lastFrameBytes = message.wireBytes),
            )
    }

    /**
     * A dropped frame is shown only when the operator can do something about it.
     *
     * A wrong key or a frame from an unpaired transmitter is ordinary background noise on a
     * shared radio channel and reporting it would train people to ignore the banner. A
     * sustained forgery attempt is not.
     */
    private fun onDropped(dropped: Session.Received.Dropped) {
        if (dropped.reason == Session.Reason.UNDER_ATTACK) {
            degrade(EngineState.Degraded.Reason.TEMPLATE_MISMATCH)
        }
    }

    // ── send ─────────────────────────────────────────────────────────────────

    /**
     * Called on the transmit control, and by the hardware key with the screen off.
     *
     * Sends on **release** rather than press, mirroring push-to-talk: the message is what
     * was said while the control was held, so it goes when the operator lets go. The
     * microphone is open for exactly that interval, and the recogniser is stopped here
     * rather than left to find a pause of its own -- on a radio net the operator decides
     * where the sentence ends.
     */
    fun onTransmit(held: Boolean) {
        if (held) beginUtterance() else endUtterance()
    }

    private fun beginUtterance() {
        val clock =
            UtteranceClock(
                utteranceId = "u" + System.currentTimeMillis(),
                language = language.code,
                mode = "PTT",
                transport = "bluetooth",
            ).start()
        utterance = clock

        val heard = CompletableDeferred<SpeechInput.Result?>()
        pendingSpeech = heard

        _state.value =
            _state.value.copy(transmitting = true, partial = null, level = 0f, speechNote = null)

        val listening = speech?.start(SpeechInput.tagFor(language.code), speechListener(clock, heard))
        if (listening != true) {
            // No recogniser, or it would not open. Settled now rather than on release, so
            // the operator is not made to wait for a result that was never coming.
            lastSpeechProblem = if (speech?.available == true) "recogniser would not start" else null
            heard.complete(null)
        }
    }

    private fun endUtterance() {
        _state.value = _state.value.copy(transmitting = false, level = 0f)
        speech?.stop()
        val heard = pendingSpeech
        val clock = utterance
        pendingSpeech = null
        utterance = null

        scope.launch {
            // Bounded. A recogniser that never answers must not silently eat the message.
            val result = heard?.let { withTimeoutOrNull(SPEECH_TIMEOUT_MILLIS) { it.await() } }
            clock?.mark(UtteranceClock.Stage.FINAL)
            send(result, clock, MessageType.TEXT)
        }
    }

    private fun speechListener(
        clock: UtteranceClock,
        heard: CompletableDeferred<SpeechInput.Result?>,
    ) = object : SpeechInput.Listener {
        override fun onLevel(level: Float) {
            _state.value = _state.value.copy(level = level)
        }

        override fun onPartial(text: String) {
            // The first hypothesis is a real latency stage, and the one W1.33 names
            // FIRST_PARTIAL. The mark is first-one-wins, so later partials cost nothing.
            clock.mark(UtteranceClock.Stage.FIRST_PARTIAL)
            _state.value = _state.value.copy(partial = text)
        }

        override fun onResult(result: SpeechInput.Result) {
            lastSpeechProblem = null
            heard.complete(result)
        }

        override fun onNothingHeard(reason: String) {
            lastSpeechProblem = reason
            heard.complete(null)
        }
    }

    /**
     * An alert is deliberately **not** spoken.
     *
     * It is the one control that has to work with a hand over the microphone and a
     * helicopter overhead, so it sends a template the receiving unit already holds rather
     * than waiting on a recogniser. `docs/UX.md` rule 3.
     */
    fun onAlert() {
        scope.launch { send(null, null, MessageType.ALERT) }
    }

    /**
     * Sends what was heard, or a template when nothing was.
     *
     * The fallback is not a convenience. Without acoustic models the template was the *only*
     * path, and it would be easy to leave the two indistinguishable on screen -- a canned
     * sentence and a recognised one are both just text in band E.
     * [LoggedMessage.fromSpeech] keeps them apart, because a demonstration that cannot tell
     * you which of the two just happened is not demonstrating anything.
     */
    private suspend fun send(
        heard: SpeechInput.Result?,
        clock: UtteranceClock?,
        type: MessageType,
    ) {
        val text = heard?.text ?: nextTemplateText() ?: return
        // The recogniser's own score, where it offers one. Below the threshold the message
        // still goes -- an uncertain sentence beats silence on a radio net -- but it travels
        // marked, and Session narrows the template match accordingly.
        val confident = heard == null || (heard.confidence ?: 1f) >= CONFIDENT_ABOVE

        val sent =
            session.send(text, confident = confident, type = type, nowMillis = System.currentTimeMillis())
        clock?.mark(UtteranceClock.Stage.TX)

        val entry =
            LoggedMessage(
                from = identity.displayName,
                text = text,
                age = "now",
                frameBytes = sent.wireBytes,
                language = displayNameFor(language),
                wasTemplate = sent.templateId != null,
                isAlert = type == MessageType.ALERT,
                delivery =
                    if (sent.queued) LoggedMessage.Delivery.PENDING else LoggedMessage.Delivery.SENT,
                fromSpeech = heard != null,
            )
        _state.value =
            _state.value.copy(
                messages = (listOf(entry) + _state.value.messages).take(MAX_ON_SCREEN),
                metrics = metricsFrom(clock, sent.wireBytes, recognised = heard != null),
                queued = session.queuedCount,
                partial = null,
                speechNote = speechNote(heard),
            )
        if (heard != null && clock != null) {
            _traces.value = (_traces.value + clock.toTrace(frameBytes = sent.wireBytes)).takeLast(MAX_TRACES)
        }
    }

    /** Cycles the profile, so a demonstration is not one sentence repeated. */
    private fun nextTemplateText(): String? {
        if (templateIds.isEmpty()) return null
        val id = templateIds[nextTemplate % templateIds.size]
        nextTemplate++
        return templates.render(id, language)
    }

    /**
     * Band F, from the utterance that just happened rather than from a benchmark.
     *
     * `STT` is the microphone opening to the final transcription, and `LINK` is that to the
     * frame leaving. Both stay absent unless something was actually recognised, and absent
     * is drawn as a dash: the time a recogniser spends refusing an utterance is not
     * recognition latency, and reporting it as such flatters the project in precisely the
     * direction a reader should distrust.
     */
    private fun metricsFrom(
        clock: UtteranceClock?,
        wireBytes: Int,
        recognised: Boolean,
    ): BandFMetrics {
        val previous = _state.value.metrics
        if (clock == null || !recognised) {
            // Caught on a real handset: a press where the recogniser answered "language pack
            // not installed" still reported STT 79 ms, because the clock had been running
            // and the marks were read regardless. Seventy-nine milliseconds to *decline* was
            // being displayed as seventy-nine milliseconds to recognise -- the metrics screen
            // said "0 utterances" about the very same press, and band F is the strip a jury
            // photographs.
            //
            // Clearing rather than leaving the previous figures: a stale STT standing beside
            // this frame's byte count reads as a measurement of this frame.
            return previous.copy(
                sttMillis = null,
                linkMillis = null,
                totalMillis = null,
                lastFrameBytes = wireBytes,
            )
        }
        val stt = clock.elapsedMillis(UtteranceClock.Stage.FINAL)
        val total = clock.elapsedMillis(UtteranceClock.Stage.TX)
        return previous.copy(
            sttMillis = stt,
            linkMillis = if (total != null && stt != null) total - stt else null,
            totalMillis = total,
            lastFrameBytes = wireBytes,
        )
    }

    /** What the screen says about the last attempt at speech. Null when it simply worked. */
    private fun speechNote(heard: SpeechInput.Result?): String? =
        when {
            heard != null -> null
            speech == null || speech.available != true ->
                "No speech recogniser on this handset. Sent a template."
            // Capitalised and used as the whole sentence. Prefixing it read as "Heard
            // nothing: nothing recognised", which is the same fact said twice.
            lastSpeechProblem != null ->
                lastSpeechProblem!!.replaceFirstChar(Char::uppercase) + ". Sent a template."
            else -> null
        }

    /**
     * Cycles the language this unit renders in.
     *
     * The point of the control on two handsets: set them differently and the same template
     * byte arrives as Hindi on one and Tamil on the other. Nothing translates — both hold
     * the same table in ten languages, which is the whole of the mechanism.
     */
    fun onLanguageCycle() {
        val all = Language.entries
        language = all[(all.indexOf(language) + 1) % all.size]
        refresh()
    }

    /** Chosen from the language screen, where cycling ten of them one tap at a time is not a control. */
    fun onLanguageChosen(code: String) {
        language = Language.entries.firstOrNull { it.code == code } ?: return
        refresh()
    }

    /** The languages this unit can render, and whether it can also speak them. */
    fun languageOptions(): List<LanguageOption> =
        Language.entries.map {
            LanguageOption(
                code = it.code,
                nativeName = displayNameFor(it),
                englishName = it.name.lowercase().replaceFirstChar(Char::uppercase),
                // No Piper voices are present, so no language can be spoken aloud on any
                // handset built from this repository. Reported rather than assumed.
                canSpeak = false,
            )
        }

    /**
     * A condition that does not clear itself when the net recovers.
     *
     * Sustained forgery is the only one so far, and it must survive a [refresh] — the link
     * coming back is not evidence that the attempt stopped.
     */
    private var sticky: EngineState.Degraded.Reason? = null

    private fun degrade(reason: EngineState.Degraded.Reason) {
        sticky = reason
        _state.value = _state.value.copy(degraded = reason)
    }

    private fun refresh() {
        _state.value =
            _state.value.copy(
                peerCount = mesh.connectedCount,
                linkUp = mesh.state.value == LinkState.CONNECTED,
                language = displayNameFor(language),
                queued = session.queuedCount,
                degraded = sticky ?: netTrouble(),
            )
    }

    /**
     * What is wrong with the net, distinguished so the banner can say what to do.
     *
     * The three states used to be one. "Link down — reconnecting" on a handset with the
     * radio switched off, or with nothing paired, is advice for a situation that will never
     * resolve, and it is the single most likely thing to be on screen when this application
     * is first opened.
     */
    private fun netTrouble(): EngineState.Degraded.Reason? {
        val bluetooth = adapter
        if (bluetooth == null || !isEnabled(bluetooth)) {
            return EngineState.Degraded.Reason.BLUETOOTH_OFF
        }
        if (mesh.connectedCount > 0) return null
        val paired = bluetoothNet?.candidateCount ?: 0
        return if (paired == 0) {
            EngineState.Degraded.Reason.NO_PEERS
        } else {
            EngineState.Degraded.Reason.LINK_DOWN
        }
    }

    private companion object {
        const val MAX_ON_SCREEN = 20

        /** How often the screen re-reads the roster. Cheap, and the numbers are live. */
        const val REFRESH_MILLIS = 1_000L

        /**
         * How long a release waits for the recogniser's final answer.
         *
         * Long enough for an on-device pass over a held sentence, short enough that a
         * recogniser which has stopped answering does not swallow the message -- the
         * template goes instead, and the screen says so.
         */
        const val SPEECH_TIMEOUT_MILLIS = 4_000L

        /** Below this the transcription still goes, marked uncertain rather than dropped. */
        const val CONFIDENT_ABOVE = 0.6f

        /** Enough for the 100-utterance run docs/EVALUATION.md section 4 asks for. */
        const val MAX_TRACES = 200

        /** Each language in its own script — a speaker of Odia is looking for ଓଡ଼ିଆ. */
        fun displayNameFor(language: Language): String =
            when (language) {
                Language.ENGLISH -> "English"
                Language.HINDI -> "हिन्दी"
                Language.BENGALI -> "বাংলা"
                Language.MARATHI -> "मराठी"
                Language.TELUGU -> "తెలుగు"
                Language.TAMIL -> "தமிழ்"
                Language.GUJARATI -> "ગુજરાતી"
                Language.KANNADA -> "ಕನ್ನಡ"
                Language.MALAYALAM -> "മലയാളം"
                Language.ODIA -> "ଓଡ଼ିଆ"
            }
    }
}
