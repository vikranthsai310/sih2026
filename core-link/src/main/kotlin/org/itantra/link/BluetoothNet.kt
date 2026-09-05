package org.itantra.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.UUID
import kotlin.random.Random

/**
 * Every other handset in the room, kept on one [MeshLink].
 *
 * ## What this owns that a single link cannot
 *
 * A walkie-talkie net is not a connection, it is a **roster**. Deciding who dials, noticing
 * that a unit has come back into range, and refusing to hold two sockets to the same
 * handset are all decisions about the set of peers rather than about any one of them, and
 * a per-connection object gets them wrong in ways that only show up with three units in the
 * room.
 *
 * ## One listener, not one per peer
 *
 * There is exactly one server socket. `accept()` is answered in a loop, so it serves every
 * incoming unit for as long as the application is running. The shape this replaced opened a
 * server socket **per bonded device**, several at once on the same service UUID, each
 * accepting exactly one connection and then closing — and since `accept()` returns whoever
 * connected rather than the device the socket was created for, a link labelled with one
 * handset's address was frequently the socket to a different handset entirely.
 *
 * ## Only handsets are dialled
 *
 * A bonded-device list is mostly earbuds, a watch, a car, a laptop. None of them publish
 * this service, so dialling them fails, and it fails slowly — an RFCOMM connect to a device
 * that is merely out of range takes seconds. Filtering by device class keeps the retry
 * budget on the units that might actually answer. See [looksLikeAHandset].
 *
 * ## Who dials
 *
 * [PeerPreference], which documents at length why the obvious answer does not work.
 *
 * ## Recovery
 *
 * A peer whose socket dies is removed from the mesh, and the dial loop for that address —
 * still running — reconnects. Both units dial when the names give no ordering, so recovery
 * works from whichever end notices first.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is requested by the app before use
class BluetoothNet(
    private val adapter: BluetoothAdapter,
    private val scope: CoroutineScope,
    private val mesh: MeshLink,
    private val bondedDevices: () -> List<BluetoothDevice>,
) {
    private class Peer(val link: RfcommLink, val inbound: Boolean)

    private val roster = LinkedHashMap<String, Peer>()
    private val lock = Mutex()
    private val jobs = ArrayList<Job>()

    @Volatile private var server: BluetoothServerSocket? = null

    /** Bonded handsets this unit will keep trying to reach. Empty means nobody to talk to. */
    val candidateCount: Int get() = candidates().size

    fun start() {
        if (jobs.isNotEmpty()) return
        jobs += scope.launch(Dispatchers.IO) { acceptLoop() }
        for (device in candidates()) {
            jobs += scope.launch(Dispatchers.IO) { dialLoop(device) }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        // A thread parked in accept() or read() is unblocked by closing the socket, never
        // by cancelling the coroutine that owns it.
        runCatching { server?.close() }
        server = null
        scope.launch {
            lock.withLock {
                roster.forEach { (address, peer) ->
                    runCatching { peer.link.disconnect() }
                    mesh.removePeer(address)
                }
                roster.clear()
            }
        }
    }

    // ── listening ────────────────────────────────────────────────────────────

    private suspend fun acceptLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                val listener = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
                server = listener
                while (currentCoroutineContext().isActive) {
                    // Blocks until a unit connects, or until stop() closes the listener.
                    register(listener.accept(), inbound = true)
                }
            } catch (e: IOException) {
                // Bluetooth switched off, or the listener was closed under us. Neither is
                // fatal: the radio may come back, and the operator is told separately.
            } catch (e: SecurityException) {
                return // The permission was revoked. Nothing here can recover that.
            } finally {
                runCatching { server?.close() }
                server = null
            }
            if (currentCoroutineContext().isActive) delay(LISTEN_RETRY_MILLIS)
        }
    }

    // ── dialling ─────────────────────────────────────────────────────────────

    private suspend fun dialLoop(device: BluetoothDevice) {
        val address = runCatching { device.address }.getOrNull() ?: return
        val backoff = Backoff(initialMillis = FIRST_RETRY_MILLIS, maxMillis = MAX_RETRY_MILLIS)

        // Two handsets switched on together would otherwise dial into each other's accept
        // at the same instant, every time, and keep colliding in step.
        delay(Random.nextLong(DIAL_STAGGER_MILLIS))

        while (currentCoroutineContext().isActive) {
            if (isLive(address)) {
                // Connected. Poll rather than back off, so a socket that dies at minute
                // forty is redialled in a second rather than in half a minute.
                backoff.reset()
                delay(ALIVE_POLL_MILLIS)
                continue
            }

            if (!PeerPreference.shouldDial(localName(), nameOf(device))) {
                // This unit is the listener for this pair. It still watches, because the
                // names can change while the application is running.
                delay(ALIVE_POLL_MILLIS)
                continue
            }

            try {
                // Leaving discovery running cripples RFCOMM throughput. Not optional.
                runCatching { adapter.cancelDiscovery() }
                val socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                socket.connect()
                register(socket, inbound = false)
                backoff.reset()
            } catch (e: IOException) {
                // A bonded handset that is not running iTantra refuses the service, and one
                // out of range times out. Both are ordinary on a radio net, and both are
                // retried rather than reported.
            } catch (e: SecurityException) {
                return
            }
            delay(backoff.nextDelayMillis())
        }
    }

    // ── the roster ───────────────────────────────────────────────────────────

    private suspend fun register(
        socket: BluetoothSocket,
        inbound: Boolean,
    ) {
        val address = runCatching { socket.remoteDevice?.address }.getOrNull()
        if (address == null) {
            runCatching { socket.close() }
            return
        }
        val theirName = runCatching { socket.remoteDevice?.name }.getOrNull()

        lock.withLock {
            val existing = roster[address]
            if (existing != null && existing.link.state.value == LinkState.CONNECTED) {
                if (existing.inbound == inbound || !supersedes(inbound, theirName)) {
                    runCatching { socket.close() }
                    return
                }
                // Both units dialled. Exactly one socket survives and both ends have to
                // choose the same one, or the pair loses the connection entirely.
                runCatching { existing.link.disconnect() }
                mesh.removePeer(address)
            }

            val link = RfcommLink(socket, scope, name = "bluetooth")
            roster[address] = Peer(link, inbound)
            mesh.addPeer(address, link)
            link.connect()
        }

        watchForDeath(address)
    }

    /** Whether a newly arrived socket replaces the one already held for that peer. */
    private fun supersedes(
        inbound: Boolean,
        theirName: String?,
    ): Boolean = inbound == PeerPreference.keepsInbound(localName(), theirName)

    private fun watchForDeath(address: String) {
        scope.launch {
            val link = lock.withLock { roster[address]?.link } ?: return@launch
            // Suspends until this socket stops working, then stops. Collecting a StateFlow
            // here would never complete, leaving one live coroutine per connection ever
            // made over a long deployment.
            link.state.first { it != LinkState.CONNECTED }
            lock.withLock {
                // Only if it is still the current socket: a superseded one reaching
                // DEGRADED must not evict its replacement.
                if (roster[address]?.link === link) {
                    roster.remove(address)
                    mesh.removePeer(address)
                }
            }
        }
    }

    private suspend fun isLive(address: String): Boolean =
        lock.withLock { roster[address]?.link?.state?.value == LinkState.CONNECTED }

    // ── what is worth dialling ───────────────────────────────────────────────

    private fun candidates(): List<BluetoothDevice> =
        runCatching { bondedDevices() }
            .getOrDefault(emptyList())
            .filter { looksLikeAHandset(it) }

    /**
     * Earbuds do not run this application, and dialling them costs seconds each time.
     *
     * `UNCATEGORIZED` and a missing class are both allowed through: an unknown device that
     * might be a handset is cheap to try, where excluding a real unit is a dead net.
     */
    private fun looksLikeAHandset(device: BluetoothDevice): Boolean {
        val major = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull() ?: return true
        return major == BluetoothClass.Device.Major.PHONE ||
            major == BluetoothClass.Device.Major.COMPUTER ||
            major == BluetoothClass.Device.Major.UNCATEGORIZED ||
            major == BluetoothClass.Device.Major.MISC
    }

    private fun localName(): String? = runCatching { adapter.name }.getOrNull()

    private fun nameOf(device: BluetoothDevice): String? = runCatching { device.name }.getOrNull()

    companion object {
        /** Stable service identifier; both ends must agree. */
        val SERVICE_UUID: UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")
        const val SERVICE_NAME = "iTantra"

        private const val LISTEN_RETRY_MILLIS = 2_000L
        private const val FIRST_RETRY_MILLIS = 1_500L
        private const val MAX_RETRY_MILLIS = 15_000L
        private const val ALIVE_POLL_MILLIS = 1_000L
        private const val DIAL_STAGGER_MILLIS = 2_000L
    }
}
