package org.itantra.app.engine

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.itantra.app.platform.FoundBeacon
import org.itantra.app.platform.LocateSiren
import org.itantra.app.platform.NodeIdentity
import org.itantra.app.platform.PositionSource
import org.itantra.app.platform.Recogniser
import org.itantra.app.platform.SherpaSpeech
import org.itantra.app.platform.Speaker
import org.itantra.app.platform.UnitPreferences
import org.itantra.app.ui.BandFMetrics
import org.itantra.app.ui.LanguageOption
import org.itantra.app.ui.LocateState
import org.itantra.app.ui.LoggedMessage
import org.itantra.app.ui.OperatingState
import org.itantra.app.ui.SoundFrom
import org.itantra.asr.BiasingLexicon
import org.itantra.asr.LexiconCorrector
import org.itantra.audio.EngineState
import org.itantra.bench.UtteranceClock
import org.itantra.bench.UtteranceTrace
import org.itantra.link.BleBroadcastLink
import org.itantra.link.BluetoothNet
import org.itantra.link.LinkState
import org.itantra.link.MeshLink
import org.itantra.link.Road
import org.itantra.link.Session
import org.itantra.link.Signal
import org.itantra.link.WifiBroadcastLink
import org.itantra.proto.Aead
import org.itantra.proto.EpochCounter
import org.itantra.proto.Language
import org.itantra.proto.Locate
import org.itantra.proto.MessageType
import org.itantra.proto.Presence
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
    /**
     * Null until a language pack is on the handset, and every send then uses a template.
     *
     * Deliberately null today. See [Recogniser] -- the implementation that used to sit here
     * drove Google Speech Services, which constraint C1 prohibits by name.
     */
    private val speech: Recogniser? = null,
    /**
     * The alert lexicon for a language, or null where none ships.
     *
     * A function rather than a map so the twenty small files are read on a language change
     * rather than all at once at startup.
     */
    private val lexicons: (String) -> BiasingLexicon? = { null },
    /** Null leaves the Wi-Fi channel out; the Bluetooth one still runs. */
    private val wifiContext: android.content.Context? = null,
    /** Null on a handset with no voice installed; arrivals are then shown but not spoken. */
    private val speaker: Speaker? = null,
    /** The operator's name for this unit, its mode and its text size. Null in a test. */
    private val preferences: UnitPreferences? = null,
    /** Where this handset is, for beaconing and for calibrating the distance. Null without a receiver. */
    private val positions: PositionSource? = null,
) {
    private val mesh = MeshLink(scope)

    private var bluetoothNet: BluetoothNet? = null

    /** The Wi-Fi road is added once per run; see [start]. */
    private var wifiAdded = false

    /** The mesh has been opened and is being watched; see [start]. */
    private var netStarted = false

    /** Guards the loops that must exist exactly once, however often [start] is called. */
    private var started = false

    /** The loops, held so [stop] can end them rather than leave a second set running. */
    private val loops = ArrayList<Job>()

    private val session =
        Session(
            link = mesh,
            key = NodeIdentity.developmentKey(),
            localSrc = identity.src,
            // Seeded from the clock, so a reinstalled handset's epoch still moves forward
            // and the other units do not refuse it as a replay until they restart.
            epochs = EpochCounter(epochStore, clock = System::currentTimeMillis),
            templates = templates,
            language = Language.HINDI,
        ).apply {
            // The operator's hop count and road switch, as they were when the handset
            // was last used. Applied here rather than in start(), which is idempotent
            // and re-entered on every return to the application.
            ttl = preferences?.ttl ?: Session.DEFAULT_TTL
        }

    init {
        mesh.roads = preferences?.roads
    }

    // Declared before the state, because the state is built from them. Kotlin initialises
    // properties in declaration order, and the first version declared these two below the
    // state: initialState() read them as null, and the screen died on its first frame.

    /** What this unit calls itself, as the operator set it. */
    private var unitName: String = preferences?.unitName ?: identity.displayName

    /** [UnitPreferences.MODE_PTT] or [UnitPreferences.MODE_PHONE]. */
    private var mode: String = preferences?.mode ?: UnitPreferences.MODE_PTT

    private var openLinePaused = false

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
    private var pendingSpeech: CompletableDeferred<Recogniser.Result?>? = null

    /** Why the last attempt produced no words, for the note under band C. */
    private var lastSpeechProblem: String? = null

    /** Rebuilt on a language change; null when that language ships no lexicon. */
    private var corrector: LexiconCorrector? = null

    /**
     * Which units have been heard from lately, and when.
     *
     * A broadcast channel has no roster to count: nothing is connected to anything, so
     * `MeshLink.connectedCount` would say "1 unit" on an empty channel and "1 unit" in a
     * room of six. The honest count is who has actually transmitted recently, which is also
     * what an operator means by the number.
     */
    private val heardFrom = LinkedHashMap<Int, Long>()

    /** Who is on the channel, by name and signal. */
    private val roster = Roster()

    private val locator = Locator(positions, LocateSiren(wifiContext))

    /** The locate screen's state: null when nobody is being looked for. */
    val locate: StateFlow<LocateState?> get() = locator.state

    /** Until when this unit beacons its position, because another asked. Zero when it does not. */
    private var beaconingUntilMillis = 0L

    /**
     * The chirp this unit makes when a searcher asks for it, and how long the last
     * request holds. A searcher whose handset died must not leave a target chirping in a
     * pocket for the rest of the day.
     */
    private val foundBeacon = FoundBeacon(wifiContext)
    private var chirpUntilMillis = 0L

    /**
     * Who asked for the chirp, and how strongly this unit hears them: the chirp quickens
     * as the searcher closes in, on the searcher's own advertisements, which this unit
     * hears whether or not the searcher hears it.
     */
    @Volatile
    private var chirpRequester = -1
    private val chirpRange = SignalSmoother()

    @Volatile
    private var chirpSignalAtMillis = 0L

    /** What this unit last asked its target about sound, so a change is sent at once. */
    private var askedTheirSound = false

    /** What the speaker is saying, or said within the last few seconds, for the echo filter. */
    @Volatile
    private var speakingText: String? = null

    @Volatile
    private var lastSpokenText: String? = null

    @Volatile
    private var lastSpokenUntilMillis = 0L

    /** Process CPU and wall clock at the press, so the utterance's share can be differenced. */
    private var cpuAtPressMillis = 0L
    private var wallAtPressMillis = 0L

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
            unitName = unitName,
            nodeId = identity.src,
            peerCount = 0,
            linkUp = false,
            transportName = "bluetooth",
            mode = mode,
            audience = "ALL UNITS",
            language = displayNameFor(Language.HINDI),
            languageCode = Language.HINDI.code,
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
            loops += scope.launch { receiveLoop() }
            loops += startRefreshTicker()
            loops += scope.launch { mesh.signals.collect { onSignal(it) } }
            if (mode == UnitPreferences.MODE_PHONE) startOpenLine()
            // On first run this is what downloads the model for the starting language,
            // rather than waiting for the operator to discover its absence by pressing
            // transmit and getting a template.
            ensurePackFor(language)
        }

        // The Wi-Fi road first, and whatever the Bluetooth radio is doing. One handset's
        // hotspot, a Wi-Fi Direct group made in the system settings, or any access point
        // the units share is a local network, and every unit on it receives the same
        // datagram: no data plan, no internet, no pairing. The first version added this
        // road only after it had checked Bluetooth, and returned early when Bluetooth was
        // off -- so a unit with Bluetooth off and a Wi-Fi Direct link up had no road at
        // all, and "the text did not transfer" was the whole of what the operator saw.
        // Added once: the recovery tick re-enters here every few seconds, and re-adding
        // the road would close and reopen its socket each time.
        if (!wifiAdded) {
            wifiContext?.let {
                mesh.addPeer(WIFI_PEER, WifiBroadcastLink(it, scope))
                wifiAdded = true
            }
        }

        // The mesh's own life: opened once, and watched for the rest of the run. Roads
        // added later are opened by the recovery tick's reconnectDown.
        if (!netStarted) {
            netStarted = true
            loops +=
                scope.launch {
                    mesh.connect()
                    // Announced at once as well as on the tick, so a restarted handset is
                    // known again within a second rather than within five.
                    delay(HELLO_AFTER_CONNECT_MILLIS)
                    if (mesh.state.value == LinkState.CONNECTED) runCatching { mesh.send(session.hello()) }
                    watchLink()
                }
        }

        val bluetooth = adapter
        if (bluetoothNet != null || bluetooth == null || !isEnabled(bluetooth)) {
            // Bluetooth roads already up, or no radio to put them on. Nothing retries its
            // way out of a radio that is off; the tick above notices when the operator
            // comes back from Settings having switched it on, and the Wi-Fi road carries
            // the traffic meanwhile.
            refresh()
            return
        }

        // The radio channel, because it needs nothing arranged. Every unit with the
        // application open is on it; there is no bond, no dialog and no roster. This is the
        // shape docs/PROTOCOL.md section 8 always described — every frame to every unit,
        // no destination field — and the shape docs/TRANSPORT.md section 8 says pairing
        // keeps breaking on demonstration day. The two radios fail differently, which is
        // the reason for running both: a frame arriving by both roads is delivered once,
        // because the replay window discards the second copy.
        mesh.addPeer(
            BROADCAST_PEER,
            BleBroadcastLink(
                bluetooth,
                scope,
                // Stamped on every advertisement: a hearing is a reading of this unit's
                // distance, whichever unit first said the frames it carries.
                localSrc = identity.src,
                keyId = Aead.keyId(NodeIdentity.developmentKey()),
            ),
        )

        // Bonded handsets are still used where they exist: RFCOMM is ~200 kbps against a
        // few advertisements a second, so a paired pair gets the better link for free. It
        // is no longer a requirement for the application to work.
        val net = BluetoothNet(bluetooth, scope, mesh, bondedDevices)
        bluetoothNet = net
        net.start()
        // Opened now rather than at the next tick: a radio switched on in Settings should
        // be carrying within a second of the operator coming back.
        scope.launch {
            mesh.reconnectDown()
            if (mesh.state.value == LinkState.CONNECTED) runCatching { mesh.send(session.hello()) }
        }
        refresh()
    }

    /**
     * Called when the operator returns to the application, which is how the two commonest
     * problems get fixed: Bluetooth switched on, or another handset paired.
     *
     * [start] is idempotent, so this is a retry rather than a second engine. Roads that
     * are down are asked to try again now rather than at the next tick.
     */
    fun restartIfIdle() {
        start()
        scope.launch { mesh.reconnectDown() }
    }

    /**
     * Peers appear and vanish without the mesh's own state changing — two units down to one
     * is still CONNECTED — and a StateFlow conflates that away, so the screen would keep
     * showing a count that had stopped being true. A tick is the honest way to display a
     * number that changes for reasons nothing emits.
     */
    private fun startRefreshTicker(): Job =
        scope.launch {
            var tick = 0L
            while (true) {
                delay(REFRESH_MILLIS)
                refresh()
                runCatching { everySecond(++tick) }.onFailure { Log.w(TAG, "presence tick failed", it) }
                if (tick % RECOVERY_EVERY_TICKS != 0L) continue
                // The slow work of keeping the net alive, every few seconds: a radio that
                // was off when the engine started, a road that has gone down, and a
                // message written while nothing was reachable. None of these announce
                // themselves, so they are asked after.
                runCatching {
                    if (bluetoothNet == null) start()
                    mesh.reconnectDown()
                    if (session.queuedCount > 0) session.flushOutbox(System.currentTimeMillis())
                    // Sixteen bytes saying which epoch this unit is on, so a unit that
                    // has just met us -- or that we have just restarted on -- finds our
                    // frames' epoch in one check instead of a search.
                    if (mesh.state.value == LinkState.CONNECTED) mesh.send(session.hello())
                }.onFailure { Log.w(TAG, "recovery tick failed", it) }
            }
        }

    fun stop() {
        foundBeacon.stop()
        speaker?.close()
        speech?.close()
        bluetoothNet?.stop()
        bluetoothNet = null
        // Ended, not abandoned: a second start() on the same scope would otherwise run a
        // second receive loop beside the first and read every frame twice.
        loops.forEach { it.cancel() }
        loops.clear()
        started = false
        wifiAdded = false
        netStarted = false
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

    /**
     * Reads the net for as long as the engine runs.
     *
     * One frame at a time, and each inside its own guard: a frame that throws on the way
     * through the session is logged and dropped, not allowed to end the collector. The
     * first version had no guard, so one malformed packed payload stopped this handset
     * receiving anything, for the life of the process, while the screen said LINK OK.
     */
    private suspend fun receiveLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                mesh.incoming.collect { wire ->
                    runCatching { onWire(wire) }
                        .onFailure { Log.w(TAG, "a frame of ${wire.size} B could not be handled", it) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "the receive loop failed; restarting it", failure)
                delay(RECEIVE_RESTART_MILLIS)
            }
        }
    }

    private fun onWire(wire: ByteArray) {
        when (val received = session.receive(wire, System.currentTimeMillis())) {
            is Session.Received.Message -> {
                Log.i(TAG, "accepted ${received.wireBytes} B from node ${received.from}")
                onMessage(received)
            }

            is Session.Received.Dropped -> {
                // Named, because a channel that hears everything and accepts nothing
                // is indistinguishable from a channel that hears nothing at all, and
                // the two have entirely different causes.
                Log.w(TAG, "dropped ${wire.size} B: ${received.reason} ${received.detail}")
                onDropped(received)
            }

            is Session.Received.Presence -> onPresence(received)

            is Session.Received.Locate -> onLocate(received)

            else -> Unit
        }

        // A unit out of direct range is reached by this handset rebroadcasting. The
        // frame is both read and relayed, which is why the relay decisions are taken
        // aside rather than returned.
        for (relay in session.takeRelays()) {
            scope.launch {
                delay(relay.delayMillis)
                // A relayed alert is still an alert: it goes down every road that is up,
                // whatever this unit's operator switched off for their own traffic.
                mesh.send(relay.frame.encode(), urgent = relay.frame.type == MessageType.ALERT)
            }
        }
    }

    private fun onMessage(message: Session.Received.Message) {
        val now = SystemClock.elapsedRealtime()
        synchronized(heardFrom) { heardFrom[message.from] = now }
        roster.heard(message.from, now)
        // A template is rendered in this unit's language; free text arrives in the
        // sender's, whatever this unit is set to, and is read and spoken as such.
        val writtenIn = if (message.wasTemplate) language else message.frame.language
        val entry =
            LoggedMessage(
                from = roster.nameOf(message.from),
                text = message.text,
                age = "now",
                frameBytes = message.wireBytes,
                language = displayNameFor(writtenIn),
                wasTemplate = message.wasTemplate,
                isAlert = message.frame.type == MessageType.ALERT,
                delivery = LoggedMessage.Delivery.RECEIVED,
                confidence = message.frame.confidence,
            )
        _state.value =
            _state.value.copy(
                messages = (_state.value.messages + entry).takeLast(MAX_ON_SCREEN),
                metrics = _state.value.metrics.copy(lastFrameBytes = message.wireBytes),
            )
        // Both halves of this call arrived from different branches and both are needed:
        // `writtenIn` picks the voice for text in another unit's script, `from` is what the
        // screen draws the speaking indicator against.
        speakArrival(
            message.text,
            from = entry.from,
            alert = message.frame.type == MessageType.ALERT,
            writtenIn = writtenIn,
        )
    }

    /**
     * Says an arriving message out loud, and times how long that took to start.
     *
     * The problem statement asks for the delay "between the text received and audio
     * processed and played", so the clock starts here — at the moment the frame became text
     * — and stops on the first **sound**, not on the end of synthesis. A listener has heard
     * something at the first chunk; the rest arrives while they are listening to it.
     */
    private fun speakArrival(
        text: String,
        from: String,
        alert: Boolean,
        writtenIn: Language = language,
    ) {
        val speaker = speaker ?: return
        val receivedAt = SystemClock.elapsedRealtime()
        // Free text from a unit set to another language is in that language's script. A
        // Hindi voice given Telugu text produces nothing a listener can use; the Telugu
        // voice, where this handset has it, does. Where it does not, this unit's own
        // voice is tried, which is at least the honest failure the screen already shows.
        val voice = if (writtenIn != language && speaker.canSpeak(writtenIn.code)) writtenIn else language
        // For the open line's echo filter: what the microphone may be about to hear.
        speakingText = text
        lastSpokenText = text
        speaker.speak(
            languageCode = voice.code,
            text = text,
            onFirstAudio = {
                // The same instant that stops the TTS clock starts the screen's speaking
                // state: the first chunk reaching the audio device is both the latency the
                // problem statement asks for and the moment a listener has heard something.
                _state.value =
                    _state.value.copy(
                        speakingFrom = from,
                        metrics =
                            _state.value.metrics.copy(
                                ttsMillis = SystemClock.elapsedRealtime() - receivedAt,
                            ),
                    )
            },
            // Cleared on the same callback that ends the utterance, so the indicator cannot
            // outlive the sound. `Speaker.speak` runs onFinished on its worker whatever
            // happened -- finished, barged in by a newer message, or failed mid-sentence --
            // which is exactly the guarantee this needs to not stick on.
            onFinished = {
                speakingText = null
                lastSpokenUntilMillis = SystemClock.elapsedRealtime() + ECHO_GRACE_MILLIS
                _state.value = _state.value.copy(speakingFrom = null)
            },
        )
        if (alert) {
            // W5.13: an alert is announced rather than merely spoken. The volume and focus
            // handling lives in AlertPlayback; this is the hook it attaches to.
            alerting = true
        }
    }

    /** Replays a message from the log. The control existed in MessageLogScreen with no wiring. */
    fun onReplay(text: String) {
        speaker?.speak(language.code, text)
    }

    /** Whether the last arrival was an alert, for the announcement path. */
    private var alerting = false

    /**
     * A frame that was heard and refused is put in the message log, marked as not read.
     *
     * The banner is still raised only for a sustained forgery attempt: a wrong key or a
     * frame from an unpaired transmitter is ordinary background noise on a shared radio
     * channel, and a banner for it would train people to ignore the banner.
     *
     * "Nothing arrived" and "something arrived and was refused" are different faults with
     * different cures, and until this the screen showed them identically -- the reason was
     * in logcat, where an operator in the field cannot read it. Duplicates (a frame that
     * came by two roads) and this unit's own frames heard back are normal and stay quiet.
     */
    private fun onDropped(dropped: Session.Received.Dropped) {
        if (dropped.reason == Session.Reason.UNDER_ATTACK) {
            degrade(EngineState.Degraded.Reason.TEMPLATE_MISMATCH)
        }
        val explanation =
            when (dropped.reason) {
                Session.Reason.NOT_AUTHENTIC, Session.Reason.UNDER_ATTACK ->
                    "Heard a frame that did not verify. The other unit is on a different key " +
                        "or an older build of this application."
                Session.Reason.WRONG_KEY -> "Heard a frame from a unit with a different key."
                Session.Reason.MALFORMED -> "Heard a corrupt frame."
                Session.Reason.UNREADABLE -> "Heard a frame that could not be read: ${dropped.detail}."
                Session.Reason.NOT_FOR_US ->
                    if (dropped.detail.startsWith("another unit")) {
                        "Another unit on the channel has this unit's node id. Reinstall one of them."
                    } else {
                        return
                    }
                Session.Reason.REPLAYED -> return
            }
        val now = SystemClock.elapsedRealtime()
        // One line per fault, not one per frame: a unit on the wrong key sends a frame a
        // second and the log would be nothing else.
        if (explanation == lastDropShown && now - lastDropShownAt < DROP_NOTE_REPEAT_MILLIS) return
        lastDropShown = explanation
        lastDropShownAt = now
        val entry =
            LoggedMessage(
                from =
                    dropped.detail.substringAfter("src ", "").substringBefore(' ').ifEmpty { "channel" }.let {
                        if (it == "channel") it else "node $it"
                    },
                text = explanation,
                age = "now",
                frameBytes = 0,
                language = displayNameFor(language),
                wasTemplate = false,
                isAlert = false,
                delivery = LoggedMessage.Delivery.REFUSED,
            )
        _state.value = _state.value.copy(messages = (_state.value.messages + entry).takeLast(MAX_ON_SCREEN))
    }

    private var lastDropShown: String? = null
    private var lastDropShownAt = 0L

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
        if (mode == UnitPreferences.MODE_PHONE) {
            // On the open line the control is HOLD: a press pauses the microphone, the
            // next press resumes it. Nothing is sent by pressing; the pause sends.
            if (held) toggleOpenLineHold()
            return
        }
        if (held) beginUtterance() else endUtterance()
    }

    private fun beginUtterance() {
        // Built now, started when the microphone actually opens. UtteranceClock.start()
        // means "the microphone delivered the first hop", and marking it on the press would
        // fold the recognition service's bind time into the STT figure -- reporting the
        // platform's start-up cost as this project's recognition latency.
        val clock =
            UtteranceClock(
                utteranceId = "u" + System.currentTimeMillis(),
                language = language.code,
                mode = "PTT",
                transport = "bluetooth",
            )
        utterance = clock

        val heard = CompletableDeferred<Recogniser.Result?>()
        pendingSpeech = heard
        cpuAtPressMillis = Process.getElapsedCpuTime()
        wallAtPressMillis = SystemClock.elapsedRealtime()

        _state.value =
            _state.value.copy(
                transmitting = true,
                // Not yet. The panel used to inform an operator that it was transmitting
                // while the microphone was still being opened, which is when the words that
                // went missing were spoken.
                listening = false,
                partial = null,
                level = 0f,
                speechNote = null,
            )

        val listening = speech?.start(language.code, speechListener(clock, heard))
        if (listening != true) {
            // No recogniser, or it would not open. Settled now rather than on release, so
            // the operator is not made to wait for a result that was never coming.
            lastSpeechProblem =
                if (speech != null) "the recogniser would not start" else null
            heard.complete(null)
        }
    }

    private fun endUtterance() {
        _state.value = _state.value.copy(transmitting = false, listening = false, level = 0f)
        // The operator letting go *is* the end of speech, and it is the instant every
        // latency figure is measured from. Marked before the recogniser is told, so the
        // stop's own cost lands inside the measurement rather than beside it.
        utterance?.mark(UtteranceClock.Stage.ENDPOINT)
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
        heard: CompletableDeferred<Recogniser.Result?>,
    ) = object : Recogniser.Listener {
        override fun onReady() {
            clock.start()
            _state.value = _state.value.copy(listening = true)
        }

        override fun onLevel(level: Float) {
            _state.value = _state.value.copy(level = level)
        }

        override fun onPartial(text: String) {
            // The first hypothesis is a real latency stage, and the one W1.33 names
            // FIRST_PARTIAL. The mark is first-one-wins, so later partials cost nothing.
            clock.mark(UtteranceClock.Stage.FIRST_PARTIAL)
            _state.value = _state.value.copy(partial = text)
        }

        override fun onResult(result: Recogniser.Result) {
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
        heard: Recogniser.Result?,
        clock: UtteranceClock?,
        type: MessageType,
    ) {
        val text = heard?.text?.let(::bestReading) ?: nextTemplateText() ?: return
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
                messages = (_state.value.messages + entry).takeLast(MAX_ON_SCREEN),
                metrics = metricsFrom(clock, sent.wireBytes, heard),
                queued = session.queuedCount,
                partial = null,
                speechNote = speechNote(heard),
            )
        // isStarted: a press where the microphone never opened has no MIC mark, and
        // toTrace refuses to invent one.
        if (heard != null && clock != null && clock.isStarted) {
            _traces.value = (_traces.value + clock.toTrace(frameBytes = sent.wireBytes)).takeLast(MAX_TRACES)
        }
    }

    /**
     * The transcription, repaired against the alert lexicon **only where that is safe**.
     *
     * `docs/ASR.md` section 3.4 wanted contextual biasing, and it cannot be had: sherpa-onnx
     * applies a hotwords file only under `modified_beam_search`, which is a transducer beam
     * search, and IndicConformer is published as a NeMo CTC graph. The shipped library says
     * so itself — *"modified_beam_search if you provide --hotwords-file"*. So the lexicon
     * cannot reach the decoder and can only repair its output.
     *
     * Repairing output is dangerous, and [LexiconCorrector] has a test asserting exactly how:
     * खाता ("eats") sits one edit from खाना ("food") and gets rewritten, because eighty
     * distress terms cannot tell the class what else is a word in Hindi. Applied to
     * free speech it would damage more than it fixes.
     *
     * So it is gated. A correction is adopted **only when it turns a sentence that matched
     * no template into one that does** — the raw reading was not a known sentence and the
     * repaired one is, which is evidence rather than a guess. Everything else is transmitted
     * exactly as heard. That confines the mechanism to the case it is good at, where the
     * payoff is also largest: a matched template travels as one byte and is rendered in the
     * receiver's own language, so repairing it fixes the sentence at both ends at once.
     */
    private fun bestReading(raw: String): String {
        val corrector = corrector ?: return raw
        if (templates.match(raw, language, recogniserConfident = true) != null) return raw
        val repaired = corrector.correct(raw)
        if (!repaired.changed) return raw
        return if (templates.match(repaired.text, language, recogniserConfident = true) != null) {
            repaired.text
        } else {
            raw
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
     * Every figure is measured from the **release**, not from the microphone opening.
     *
     * This was wrong and it flattered nothing -- it made the project look three to four
     * times worse than it is. STT ran from the first sample, so a three-second sentence
     * contributed three seconds to a number labelled recognition latency: 4 116 ms before
     * the sliding window and 3 330 ms after, when the decode itself had gone from about
     * 1 116 ms to about 330 ms. The improvement was real and invisible.
     *
     * ISRO's wording settles it: "the time delay between the Words said and STT
     * completion". The words are said when the operator lets go. So `STT` is release to
     * transcription, `LINK` is transcription to the frame leaving, and `TOTAL` is release to
     * transmitted -- the wait an operator actually experiences.
     *
     * All three stay absent unless something was recognised, and absent is drawn as a dash:
     * the time a recogniser spends refusing an utterance is not recognition latency.
     */
    private fun metricsFrom(
        clock: UtteranceClock?,
        wireBytes: Int,
        heard: Recogniser.Result?,
    ): BandFMetrics {
        val previous = _state.value.metrics
        if (clock == null || heard == null) {
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
                realTimeFactor = null,
                cpuCores = null,
                cpuCoreCount = null,
                lastFrameBytes = wireBytes,
                // No audio was recorded, so the ratio falls back to the protocol convention.
                audioMillis = null,
            )
        }
        val endpoint = clock.elapsedMillis(UtteranceClock.Stage.ENDPOINT)
        val final = clock.elapsedMillis(UtteranceClock.Stage.FINAL)
        val tx = clock.elapsedMillis(UtteranceClock.Stage.TX)
        return previous.copy(
            sttMillis = if (final != null && endpoint != null) final - endpoint else null,
            linkMillis = if (tx != null && final != null) tx - final else null,
            totalMillis = if (tx != null && endpoint != null) tx - endpoint else null,
            realTimeFactor = heard.realTimeFactor,
            cpuCores = coresSincePress(),
            cpuCoreCount = Runtime.getRuntime().availableProcessors(),
            lastFrameBytes = wireBytes,
            audioMillis = heard.audioMillis,
        )
    }

    /**
     * Processor time this utterance used, counted in **cores**.
     *
     * `Process.getElapsedCpuTime` is this process's own CPU milliseconds, so no assumption
     * about the kernel's tick rate is needed and nothing else on the handset is counted.
     * Divided by elapsed wall time from the press, which is the interval an operator would
     * point at.
     *
     * The value exceeds 1.0 whenever more than one core was busy, and that is not an error:
     * the decoder is multi-threaded, so 2.6 means two and a half cores. This used to be
     * reported as a percentage of one core, which is the convention `top` uses and which
     * put "CPU 107 %" on the strip. That reads as 107 % of the handset, which is
     * impossible, and a number that looks broken is worse than no number on the strip a
     * jury photographs. Same measurement, divided by a hundred, and shown beside the core
     * count so it has a scale — the count is *displayed* rather than divided out, because
     * scaling by a figure the reader cannot see answers nothing.
     *
     * This is **not** the Efficiency criterion's number. That one asks for CPU during *idle
     * listening*, which is a different measurement in a different state and belongs in the
     * bench scorecard.
     */
    private fun coresSincePress(): Double? {
        if (wallAtPressMillis == 0L) return null
        val wall = SystemClock.elapsedRealtime() - wallAtPressMillis
        val cpu = Process.getElapsedCpuTime() - cpuAtPressMillis
        return if (wall > 0 && cpu >= 0) cpu.toDouble() / wall else null
    }

    /** What the screen says about the last attempt at speech. Null when it simply worked. */
    private fun speechNote(heard: Recogniser.Result?): String? =
        when {
            heard != null -> null
            speech == null ->
                "No speech model on this handset. Sent a template."
            // Capitalised and used as the whole sentence. Prefixing it read as "Heard
            // nothing: nothing recognised", which is the same fact said twice.
            lastSpeechProblem != null ->
                lastSpeechProblem!!.replaceFirstChar(Char::uppercase) + ". Sent a template."
            else -> null
        }

    /**
     * Chosen from the band B menu or the language screen.
     *
     * Choosing a language also **asks the platform for its speech model**, which is the fix
     * for the failure this most often produced on a handset: Hindi answered "language pack
     * not installed" and nothing in the application could do anything about it.
     */
    fun onLanguageChosen(code: String) {
        language = Language.entries.firstOrNull { it.code == code } ?: return
        // The session packs and matches in this language too. Left at Hindi, a Tamil
        // sentence missed every template and went as raw UTF-8 in a frame marked Hindi.
        session.language = language
        ensurePackFor(language)
        if (mode == UnitPreferences.MODE_PHONE) startOpenLine()
        refresh()
    }

    /**
     * Whether this unit can turn speech into words yet, and what to say when it cannot.
     *
     * The recogniser's language packs are fetched by `tools/fetch_models.py`, not by an
     * in-application download button: they are hundreds of megabytes each, constraint C2
     * allows them to be fetched "once during setup" and never at runtime, and a 30 MB
     * installer target means they cannot be bundled either.
     */
    private fun ensurePackFor(target: Language) {
        corrector = lexicons(target.code)?.let { LexiconCorrector(it) }
        val sherpa = speech as? SherpaSpeech
        val installed = speech?.isReady(target.code) == true

        _state.value =
            _state.value.copy(
                languages = languageOptions(),
                speechNote = packNote(target, installed, sherpa?.isLoaded(target.code) ?: installed),
            )
        if (!installed) return

        // Loading a 130-190 MB graph takes seconds, and it is paid here -- on the language
        // change, while nobody is speaking -- rather than on the first press. The note is
        // rewritten when it lands so the operator knows when the unit can actually hear.
        speaker?.preload(target.code)
        sherpa?.preload(target.code) {
            if (target != language) return@preload
            _state.value =
                _state.value.copy(
                    languages = languageOptions(),
                    speechNote = packNote(target, installed = true, loaded = true),
                )
        }
    }

    private fun packNote(
        target: Language,
        installed: Boolean,
        loaded: Boolean,
    ): String? =
        when {
            !installed ->
                "No ${displayNameFor(target)} speech model on this handset. " +
                    "Transmit sends a template."

            !loaded -> "Loading the ${displayNameFor(target)} model…"
            else -> null
        }

    /** The languages this unit can render, and what it can do with each of them. */
    fun languageOptions(): List<LanguageOption> = languageOptions(speech, speaker)

    /**
     * A condition that does not clear itself when the net recovers.
     *
     * Sustained forgery is the only one so far, and it must survive a [refresh] — the link
     * coming back is not evidence that the attempt stopped.
     */
    private var sticky: EngineState.Degraded.Reason? = null

    // ── the unit's own name, mode and presence ────────────────────────────────

    /** The operator renamed this unit. Everyone in range hears the new name at once. */
    fun setUnitName(name: String) {
        preferences?.setUnitName(name)
        unitName = preferences?.unitName ?: name.trim().ifEmpty { identity.displayName }
        refresh()
        scope.launch { sendPresence() }
    }

    /**
     * Which roads routine traffic takes. Alerts are unaffected — see [Link.send].
     *
     * Applied to the mesh at once and stored for next time. The stored set is the
     * sanitised one, so the screen, the mesh and the disk never disagree about which
     * roads are on.
     */
    fun setRoads(selected: Collection<String>) {
        val clean = Road.sanitise(selected)
        preferences?.setRoads(clean)
        mesh.roads = clean
        refresh()
    }

    /** The roads routine traffic takes now. */
    val roads: Set<String> get() = mesh.roads ?: Road.ALL

    /** How many relay hops a message from this unit may travel. Clamped by the session. */
    fun setTtl(hops: Int) {
        session.ttl = hops
        preferences?.setTtl(session.ttl)
        refresh()
    }

    val ttl: Int get() = session.ttl

    /** Push-to-talk or the open line. Persisted, and applied at once. */
    fun setMode(next: String) {
        val clean = if (next == UnitPreferences.MODE_PHONE) UnitPreferences.MODE_PHONE else UnitPreferences.MODE_PTT
        if (clean == mode) return
        preferences?.setMode(clean)
        mode = clean
        if (clean == UnitPreferences.MODE_PHONE) startOpenLine() else stopOpenLine()
        refresh()
        scope.launch { sendPresence() }
    }

    private fun onSignal(signal: Signal) {
        roster.signal(signal)
        locator.onSignal(signal)
        if (signal.src == chirpRequester && foundBeacon.isRunning) {
            // The searcher's distance, on the assumed model: uncalibrated, but a rate
            // needs only to rise as they come, and the same scale as their own siren.
            val rssi = chirpRange.offer(signal.rssi, signal.atMillis)
            chirpSignalAtMillis = signal.atMillis
            val metres = Locator.distanceFor(rssi, Locator.referenceFor(signal.txPower))
            foundBeacon.proximity = Locator.proximityForMetres(metres)
        }
    }

    private fun onPresence(received: Session.Received.Presence) {
        val now = SystemClock.elapsedRealtime()
        synchronized(heardFrom) { heardFrom[received.from] = now }
        roster.presence(received.from, received.presence, now)
        locator.onTargetPresence(received.from, received.presence, now)
        refresh()
    }

    /**
     * Another unit wants to find this one, or has stopped looking.
     *
     * Answered without asking anybody: the request came sealed under the net's key from a
     * unit on this net, and a colleague trying to reach an operator in a flood is not a
     * moment for a consent dialog. Beaconing stops by itself after [BEACON_MILLIS].
     */
    private fun onLocate(received: Session.Received.Locate) {
        val now = SystemClock.elapsedRealtime()
        synchronized(heardFrom) { heardFrom[received.from] = now }
        roster.heard(received.from, now)
        if (received.locate.target != identity.src) return
        if (received.locate.start) {
            beaconingUntilMillis = now + BEACON_MILLIS
            positions?.start()
            // The searcher wants to follow a sound. Held for a bounded time and renewed
            // by every request, so the chirp ends when the requests do. Every request
            // from the searcher carries its current wish, so a renewal never silences a
            // chirp the searcher still wants.
            if (received.locate.sound) {
                chirpUntilMillis = now + CHIRP_MILLIS
                if (chirpRequester != received.from) {
                    chirpRange.clear()
                    chirpRequester = received.from
                }
                foundBeacon.start()
            } else {
                stopChirping()
            }
            // Answered at once, and the answer says whether this unit is chirping.
            scope.launch { sendPresence() }
        } else {
            beaconingUntilMillis = 0L
            stopChirping()
            if (!locator.isActive) positions?.stop()
        }
    }

    private fun stopChirping() {
        chirpUntilMillis = 0L
        chirpRequester = -1
        chirpRange.clear()
        foundBeacon.stop()
    }

    /**
     * The one request this unit makes of its target: beacon, and sound or not.
     *
     * Sent when the sound wish changes, every [CHIRP_RENEW_TICKS] while it is yes -- the
     * chirp holds [CHIRP_MILLIS] per request, so a target that misses three in a row still
     * does not fall silent -- and every [LOCATE_RENEW_TICKS] regardless, so a target that
     * missed the first request, or whose beacon timer ran out, is still beaconing for as
     * long as somebody is looking. One request rather than two kinds: the first version
     * renewed the beacon with a request that said nothing about sound, and the target
     * read that as "stop chirping" once a minute.
     */
    private suspend fun askTarget(
        tick: Long,
        force: Boolean = false,
    ) {
        val target = locator.target ?: return
        val want = locator.wantsTheirSound
        val due =
            force ||
                want != askedTheirSound ||
                (want && tick % CHIRP_RENEW_TICKS == 0L) ||
                tick % LOCATE_RENEW_TICKS == 0L
        if (!due) return
        askedTheirSound = want
        session.sendLocate(Locate(target, true, sound = want), System.currentTimeMillis())
    }

    private suspend fun sendPresence() {
        val now = SystemClock.elapsedRealtime()
        val beaconing = now < beaconingUntilMillis
        val position = if (beaconing || locator.isActive) positions?.current() else null
        session.sendPresence(
            Presence(
                name = unitName,
                position = position,
                beaconing = beaconing,
                openLine = mode == UnitPreferences.MODE_PHONE,
                chirping = foundBeacon.isRunning,
                batteryPercent = batteryPercent(),
            ),
        )
    }

    private fun batteryPercent(): Int =
        runCatching {
            (wifiContext?.getSystemService(android.content.Context.BATTERY_SERVICE) as? android.os.BatteryManager)
                ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrNull()?.takeIf { it in 0..100 } ?: Presence.BATTERY_UNKNOWN

    /** The once-a-second work: presence, beacons, and the locate screen's clock. */
    private suspend fun everySecond(tick: Long) {
        val now = SystemClock.elapsedRealtime()
        // The clock on the locate screen runs whether or not there is a link: "signal
        // lost" is the one thing it must be able to say when the link is gone.
        if (locator.isActive) locator.tick()
        // A chirp whose searcher has not been heard for a while goes back to its slowest:
        // the rate says how near they are, and nobody knows.
        if (foundBeacon.isRunning && now - chirpSignalAtMillis > Locator.LOST_AFTER_MILLIS) foundBeacon.proximity = 0f
        if (chirpUntilMillis != 0L && now > chirpUntilMillis) stopChirping()
        if (mesh.state.value != LinkState.CONNECTED) return
        val beaconing = now < beaconingUntilMillis
        if (!beaconing && beaconingUntilMillis != 0L) {
            beaconingUntilMillis = 0L
            stopChirping()
            if (!locator.isActive) positions?.stop()
        }
        if (locator.isActive) askTarget(tick)
        // Every second while somebody is walking towards this unit, every ten otherwise.
        if (beaconing || locator.isActive || tick % PRESENCE_EVERY_TICKS == 0L) sendPresence()
    }

    // ── locating a unit ──────────────────────────────────────────────────────

    /** Everyone this run has heard of, for the locate list, nearest first. */
    fun unitsEverHeard(): List<org.itantra.app.ui.UnitInfo> = roster.everyone(SystemClock.elapsedRealtime())

    fun startLocating(src: Int) {
        locator.start(src, roster.nameOf(src))
        positions?.start()
        scope.launch {
            // Three times over two seconds: the request is one advertisement, and the
            // first one is the easiest to miss. Each carries the sound wish, which is a
            // preference kept from the last search.
            repeat(3) {
                askTarget(tick = 0L, force = true)
                delay(LOCATE_REPEAT_MILLIS)
            }
        }
    }

    fun stopLocating() {
        val src = locator.target
        locator.stop()
        askedTheirSound = false
        if (beaconingUntilMillis == 0L) positions?.stop()
        if (src != null) scope.launch { session.sendLocate(Locate(src, false), System.currentTimeMillis()) }
    }

    /** Which handset sounds during the search. A change reaches the target at once. */
    fun setLocateSound(from: SoundFrom) {
        locator.setSound(from)
        scope.launch { askTarget(tick = 0L, force = true) }
    }

    // ── the open line ────────────────────────────────────────────────────────

    private fun startOpenLine() {
        val sherpa = speech as? SherpaSpeech
        if (sherpa == null) {
            _state.value = _state.value.copy(speechNote = "No recogniser on this handset; the open line cannot listen.")
            return
        }
        openLinePaused = false
        val started =
            sherpa.startOpenLine(
                language.code,
                object : SherpaSpeech.OpenLineListener {
                    override fun onLevel(level: Float) {
                        _state.value = _state.value.copy(level = level)
                    }

                    override fun onPartial(text: String) {
                        _state.value = _state.value.copy(partial = text)
                    }

                    override fun onUtterance(result: Recogniser.Result) {
                        scope.launch { onOpenLineUtterance(result) }
                    }

                    override fun onProblem(reason: String) {
                        _state.value =
                            _state.value.copy(
                                listening = false,
                                speechNote = reason.replaceFirstChar(Char::uppercase) + ".",
                            )
                    }
                },
            )
        _state.value =
            _state.value.copy(
                transmitting = false,
                listening = started,
                partial = null,
                speechNote =
                    if (started) {
                        null
                    } else {
                        "No ${displayNameFor(language)} speech model on this handset. The open line cannot listen."
                    },
            )
    }

    private fun stopOpenLine() {
        (speech as? SherpaSpeech)?.stopOpenLine()
        openLinePaused = false
        _state.value = _state.value.copy(listening = false, transmitting = false, partial = null, level = 0f)
    }

    private fun toggleOpenLineHold() {
        val sherpa = speech as? SherpaSpeech ?: return
        openLinePaused = !openLinePaused
        sherpa.setOpenLineMuted(openLinePaused)
        _state.value =
            _state.value.copy(
                openLinePaused = openLinePaused,
                listening = !openLinePaused,
                partial = null,
                level = 0f,
            )
    }

    /**
     * A sentence the open line heard. Sent, unless it is the handset's own voice.
     *
     * The echo canceller catches most of what the speaker says; what gets through is
     * caught here by content. Anything that reads like the message being spoken, or one
     * spoken a moment ago, is the room hearing this handset, not the operator.
     */
    private suspend fun onOpenLineUtterance(result: Recogniser.Result) {
        val echo = speakingText ?: lastSpokenText?.takeIf { SystemClock.elapsedRealtime() < lastSpokenUntilMillis }
        if (echo != null && similarity(result.text, echo) >= ECHO_SIMILARITY) {
            Log.i(TAG, "open line heard the handset's own voice; dropped")
            _state.value = _state.value.copy(partial = null)
            return
        }
        _state.value = _state.value.copy(partial = null)
        send(result, null, MessageType.TEXT)
    }

    private fun degrade(reason: EngineState.Degraded.Reason) {
        sticky = reason
        _state.value = _state.value.copy(degraded = reason)
    }

    /** Units heard from inside [PEER_MEMORY_MILLIS]. See [heardFrom]. */
    private fun unitsOnChannel(): Int {
        val now = SystemClock.elapsedRealtime()
        return synchronized(heardFrom) {
            heardFrom.entries.removeAll { now - it.value > PEER_MEMORY_MILLIS }
            heardFrom.size
        }
    }

    private fun refresh() {
        val now = SystemClock.elapsedRealtime()
        _state.value =
            _state.value.copy(
                // Units heard inside the presence window: what "here now" means.
                peerCount = roster.presentCount(now),
                units = roster.present(now),
                unitName = unitName,
                mode = mode,
                openLinePaused = openLinePaused,
                linkUp = mesh.state.value == LinkState.CONNECTED,
                language = displayNameFor(language),
                languageCode = language.code,
                languages = languageOptions(),
                queued = session.queuedCount,
                degraded = sticky ?: netTrouble(),
            )
    }

    /**
     * What is wrong with the channel.
     *
     * "No other unit paired" is gone, because pairing is gone. On a broadcast channel the
     * only thing that can be wrong with the radio is that it is switched off; a channel
     * with nobody else on it is not a fault, it is a quiet channel, and telling an operator
     * to go and pair something would now be advice for a problem they do not have.
     */
    private fun netTrouble(): EngineState.Degraded.Reason? {
        // A net that is up is up, whichever radio carries it: a unit on a hotspot or a
        // Wi-Fi Direct group with Bluetooth off is on the channel, and a banner telling
        // its operator to switch Bluetooth on would be advice for a problem they do not
        // have. Only when nothing carries is the radio that is off worth naming.
        if (mesh.state.value == LinkState.CONNECTED) return null
        val bluetooth = adapter
        if (bluetooth == null || !isEnabled(bluetooth)) {
            return EngineState.Degraded.Reason.BLUETOOTH_OFF
        }
        return EngineState.Degraded.Reason.LINK_DOWN
    }

    /**
     * What each road is doing right now, for the settings screen.
     *
     * All three run at once: [MeshLink] sends every frame down every road that is on and
     * the replay window discards the duplicate. What the operator chooses is which roads
     * routine traffic takes — [setRoads] — and that is a switch on sending only. Every
     * road stays up and stays heard, and an alert goes down all of them regardless. The
     * screen used to present these as a *choice* with one entry and an empty click
     * handler — it named RFCOMM, omitted the two channels that need no pairing, and did
     * nothing when pressed. On demonstration day the two it omitted are the ones that
     * work, which is the reason `docs/TRANSPORT.md` section 8 gives for building them.
     *
     * RFCOMM is reported as a count rather than a state because it is one peer per bonded
     * handset: it is not "connected" or not, it is connected to some number of the units
     * in the room.
     */
    fun channels(): List<ChannelStatus> {
        val states = mesh.peerStates
        val on = roads
        val bonded = states.keys.filter { it != BROADCAST_PEER && it != WIFI_PEER }
        val bondedUp = bonded.count { states[it] == LinkState.CONNECTED }
        return listOf(
            ChannelStatus(
                id = BROADCAST_PEER,
                name = "Bluetooth LE broadcast",
                detail = "No pairing. Every unit with the app open is on it",
                state = states[BROADCAST_PEER],
                enabled = BROADCAST_PEER in on,
            ),
            ChannelStatus(
                id = WIFI_PEER,
                name = "Wi-Fi broadcast",
                detail = "A hotspot, a Wi-Fi Direct group, or any shared Wi-Fi. No data plan, no router",
                state = states[WIFI_PEER],
                enabled = WIFI_PEER in on,
            ),
            ChannelStatus(
                id = RFCOMM_CHANNEL,
                enabled = RFCOMM_CHANNEL in on,
                name = "Bluetooth Classic (RFCOMM)",
                detail =
                    if (bonded.isEmpty()) {
                        "Bonded handsets only. None bonded yet"
                    } else {
                        "Bonded handsets only. $bondedUp of ${bonded.size} connected"
                    },
                state =
                    when {
                        bonded.isEmpty() -> null
                        bondedUp > 0 -> LinkState.CONNECTED
                        else -> LinkState.DEGRADED
                    },
            ),
        )
    }

    /**
     * One road's live state.
     *
     * [state] is null when the channel is not running at all — no Wi-Fi context, or no
     * handset bonded — which is a different thing from running and not reaching anyone,
     * and the screen says so differently.
     */
    data class ChannelStatus(
        val id: String,
        val name: String,
        val detail: String,
        val state: LinkState?,
        /** Whether routine traffic goes down this road. Alerts always do. */
        val enabled: Boolean = true,
    )

    companion object {
        const val MAX_ON_SCREEN = 20

        /** How often the screen re-reads the roster. Cheap, and the numbers are live. */
        const val REFRESH_MILLIS = 1_000L

        /** Every fifth tick, the net is nudged: roads reconnected, the outbox flushed. */
        const val RECOVERY_EVERY_TICKS = 5L

        /** After the receive loop dies, how long before it is started again. */
        const val RECEIVE_RESTART_MILLIS = 200L

        /** The same refusal is shown again only after this long. */
        const val DROP_NOTE_REPEAT_MILLIS = 10_000L

        /** Presence every ten seconds when nobody is looking for anybody. */
        const val PRESENCE_EVERY_TICKS = 10L

        /** How long a unit beacons its position after one request. Renewed while somebody looks. */
        const val BEACON_MILLIS = 10 * 60_000L

        const val LOCATE_RENEW_TICKS = 60L

        /** A chirp request holds this long; the searcher renews it every [CHIRP_RENEW_TICKS] seconds. */
        const val CHIRP_MILLIS = 20_000L
        const val CHIRP_RENEW_TICKS = 5L
        const val LOCATE_REPEAT_MILLIS = 700L

        /** After the speaker finishes, how long its words still count as echo. */
        const val ECHO_GRACE_MILLIS = 3_000L

        /** Token overlap above which an open-line sentence is the handset's own voice. */
        const val ECHO_SIMILARITY = 0.5

        /** Overlap of the words in two sentences, 0..1, ignoring case and punctuation. */
        fun similarity(
            a: String,
            b: String,
        ): Double {
            fun tokens(s: String) =
                s.lowercase().split(Regex("\\s+")).map {
                    it.trim {
                            c ->
                        !c.isLetterOrDigit()
                    }
                }.filter { it.isNotEmpty() }.toSet()
            val x = tokens(a)
            val y = tokens(b)
            if (x.isEmpty() || y.isEmpty()) return 0.0
            return x.intersect(y).size.toDouble() / x.union(y).size
        }

        /** Long enough for the broadcast roads to open before the first hello goes out. */
        const val HELLO_AFTER_CONNECT_MILLIS = 750L

        /**
         * How long a release waits for the recogniser's final answer.
         *
         * Long enough for an on-device pass over a held sentence, short enough that a
         * recogniser which has stopped answering does not swallow the message -- the
         * template goes instead, and the screen says so.
         *
         * Raised from four seconds, which was too tight: the release now defers the stop
         * until the microphone has been open for a moment, so the wait begins later, and a
         * cold recognition session can take several seconds to return its first final
         * result. Four seconds turned a slow success into a template.
         */
        const val SPEECH_TIMEOUT_MILLIS = 8_000L

        /** Below this the transcription still goes, marked uncertain rather than dropped. */
        const val CONFIDENT_ABOVE = 0.6f

        /** Enough for the 100-utterance run docs/EVALUATION.md section 4 asks for. */
        const val MAX_TRACES = 200

        private const val TAG = "itantra-net"

        /** The broadcast channel's entry in the mesh. There is exactly one. */
        const val BROADCAST_PEER = Road.BLE

        /** The Wi-Fi channel's entry in the mesh. There is exactly one. */
        const val WIFI_PEER = Road.WIFI

        /**
         * RFCOMM's id on the settings screen, and the road its peers are added under. It
         * is not a mesh peer id: RFCOMM adds one peer per bonded handset, keyed by
         * Bluetooth address.
         */
        const val RFCOMM_CHANNEL = Road.RFCOMM

        /** How long a unit stays counted after its last transmission. */
        const val PEER_MEMORY_MILLIS = 60_000L

        /**
         * The ten languages, with what this handset can do with each.
         *
         * On the companion so that the screen has a list to show **before** the engine
         * exists — the band B menu is drawn from the moment the application opens, and a
         * chevron that opens an empty menu is worse than one that does nothing. With a null
         * recogniser the rows are still correct; they simply say nothing about speech.
         */
        fun languageOptions(
            speech: Recogniser?,
            speaker: Speaker? = null,
        ): List<LanguageOption> =
            Language.entries.map {
                LanguageOption(
                    code = it.code,
                    nativeName = displayNameFor(it),
                    englishName = it.name.lowercase().replaceFirstChar(Char::uppercase),
                    // Whether a voice is actually installed for this language, asked of
                    // the store rather than assumed either way.
                    canSpeak = speaker?.canSpeak(it.code) == true,
                    recognition =
                        when {
                            speech == null -> "no model installed"
                            speech.isReady(it.code) -> "installed on this handset"
                            else -> "no model installed"
                        },
                )
            }

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
