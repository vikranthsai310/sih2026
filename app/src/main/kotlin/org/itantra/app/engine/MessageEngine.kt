package org.itantra.app.engine

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.itantra.app.platform.NodeIdentity
import org.itantra.app.ui.LoggedMessage
import org.itantra.app.ui.OperatingState
import org.itantra.audio.EngineState
import org.itantra.link.LinkState
import org.itantra.link.MeshLink
import org.itantra.link.RfcommLink
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
) {
    private val mesh = MeshLink(scope)

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
     * Brings up a link to every bonded handset.
     *
     * One side of each pair dials and the other listens, decided by comparing node ids so
     * neither has to be told. A handset that is not bonded in Android's Bluetooth settings
     * is not reachable — pairing at the operating-system level is a prerequisite the
     * pre-flight checklist already names.
     */
    fun start() {
        val bluetooth = adapter ?: return degrade(EngineState.Degraded.Reason.LINK_DOWN)
        val devices = runCatching { bondedDevices() }.getOrDefault(emptyList())

        for (device in devices) {
            val peerId = runCatching { device.address }.getOrNull() ?: continue
            val theirSrc = NodeIdentity.srcFor(peerId)

            // A unit whose id collides with ours would drop our frames as its own. Better
            // to leave it out of the net and say so than to appear connected and silent.
            if (identity.collidesWith(theirSrc)) continue

            val role =
                if (identity.dials(theirSrc)) RfcommLink.Role.CLIENT else RfcommLink.Role.HOST
            mesh.addPeer(
                peerId,
                RfcommLink(
                    adapter = bluetooth,
                    role = role,
                    target = if (role == RfcommLink.Role.CLIENT) device else null,
                    scope = scope,
                ),
            )
        }

        scope.launch {
            mesh.connect()
            watchLink()
        }
        scope.launch { receiveLoop() }
        refresh()
    }

    fun stop() {
        scope.launch { mesh.disconnect() }
    }

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
     * was said while the control was held, so it goes when the operator lets go.
     */
    fun onTransmit(held: Boolean) {
        _state.value = _state.value.copy(transmitting = held)
        if (held) return
        scope.launch { sendNextTemplate(MessageType.TEXT) }
    }

    fun onAlert() {
        scope.launch { sendNextTemplate(MessageType.ALERT) }
    }

    private suspend fun sendNextTemplate(type: MessageType) {
        if (templateIds.isEmpty()) return
        val id = templateIds[nextTemplate % templateIds.size]
        nextTemplate++
        val text = templates.render(id, language) ?: return

        val sent = session.send(text, confident = true, type = type, nowMillis = System.currentTimeMillis())

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
            )
        _state.value =
            _state.value.copy(
                messages = (listOf(entry) + _state.value.messages).take(MAX_ON_SCREEN),
                metrics = _state.value.metrics.copy(lastFrameBytes = sent.wireBytes),
                queued = session.queuedCount,
            )
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

    private fun degrade(reason: EngineState.Degraded.Reason) {
        _state.value = _state.value.copy(degraded = reason)
    }

    private fun refresh() {
        _state.value =
            _state.value.copy(
                peerCount = mesh.connectedCount,
                linkUp = mesh.state.value == LinkState.CONNECTED,
                language = displayNameFor(language),
                queued = session.queuedCount,
                degraded =
                    when {
                        mesh.peerCount == 0 -> EngineState.Degraded.Reason.LINK_DOWN
                        mesh.state.value != LinkState.CONNECTED -> EngineState.Degraded.Reason.LINK_DOWN
                        else -> null
                    },
            )
    }

    private companion object {
        const val MAX_ON_SCREEN = 20

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
