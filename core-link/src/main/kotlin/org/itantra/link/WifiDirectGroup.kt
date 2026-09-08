package org.itantra.link

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Forms the Wi-Fi Direct group by itself, so nobody has to open Settings.
 *
 * ## What it is for
 *
 * [WifiBroadcastLink] carries frames over whatever local network the handsets share, and a
 * Wi-Fi Direct group is one such network: the owner's `p2p` interface is 192.168.49.1, a
 * client gets an address on the same subnet, and the subnet broadcast reaches every unit.
 * Until this class, that group had to be made by hand -- Settings, Wi-Fi Direct, tap the
 * other handset, accept on it -- on every pair of handsets, every time. This class does
 * the tapping: it looks for a unit that already owns a group and joins it, and if there is
 * none, becomes one. The rules are in [WifiDirectElection]; this class is the radio.
 *
 * ## What the platform still asks of a person
 *
 * The first time a unit joins a particular owner, the owner's screen shows the platform's
 * own "Invitation to connect" dialog, and somebody has to tap Accept within about thirty
 * seconds. There is no application-level way round it. The group is *persistent*, so that
 * happens once per pair of handsets: from then on, and across restarts and reboots, the
 * join completes without a dialog on either side.
 *
 * Wi-Fi has to be switched on. An application cannot switch it on from Android 10, so the
 * road's status line says so instead. On Android 12 and below, discovery additionally
 * needs the handset's location mode on, by platform rule, and the status line says that
 * too.
 *
 * ## What it costs
 *
 * `NEARBY_WIFI_DEVICES` on Android 13 and up, declared `neverForLocation`; the location
 * permission the application already holds for finding a unit, on Android 12 and below.
 * Nothing here reads a position. See `docs/TRANSPORT.md` section 4.
 *
 * Idempotent: [start] on a started group and [stop] on a stopped one do nothing, so the
 * engine can call them on every tick with the road switch as the argument.
 */
