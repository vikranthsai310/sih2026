package org.itantra.app.platform

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.itantra.link.Road
import org.itantra.link.Session

private val Context.unitPreferences by preferencesDataStore(name = "itantra_unit")

/**
 * What an operator sets about this handset, kept across restarts: what it is called,
 * which mode it is in, how large the text is, and how it uses the radio -- whether it
 * keeps relaying with the screen off, which roads routine traffic takes, and how many
 * hops a message of its own may travel.
 *
 * Read once, at construction, with a blocking read -- a few bytes from a file that exists
 * before the first screen is drawn -- and written in the background thereafter. The values
 * are held here as well so a caller never waits on the store to answer a question it asked
 * a moment ago.
 */
class UnitPreferences(
    private val context: Context,
    /** The name used until the operator sets one: the handset's model. */
    private val defaultName: String,
) {
    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile
    var unitName: String = defaultName
        private set

    @Volatile
    var mode: String = MODE_PTT
        private set

    @Volatile
    var textScale: Float = 1f
        private set

    /**
     * Whether the engine keeps running with the screen off and the application gone, so
     * this handset goes on hearing and rebroadcasting for units out of each other's range.
     *
     * Off by default. On, it costs battery -- the radios stay awake and so does the
     * processor -- and that is a trade an operator makes knowingly, not one the
     * application makes for them.
     */
    @Volatile
    var relayMode: Boolean = false
        private set

    /**
     * The roads routine traffic may take. Always a non-empty subset of [Road.ALL]: a
     * stored set naming nothing that exists reads back as everything, because a screen
     * showing every road off while the mesh sends anyway is a lie in the safe direction,
     * and still a lie.
     */
    @Volatile
    var roads: Set<String> = Road.ALL
        private set

    /** Relay hops for a message this unit sends. [Session.MIN_TTL]..[Session.MAX_TTL]. */
    @Volatile
    var ttl: Int = Session.DEFAULT_TTL
        private set

    init {
        runCatching {
            runBlocking {
                val prefs = context.unitPreferences.data.first()
                prefs[NAME]?.takeIf { it.isNotBlank() }?.let { unitName = it }
                prefs[MODE]?.let { if (it == MODE_PHONE) mode = MODE_PHONE }
                prefs[TEXT_SCALE]?.let { textScale = it.coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE) }
                prefs[RELAY_MODE]?.let { relayMode = it }
                prefs[ROADS]?.let { roads = Road.sanitise(it) }
                prefs[TTL]?.let { ttl = it.coerceIn(Session.MIN_TTL, Session.MAX_TTL) }
            }
        }
    }

    fun setUnitName(name: String) {
        val clean = name.trim().ifEmpty { defaultName }
        unitName = clean
        scope.launch { context.unitPreferences.edit { it[NAME] = clean } }
    }

    fun setMode(next: String) {
        val clean = if (next == MODE_PHONE) MODE_PHONE else MODE_PTT
        mode = clean
        scope.launch { context.unitPreferences.edit { it[MODE] = clean } }
    }

    fun setTextScale(scale: Float) {
        val clean = scale.coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE)
        textScale = clean
        scope.launch { context.unitPreferences.edit { it[TEXT_SCALE] = clean } }
    }

    fun setRelayMode(on: Boolean) {
        relayMode = on
        scope.launch { context.unitPreferences.edit { it[RELAY_MODE] = on } }
    }

    /** Stores the sanitised set, so what is on disk is what [roads] will say. */
    fun setRoads(selected: Collection<String>) {
        val clean = Road.sanitise(selected)
        roads = clean
        scope.launch { context.unitPreferences.edit { it[ROADS] = clean } }
    }

    fun setTtl(hops: Int) {
        val clean = hops.coerceIn(Session.MIN_TTL, Session.MAX_TTL)
        ttl = clean
        scope.launch { context.unitPreferences.edit { it[TTL] = clean } }
    }

    companion object {
        const val MODE_PTT = "PTT"
        const val MODE_PHONE = "Phone"

        /** Below the system size and up to twice it: `docs/UX.md` rule 9 names 200 %. */
        const val MIN_TEXT_SCALE = 0.85f
        const val MAX_TEXT_SCALE = 2.0f
        const val TEXT_SCALE_STEP = 0.15f

        private val NAME = stringPreferencesKey("unit_name")
        private val MODE = stringPreferencesKey("mode")
        private val TEXT_SCALE = floatPreferencesKey("text_scale")
        private val RELAY_MODE = booleanPreferencesKey("relay_mode")
        private val ROADS = stringSetPreferencesKey("roads")
        private val TTL = intPreferencesKey("ttl")
    }
}
