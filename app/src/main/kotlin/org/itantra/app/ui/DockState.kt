package org.itantra.app.ui

/**
 * What the dock is doing, derived from the engine and from nothing else.
 *
 * ## Why this is a function and not a piece of state
 *
 * The floor can be seized without this screen being touched. `PushToTalkKey`
 * (`MainActivity.kt:124`) is wired straight to the engine, and `VolumeKeyCapture` holds the
 * same path open with the screen off — so a press can arrive from hardware that the
 * composable never sees. The instant the dock keeps its own `pressed` boolean the two
 * disagree: the operator's thumb is on the volume key, the engine is transmitting, and the
 * circle on screen is sitting idle.
 *
 * So there is no local state here at all. [dockStateOf] is a pure function of
 * [OperatingState], the same way the rest of the screen is, and however the floor was
 * seized the dock has already been told.
 *
 * ## The precedence, and the one case that looks like a bug
 *
 * [DockState.BUSY] and `transmitting` genuinely overlap. Nothing in `MessageEngine` ever
 * calls `Speaker.stop()` — only `speak`, `preload` and `close` — so a message can still be
 * playing out loud when the operator seizes the floor. When that happens the operator wins
 * and the dock shows what *their* handset is doing, because the person holding it needs to
 * know whether it is listening to them far more than they need to be told about audio they
 * can already hear.
 */
internal enum class DockState {
    /** Nothing happening. The circle is an invitation. */
    IDLE,

    /**
     * The floor is held and the microphone is not open yet.
     *
     * A state of its own, and the reason is the whole design of the control: anything said
     * in this gap is not in the audio at all. The circle fills so the press is acknowledged,
     * and it carries **no motion** — see the note on [LIVE].
     */
    SEIZED,

    /**
     * The microphone is open and recording.
     *
     * Motion starts here and only here. The halos and the equaliser are how an operator
     * tells [SEIZED] from this one at a glance, without reading — which is why "tidying" a
     * spinner into [SEIZED] would be a safety defect rather than a style change. It would
     * tell someone to start talking into a microphone that is not listening yet.
     */
    LIVE,

    /** Another unit's message is being spoken aloud. The channel is busy. */
    BUSY,

    /**
     * Full duplex. The circle keeps its position and loses its affordance.
     *
     * Unreachable today: `MessageEngine` sets `mode` to the literal `"PTT"` in both places
     * it is set, and `DuplexPolicy` — written and tested in `core-audio` — has no caller.
     * Drawn anyway, from the state, so the screen is already correct on the day the engine
     * reports it, and covered by [dockStateOf]'s tests so it is not merely hopeful code.
     */
    PHONE,
}

/**
 * The dock's state, from the engine's state.
 *
 * Pure, total, and unit-tested — which is the only automated defence this screen has, since
 * `:app` carries no Compose UI-test dependency.
 */
internal fun dockStateOf(state: OperatingState): DockState =
    when {
        state.mode.equals("Phone", ignoreCase = true) -> DockState.PHONE
        state.transmitting && state.listening -> DockState.LIVE
        state.transmitting -> DockState.SEIZED
        state.speakingFrom != null -> DockState.BUSY
        else -> DockState.IDLE
    }
