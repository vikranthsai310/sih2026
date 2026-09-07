package org.itantra.app.platform

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val Context.unitPreferences by preferencesDataStore(name = "itantra_unit")

/**
 * The three things an operator sets about this handset, kept across restarts: what it is
 * called, which mode it is in, and how large the text is.
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

    init {
        runCatching {
            runBlocking {
                val prefs = context.unitPreferences.data.first()
                prefs[NAME]?.takeIf { it.isNotBlank() }?.let { unitName = it }
                prefs[MODE]?.let { if (it == MODE_PHONE) mode = MODE_PHONE }
                prefs[TEXT_SCALE]?.let { textScale = it.coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE) }
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
    }
}
