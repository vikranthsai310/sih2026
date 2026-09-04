package org.itantra.app.platform

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.itantra.proto.EpochCounter

private val Context.epochStore by preferencesDataStore(name = "itantra_epoch")

/**
 * Durable storage for `EPOCH`, backed by DataStore. The other half of task **W6.10**.
 *
 * ## Why the writes are blocking
 *
 * [EpochCounter] persists the new epoch **before** handing it out, and that ordering is
 * the entire safety property: if the process dies having transmitted under an epoch that
 * was never recorded, the next start reuses it, and nonce reuse under a fixed key
 * destroys AES-GCM rather than merely weakening it.
 *
 * A suspending write would let the caller continue before the value was durable, which
 * silently reopens exactly that window. So [write] blocks until DataStore has committed.
 * It is called twice in the life of a process — once at start and once per 65 536 frames
 * — so the cost is irrelevant and the guarantee is not.
 *
 * DataStore writes to a temporary file and renames, so a crash mid-write leaves the
 * previous value intact rather than a truncated one. That is the same atomic-install rule
 * the model packs follow, and for the same reason.
 */
class DataStoreEpochStore(private val context: Context) : EpochCounter.Store {
    override fun read(): Long? =
        runBlocking {
            context.epochStore.data.first()[KEY]
        }

    override fun write(epoch: Long) {
        runBlocking {
            context.epochStore.edit { it[KEY] = epoch }
        }
    }

    private companion object {
        val KEY = longPreferencesKey("epoch")
    }
}