class WifiDirectGroup(
    private val context: Context,
    private val scope: CoroutineScope,
    private val election: WifiDirectElection = WifiDirectElection(),
) {
    /** What the group is doing, for the road's status line. */
    sealed interface Status {
        val summary: String

        /** Not running, and why. */
        data class Off(
            val reason: String,
        ) : Status {
            override val summary get() = reason
        }

        data object Searching : Status {
            override val summary get() = "looking for a group"
        }

        data class Joining(
            val owner: String,
        ) : Status {
            override val summary get() = "joining $owner"
        }

        data class Owner(
            val clients: Int,
        ) : Status {
            override val summary
                get() =
                    when (clients) {
                        0 -> "owning a group, nobody joined yet"
                        1 -> "owning a group, 1 unit joined"
                        else -> "owning a group, $clients units joined"
                    }
        }

        data class Client(
            val owner: String,
        ) : Status {
            override val summary get() = "joined $owner's group"
        }
    }

    private val _state = MutableStateFlow<Status>(Status.Off("Not started"))
    val state: StateFlow<Status> get() = _state.asStateFlow()

    private val lock = Any()
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var ticker: Job? = null
    private var running = false

    /** Whether the platform says Wi-Fi Direct is on; from a sticky broadcast, so known at once. */
    private var p2pOn = false

    /** The last peer report, so a tick can decide with it. */
    private var peers: List<WifiDirectElection.Peer> = emptyList()

    /** What each peer calls itself, for the status line. */
    private val names = HashMap<String, String>()

    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
            val pm = context.packageManager
            if (!pm.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
                _state.value = Status.Off("This handset has no Wi-Fi Direct")
                return
            }
            val p2p = context.applicationContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            if (p2p == null) {
                _state.value = Status.Off("No Wi-Fi Direct service on this handset")
                return
            }
            manager = p2p
            election.reset(now())
            _state.value = Status.Searching
            val filter =
                IntentFilter().apply {
                    addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                    addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                    addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                    addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
                }
            val listener = Receiver()
            receiver = listener
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(listener, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    context.registerReceiver(listener, filter)
                }
            }.onFailure { Log.w(TAG, "could not listen for Wi-Fi Direct broadcasts", it) }
            ticker =
                scope.launch(Dispatchers.Default) {
                    while (isActive) {
                        runCatching { tick() }.onFailure { Log.w(TAG, "tick failed", it) }
                        delay(TICK_MILLIS)
                    }
                }
            Log.i(TAG, "started")
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!running) return
            running = false
            ticker?.cancel()
            ticker = null
            receiver?.let { runCatching { context.unregisterReceiver(it) } }
            receiver = null
            val p2p = manager
            val ch = channel
            if (p2p != null && ch != null) {
                // The group is taken down with the application, so the handset's Wi-Fi is
                // its own again; the next start forms it afresh.
                runCatching { p2p.stopPeerDiscovery(ch, null) }
                when (election.phase) {
                    is WifiDirectElection.Phase.Owner, is WifiDirectElection.Phase.Client ->
                        runCatching { p2p.removeGroup(ch, null) }
                    is WifiDirectElection.Phase.Joining -> runCatching { p2p.cancelConnect(ch, null) }
                    is WifiDirectElection.Phase.Searching -> Unit
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) runCatching { ch.close() }
            }
            channel = null
            manager = null
            peers = emptyList()
            _state.value = Status.Off("Not started")
            Log.i(TAG, "stopped")
        }
    }

    // ── the clock ────────────────────────────────────────────────────────────

    private fun tick() {
        synchronized(lock) {
            if (!running) return
            val p2p = manager ?: return
            blocker()?.let {
                _state.value = Status.Off(it)
                return
            }
            if (channel == null) {
                channel =
                    runCatching {
                        p2p.initialize(context.applicationContext, Looper.getMainLooper()) {
                            // The channel went away under us; the next tick makes another.
                            synchronized(lock) { channel = null }
                        }
                    }.getOrNull()
                if (channel == null) {
                    _state.value = Status.Off("Wi-Fi Direct would not open")
                    return
                }
                Log.i(TAG, "channel open")
            }
            act(election.advance(now(), peers))
            show()
        }
    }

    /** Why the group cannot run right now, in the operator's words, or null. */
    private fun blocker(): String? {
        val needed =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.NEARBY_WIFI_DEVICES
            } else {
                Manifest.permission.ACCESS_FINE_LOCATION
            }
        if (context.checkSelfPermission(needed) != PackageManager.PERMISSION_GRANTED) {
            return "Nearby devices permission refused"
        }
        if (!p2pOn) return "Wi-Fi is off. Switch it on; no network is needed"
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.P..Build.VERSION_CODES.S_V2) {
            val location = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (location != null && !location.isLocationEnabled) {
                return "Location is off. Android 12 and below needs it on to find units over Wi-Fi Direct"
            }
        }
        return null
    }

    // ── the radio ────────────────────────────────────────────────────────────

    private fun act(action: WifiDirectElection.Action) {
        val p2p = manager ?: return
        val ch = channel ?: return
        when (action) {
            WifiDirectElection.Action.None -> Unit
            WifiDirectElection.Action.Discover -> {
                Log.i(TAG, "discovering")
                call("discover") { p2p.discoverPeers(ch, it) }
            }
            is WifiDirectElection.Action.Join -> {
                Log.i(TAG, "joining ${nameOf(action.owner)}")
                val config =
                    WifiP2pConfig().apply {
                        deviceAddress = action.owner
                        wps.setup = WpsInfo.PBC
                        // The other side already owns the group; this says so in case the
                        // platform negotiates anyway.
                        groupOwnerIntent = 0
                    }
                call("join", onFailure = { synchronized(lock) { election.joinFailed(now()) } }) {
                    p2p.connect(ch, config, it)
                }
            }
            WifiDirectElection.Action.CreateGroup -> {
                Log.i(TAG, "creating a group")
                call("create", onFailure = { synchronized(lock) { election.createFailed(now()) } }) {
                    p2p.createGroup(ch, it)
                }
            }
            WifiDirectElection.Action.RemoveGroup -> {
                Log.i(TAG, "stepping down for another owner")
                call("remove") { p2p.removeGroup(ch, it) }
            }
        }
    }

    /**
     * One `WifiP2pManager` call, with its outcome logged and a `SecurityException` --
     * the permission revoked mid-run -- reported rather than thrown into the ticker.
     */
    private fun call(
        what: String,
        onFailure: (() -> Unit)? = null,
        invoke: (WifiP2pManager.ActionListener) -> Unit,
    ) {
        val listener =
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() = Unit

                override fun onFailure(reason: Int) {
                    Log.w(TAG, "$what refused: ${reasonName(reason)}")
                    if (reason == WifiP2pManager.P2P_UNSUPPORTED) {
                        synchronized(lock) { _state.value = Status.Off("This handset has no Wi-Fi Direct") }
                    }
                    onFailure?.invoke()
                }
            }
        runCatching { invoke(listener) }.onFailure {
            Log.w(TAG, "$what threw", it)
            onFailure?.invoke()
        }
    }

    private inner class Receiver : BroadcastReceiver() {
        override fun onReceive(
            context: Context,
            intent: Intent,
        ) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    val on = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    synchronized(lock) {
                        p2pOn = on
                        if (!on) {
                            election.lost(now())
                            peers = emptyList()
                        }
                    }
                    Log.i(TAG, if (on) "Wi-Fi Direct is on" else "Wi-Fi Direct is off")
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> onPeersChanged()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> onConnectionChanged()
                WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1)
                    if (state == WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED) {
                        synchronized(lock) { election.discoveryStopped() }
                    }
                }
            }
        }
    }

    private fun onPeersChanged() {
        val p2p = manager ?: return
        val ch = channel ?: return
        runCatching {
            p2p.requestPeers(ch) { list ->
                val seen = ArrayList<WifiDirectElection.Peer>()
                for (device in list.deviceList) {
                    if (device.status == WifiP2pDevice.FAILED || device.status == WifiP2pDevice.UNAVAILABLE) continue
                    seen += WifiDirectElection.Peer(device.deviceAddress, device.isGroupOwner)
                    device.deviceName?.takeIf { it.isNotBlank() }?.let { names[device.deviceAddress] = it }
                }
                synchronized(lock) {
                    if (!running) return@requestPeers
                    peers = seen
                    val roll = seen.joinToString { nameOf(it.address) + if (it.isOwner) " (owner)" else "" }
                    Log.i(TAG, "peers: $roll")
                    act(election.advance(now(), seen))
                    show()
                }
            }
        }.onFailure { Log.w(TAG, "could not read peers", it) }
    }

    private fun onConnectionChanged() {
        val p2p = manager ?: return
        val ch = channel ?: return
        runCatching {
            p2p.requestConnectionInfo(ch) { info ->
                if (info == null || !info.groupFormed) {
                    synchronized(lock) {
                        if (!running) return@requestConnectionInfo
                        Log.i(TAG, "no group")
                        election.lost(now())
                        show()
                    }
                    return@requestConnectionInfo
                }
                runCatching {
                    p2p.requestGroupInfo(ch) { group ->
                        val clients = group?.clientList?.size ?: 0
                        val owner = group?.owner
                        val ownerName = owner?.deviceName?.takeIf { it.isNotBlank() }
                        if (owner != null && ownerName != null) names[owner.deviceAddress] = ownerName
                        synchronized(lock) {
                            if (!running) return@requestGroupInfo
                            Log.i(
                                TAG,
                                if (info.isGroupOwner) {
                                    "owning ${group?.networkName ?: "a group"}, $clients client(s)"
                                } else {
                                    "joined ${group?.networkName ?: "a group"} owned by ${nameOf(owner?.deviceAddress)}"
                                },
                            )
                            election.formed(now(), info.isGroupOwner, owner?.deviceAddress, clients)
                            // A client has found what it was looking for; a find running
                            // beside a group it is in only costs it throughput.
                            if (!info.isGroupOwner) runCatching { p2p.stopPeerDiscovery(ch, null) }
                            show()
                        }
                    }
                }.onFailure { Log.w(TAG, "could not read the group", it) }
            }
        }.onFailure { Log.w(TAG, "could not read the connection", it) }
    }

    /** Mirrors the election's phase into [state]. Called under [lock]. */
    private fun show() {
        _state.value =
            when (val phase = election.phase) {
                is WifiDirectElection.Phase.Searching -> Status.Searching
                is WifiDirectElection.Phase.Joining -> Status.Joining(nameOf(phase.owner))
                is WifiDirectElection.Phase.Owner -> Status.Owner(phase.clients)
                is WifiDirectElection.Phase.Client -> Status.Client(nameOf(phase.owner))
            }
    }

    private fun nameOf(address: String?): String = address?.let { names[it] ?: it } ?: "?"

    private fun now(): Long = SystemClock.elapsedRealtime()

    private fun reasonName(reason: Int): String =
        when (reason) {
            WifiP2pManager.P2P_UNSUPPORTED -> "unsupported"
            WifiP2pManager.BUSY -> "busy"
            WifiP2pManager.NO_SERVICE_REQUESTS -> "no service requests"
            WifiP2pManager.ERROR -> "error"
            else -> "reason $reason"
        }

    private companion object {
        const val TAG = "itantra-wifidirect"

        /** How often the election is asked what to do when no broadcast has arrived. */
        const val TICK_MILLIS = 2_000L
    }
}
