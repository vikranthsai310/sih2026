package org.itantra.audio

/**
 * The single state machine that owns the device. Everything else observes it.
 *
 * ```
 *   INITIALISING -> READY -> LISTENING -> RECOGNISING -> TRANSMITTING
 *                     ^                                        |
 *                     +----------------------------------------+
 *                     +---- RECEIVING -> SPEAKING -------------+
 *
 *   DEGRADED  (mic lost, link down, thermal, storage) -> recovers to READY
 * ```
 *
 * [Degraded] is a first-class state, always visible in the interface, and always
 * carrying a reason. A system that silently stops working is worse than one that
 * says it has stopped. See `docs/ARCHITECTURE.md` section 4.
 */
sealed interface EngineState {
    /** Loading models. Transmit stays disabled until this ends — risk T-11. */
    data object Initialising : EngineState

    data object Ready : EngineState

    data object Listening : EngineState

    data object Recognising : EngineState

    data object Transmitting : EngineState

    data object Receiving : EngineState

    data object Speaking : EngineState

    /** Recoverable. The reason is shown to the operator verbatim. */
    data class Degraded(val reason: Reason) : EngineState {
        enum class Reason(val message: String) {
            MICROPHONE_UNAVAILABLE("Microphone in use by a call"),

            /**
             * The radio itself is off, which no amount of reconnecting fixes.
             *
             * Separated from [LINK_DOWN] because the two look identical on screen and need
             * opposite responses: one is waited out, the other needs a person to act. An
             * operator told "reconnecting automatically" while Bluetooth is switched off
             * waits for something that will never happen.
             */
            BLUETOOTH_OFF("Bluetooth is off"),

            /** The radio is up and nothing is on the net yet — nobody bonded, or nobody else running. */
            NO_PEERS("No other unit paired"),

            /** Without BLUETOOTH_CONNECT there is no radio to fail on, so this is its own state. */
            PERMISSION_DENIED("Nearby devices permission refused"),
            LINK_DOWN("Link down — reconnecting"),
            THERMAL("Thermal — reduced to 1 thread"),
            STORAGE_FULL("Storage full"),
            TEMPLATE_MISMATCH("Template mismatch — check the other unit"),
            MODELS_MISSING("Language pack missing or corrupt"),
        }
    }

    /**
     * Whether the transmit control may be enabled.
     *
     * False while initialising, so the one-to-two second cold-start model load is
     * never paid on a key press (risk T-11), and false while degraded.
     */
    val canTransmit: Boolean
        get() =
            when (this) {
                Ready, Listening -> true
                else -> false
            }

    /** Whether the operator should see a banner. */
    val isDegraded: Boolean get() = this is Degraded
}

/**
 * Legal transitions. Kept explicit so an illegal one is a test failure rather than a
 * subtle bug in the field.
 */
object EngineTransitions {
    private val ALLOWED: Map<String, Set<String>> =
        mapOf(
            key(EngineState.Initialising) to setOf(key(EngineState.Ready), DEGRADED),
            key(EngineState.Ready) to setOf(key(EngineState.Listening), key(EngineState.Receiving), DEGRADED),
            key(EngineState.Listening) to
                setOf(key(EngineState.Recognising), key(EngineState.Ready), key(EngineState.Receiving), DEGRADED),
            key(EngineState.Recognising) to setOf(key(EngineState.Transmitting), key(EngineState.Ready), DEGRADED),
            key(EngineState.Transmitting) to setOf(key(EngineState.Ready), key(EngineState.Listening), DEGRADED),
            key(EngineState.Receiving) to setOf(key(EngineState.Speaking), key(EngineState.Ready), DEGRADED),
            key(EngineState.Speaking) to setOf(key(EngineState.Ready), key(EngineState.Listening), DEGRADED),
            // Degraded always recovers to Ready, never straight back into work.
            DEGRADED to setOf(key(EngineState.Ready)),
        )

    private const val DEGRADED = "Degraded"

    private fun key(state: EngineState): String =
        if (state is EngineState.Degraded) DEGRADED else state::class.simpleName ?: "?"

    fun isLegal(
        from: EngineState,
        to: EngineState,
    ): Boolean = ALLOWED[key(from)]?.contains(key(to)) == true
}
