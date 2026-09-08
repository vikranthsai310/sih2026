# The transport layer

Implemented by `core-link`. **Three physical media, one interface**, all of them phone to
phone. The application never learns which one is carrying its bytes.

> **Scope: mobile application only.** The problem statement asks for streaming "through
> wifi/Bluetooth connected embedded device **or another phone** with same application". We
> do the second. There is no radio module, no ESP32 and no LoRa node in this project, so
> `SerialLink` and the deployment topology that went with it are out of scope.
>
> The low-bitrate argument is unaffected and still load-bearing: Bluetooth Low Energy
> gives 5–20 kbps, which is already below the 6 kbps Opus floor once framing and
> retransmission are accounted for, and it is the transport that supports the eight-hour
> standby claim.

## 1. The abstraction

```kotlin
interface Link {
    suspend fun send(frame: ByteArray)
    val incoming: Flow<ByteArray>
    val state: StateFlow<LinkState>      // IDLE · DISCOVERING · CONNECTED · DEGRADED · ERROR
    val mtu: Int
    val metrics: LinkMetrics             // rssi, round-trip, loss, queue depth
}
```

Four implementations satisfy this contract. Changing transport is a settings toggle, and
adding one costs a single file.

This abstraction is also what allows the entire transport layer to be developed and tested
in **week 2, before any model exists** — a text field replaces the recogniser and the whole
path is exercised. That is not a convenience; it is the reason the schedule survives.

### Contract obligations

- `send` MUST be safe to call from any thread and MUST NOT block the caller for more than
  the time to enqueue.
- `incoming` MUST emit exactly one complete frame per emission — de-framing is the
  implementation's job, never the consumer's.
- `state` MUST reach `DEGRADED` rather than `ERROR` for any condition the implementation
  can recover from on its own.
- An implementation MUST NOT interpret the payload. It sees bytes.

## 2. Bluetooth Classic — RFCOMM

**The default transport.** RFCOMM is a virtual serial cable, and it is primary for two
reasons: it is a reliable ordered byte stream requiring no additional protocol work, and it
is the same profile an ESP32 or a radio modem exposes.

**The code that talks to another phone is the code that talks to a radio.** That single
property is worth more than any performance characteristic in the table below.

```kotlin
val ITANTRA_UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")

// host
val server = adapter.listenUsingRfcommWithServiceRecord("iTantra", ITANTRA_UUID)
val socket = server.accept(); server.close()

// peer
adapter.cancelDiscovery()                         // discovery cripples throughput
val socket = device.createRfcommSocketToServiceRecord(ITANTRA_UUID).apply { connect() }
```

`cancelDiscovery()` before connecting is not optional. Leaving discovery running reduces
throughput by roughly an order of magnitude and is a common cause of "it works on the
bench, it fails on stage".

### Permissions

`BLUETOOTH_CONNECT` and `BLUETOOTH_SCAN` from API 31, with `neverForLocation` on the scan
declaration so the app does not request location. Requesting location permission for a
disaster-communications app invites a question you do not want during a demonstration.

## 3. Bluetooth Low Energy — GATT

BLE is not a stream but a set of addressable characteristics. One characteristic is
written by the peer, one is notified by the host.

| Property | Value |
| --- | --- |
| Service UUID | `8ce255c0-200a-11e0-ac64-0800200c9a66` |
| TX characteristic | Notify, host → peers |
| RX characteristic | Write without response, peer → host |
| Default MTU | 23 B |
| Negotiated MTU | 247 B, giving ~244 usable |

A typical sentence — 44 B unauthenticated, 60 B with a full tag — fits in a single packet after negotiation. Frames exceeding the
negotiated MTU are fragmented per [PROTOCOL.md §11](PROTOCOL.md#11-fragmentation).

Power consumption is roughly a tenth of Bluetooth Classic, which makes BLE the correct
**standby** transport and is what supports the eight-hour endurance claim. It is also
natively broadcast, which makes all-units operation straightforward.

### The broadcast road as shipped: the air is a buffer

What ships is not GATT but **extended advertising** (`BleBroadcastLink`): frames go out as
service data under the UUID above and every unit scans for it. The link holds **one
advertising set** for its whole life, and the set is on the air **all the time**, ten
advertisements a second (`INTERVAL_HIGH`). What it advertises is not one frame but
everything this unit has said recently, laid out by `OnAir` and packed by `AirBlob`:

```
 byte  0      1      2      3 …
     +------+------+-------+--------------------------------------------+
     | 0xB1 | SRC  | KEYID | hello, presence, message, message, …       |
     +------+------+-------+--------------------------------------------+
```

- The **hello** and the **presence** are pinned at the head; only the latest of each is
  kept. `SRC` and `KEYID` are the advertiser's own, so a hearing is a reading of the
  distance to *this* unit even when the frames it carries were first said by another.
- Every other frame queues in order, alerts first, and once on the air stays there for **at
  least 5 s** and **up to 30 s** while nothing newer needs the room. At ten a second that is
  fifty to three hundred chances for a scanner that, measured on two SM-S947B handsets,
  takes roughly one in three.
- The buffer is kept to **one radio packet**, about 200 B of frames, because a chain of
  packets is heard far less often than one. A single frame larger than that goes alone,
  chained, up to what the controller reports it can carry (`mtu`); `Session` fragments
  beyond that.
- The receiver splits the blob by each frame's `LEN`, delivers every frame **once** (a
  per-frame repeat window longer than any stay on the air), and the replay window above
  discards anything that still arrives twice, by two roads or by relay. A blob whose first
  byte is a frame's own `0xA1` sentinel is read as a bare frame, so a unit on the previous
  version is still heard.
- A blob that fits one controller command (251 B) replaces the set's data while it is on
  the air, with no gap; a larger one pays for disable, set, enable, each step waited on for
  its status. A refusal at any step tears the set down and starts a fresh one; the frames
  stay in the buffer, and nothing is counted as sent until the controller has accepted it.
- Every five seconds the link checks that the radio is still on and, every ten minutes,
  restarts the scan (`keepScanning`). A radio that is off makes the road *degraded*, which is
  what gets it re-armed on the engine's next recovery tick when the radio returns.

> **Amended 2026-09-07 — "one message arrived and the next did not".** The version before
> this one put each frame on the air by itself for 2.5 s (a hello for 600 ms) and then took
> it off for good. An advertisement is a chance, not a packet: a receiver whose scanner was
> between duty cycles, restarting, or busy with the hello that went out just before missed
> all twenty-five chances now and then, and nothing acknowledged or retried, so the message
> was simply gone. The buffer above replaces that. It is also why the set is now on the
> air continuously rather than in bursts: the locate screen gets a reading ten times a
> second from every unit in range, and a unit that has just started learns every
> neighbour's epoch from the pinned hello the moment it hears anything.

> **Amended 2026-09-07 — ten a second.** The set's interval was `INTERVAL_LOW`, one
> advertisement a second, so a 2.5 s window held two or three chances rather than the
> twenty-five the design assumed. It is now `INTERVAL_HIGH`, 100 ms. The extended header
> also carries the sender's transmit power (`setIncludeTxPower`), read back as
> `ScanResult.txPower`, so distance is reckoned against the power that actually left the
> antenna rather than one figure for every make of phone. `Signal.txPower`,
> `Locator.referenceFor`.

> **Amended 2026-09-07 — "it sends for a while, then stops, then sometimes sends".** Two
> causes, one each side. *Receiving:* Android puts a thirty-minute limit on every scan,
> after which it is silently downgraded to *opportunistic* — the application is no longer
> scanning and is handed results only when some other application scans for the same
> thing. The scan is restarted every ten minutes. *Sending:* the transmit loop wrote the
> next frame and re-enabled the set without waiting for the controller's answer to either
> command. Data written while a set is enabled must fit one command, 251 bytes; a longer
> frame was refused and the *previous* frame went out again, so long messages vanished
> without a log line. Each step now waits for its status.

> **Found 2026-09-07 on two SM-S947B handsets.** The first version started a new set for
> every frame and stopped it through a single shared callback object. Android keys its
> callback registry on that object, and the previous set's asynchronous "stopped"
> notification unregisters whichever set the callback maps to when it lands — the new one.
> That set could never be stopped: it advertised its frame for ever, the peer re-heard it
> every four seconds and dropped it as a replay, and after sixteen of them (the
> controller's limit) every start was refused with *too many advertisers* and the unit
> went mute. `dumpsys bluetooth_manager` showed sixteen ongoing sets.

## 4. Wi-Fi — hotspot or any shared network

Two mechanisms produce the same result: a local network between the devices, with no
router, no SIM and no internet. The **hosted-network** path is the one that ships — the
ordinary hotspot toggle, mobile data off, joined by the other handsets in Wi-Fi settings.
`WifiP2pManager` discovery is inconsistent across Samsung, Xiaomi and Realme builds and has
consumed a substantial fraction of many teams' schedules; the hosted network needs no P2P
API at all, so it is treated as optional (risk T-09). Any existing access point — a relief
camp's, an office's — works identically and needs nothing arranged.

### What is actually sent, and why it is not TCP

This document previously specified TCP on port `38173` with a UDP broadcast used only for
discovery. **Frames are sent as UDP broadcast datagrams on port `38173`
instead**, and there is no discovery step, no connection and no peer list.

The reason is the same one that produced `BleBroadcastLink`. TCP is point-to-point: N units
means N×(N−1)/2 connections, each of which must be discovered, established, torn down on a
handset walking out of range, and re-established when it returns — which is the pairing
problem in another costume, and the problem statement asks for a walkie-talkie, where one
press is heard by everyone. One `sendto` to the subnet broadcast address reaches every unit
on the network at once, is stateless, and needs nothing to have happened beforehand.

Frames are already authenticated and replay-protected end to end (`PROTOCOL.md` §6), so the
transport is not being asked to provide reliability or ordering it would otherwise supply.
A datagram lost is a frame lost, and the outbox retransmits.

Implementation notes worth stating, because each was a bug first:

- **Broadcast addresses are enumerated, not assumed.** A phone hosting a hotspot is usually
  `192.168.43.1` and a phone joined to one is not; some builds drop `255.255.255.255` while
  delivering the subnet's own broadcast perfectly. Every interface's broadcast address is
  used, plus the limited broadcast.
- **A multicast lock is held.** Without it Wi-Fi power save discards broadcast frames before
  they reach the socket, and the failure looks like a channel that transmits and never
  receives.
- **A unit's own broadcast comes back to it.** Recently sent frames are remembered by hash
  and dropped on arrival, so a handset does not read its own transmission as traffic.

### Wi-Fi Direct, and what "up" means

A Wi-Fi Direct group made in the system settings is a local network like any other: the
group owner's `p2p` interface is 192.168.49.1 and the client is given an address on the
same subnet, so the enumerated broadcast above reaches it and nothing here has to know
that P2P was involved. The road reports itself **up only while some interface has a
subnet broadcast address** -- a hotspot's, a group's, an access point's -- and looks again
every three seconds, because a group formed after the application started, or a client
still waiting for its address, appears with no callback.

> **Amended 2026-09-08 — "with Bluetooth off, nothing transfers over Wi-Fi Direct".** Two
> defects. The engine added the Wi-Fi road only *after* it had checked the Bluetooth radio,
> and returned early when the radio was off, so a unit with Bluetooth off had no road at
> all. And the Wi-Fi road reported itself connected from the moment its socket was bound,
> whether or not the handset was on any network, since the limited broadcast can always be
> sent to nowhere; so the engine could not have told the difference anyway. The road is
> now added first and whatever the radio is doing, Bluetooth roads are added when the
> radio is on, then or later, and the road's state is the honest one above. The banner
> names Bluetooth as the problem only when nothing carries.

### The permission this costs

`android.permission.INTERNET`. Android requires it to open any socket, including one that
only ever addresses a broadcast address on the local subnet. Constraint **C2** had been
verified by that permission's absence, which was a stronger claim than C2 makes and made a
transport ISRO's own description asks for ("streamed through wifi/Bluetooth") impossible to
build. The claim is now verified by inspection instead: every datagram goes to a broadcast
address, and nothing in the application resolves a hostname or opens an outbound network
connection.

`WifiBroadcastLinkTest` asserts the first two of those, on the JVM and with no handset:
that `WifiBroadcastLink.broadcastTargets()` returns the enumerated broadcast addresses and
nothing else — equality both ways, so a unicast target would fail it — and that the limited
broadcast is built from its four bytes rather than resolved from a name. The third is a
grep over `src/main` that returns nothing. `docs/SECURITY.md` audit item 3 records all
three as re-runnable lines. `WifiLink`, a TCP-and-discovery design that was never wired to
anything, was deleted on 2026-09-06 so that the third check holds without a caveat.

Wi-Fi is also the only transport on which `AUDIO_FB` — the low-confidence Opus fallback —
is permitted, because it is the only one with the bandwidth for it.

## 5. Serial — the deployment path

The fourth implementation sends identical frames over Bluetooth SPP to a radio module. The
phone is unchanged; only what sits at the far end of the Bluetooth connection differs. This
is the configuration the problem statement describes when it refers to a "Wi-Fi/Bluetooth
connected embedded device".

```
 DEMONSTRATION
   Phone A ────── Bluetooth ──────► Phone B                       30 m

 DEPLOYMENT
   Phone A ──BT──► LoRa node ═══ 865.5 MHz ═══► LoRa node ──BT──► Phone B
                                                                  2 – 15 km
```

### Radio node

| Item | Choice |
| --- | --- |
| MCU | ESP32 with Bluetooth SPP |
| Radio | SX1276/SX1278 at 865.5 MHz — licence-free in India (865–867 MHz) |
| Firmware | ~200 lines: SPP in → LoRa out, LoRa in → SPP out. No parsing, no buffering beyond one frame |
| Cost | ~₹1 500 per pair |

The firmware deliberately does not understand the protocol. It is a wire, and keeping it a
wire is what makes it trustworthy.

### LoRa parameters

| Parameter | Value | Effective rate |
| --- | --- | --- |
| Spreading factor | 10 | ~980 bps |
| Bandwidth | 125 kHz | |
| Coding rate | 4/5 | |
| Long-range profile | SF12, 125 kHz | ~290 bps |

Duty-cycle limits apply in the 865–867 MHz band; at one message per several seconds the
system is comfortably within them, and the frame budget in
[PROTOCOL.md §1](PROTOCOL.md#1-frame-layout) is what keeps it there.

## 6. Comparison

| Property | BT Classic | BLE | Wi-Fi | LoRa serial |
| --- | --- | --- | --- | --- |
| Range | 10–30 m | 10–50 m | 50–150 m | 2–15 km |
| Usable rate | ~200 kbps | 5–20 kbps | > 10 Mbps | 0.3–5 kbps |
| Power | Medium | Very low | High | Low |
| Model | Byte stream | Packets | Byte stream | Packets |
| Native broadcast | No | Yes | Yes (subnet) | Yes |
| Audio fallback viable | Marginal | No | Yes | No |
| Fragmentation needed | No | Yes | No | Yes |
| **Role** | **Default** | **Standby** | **Range and fallback** | **Deployment** |

## 7. Framing over a stream

RFCOMM and TCP preserve byte order but not message boundaries. The `readFully` loop and the
resynchronisation rule are normative and specified in
[PROTOCOL.md §13](PROTOCOL.md#13-stream-framing). Every stream implementation MUST use
them.

## 8. Discovery, connection and recovery

| Phase | Behaviour |
| --- | --- |
| Discovery | RFCOMM: bonded devices first, then a bounded 12 s scan. BLE: advertise and scan on the service UUID, no bonding. Wi-Fi: none — frames are broadcast to the subnet on port `38173` and any unit on the network receives them; a hotspot, a Wi-Fi Direct group or an access point all count, and the road is up only while one exists |
| Connection | RFCOMM: the unit with the lexicographically greater **Bluetooth name** dials, the other listens (`PeerPreference`). Two units with the same name, or no name, both dial and **both sockets are kept** — a duplicate frame costs nothing, a pair that closes each other's socket never connects. Broadcast roads have no connection at all |
| Hello | Every 5 s and once on start: 16 bytes announcing this unit's epoch, so a peer that restarted or was reinstalled is verified in one check (PROTOCOL.md §9). On the BLE road the latest hello is pinned to the advertisement, so it is on the air continuously. The 2 s authenticated heartbeat in the original design was never sent, for the reason given there |
| Backoff | Exponential with jitter, 1.5 s → 15 s, reset on connection |
| Recovery | Every 5 s the engine re-opens any road that is down — a radio switched off and on, a hotspot that appeared — and flushes the outbox if anything is queued. Returning to the application does the same at once |
| Store and forward | Frames queue in the outbox while disconnected and flush in order on reconnection, and on every recovery tick |
| Degraded | Surfaced in the UI with a reason string. The service never silently stops trying. A handset without Bluetooth 5 extended advertising reports the BLE road as degraded rather than open, because a legacy advertisement cannot hold the smallest frame |

**Bringing two handsets up.** Grant the microphone and all three "Nearby devices"
permissions on both. For the fastest road, bond the two in Android's Bluetooth settings —
the application dials only bonded handsets and never makes itself discoverable. Either
turn on one handset's hotspot and join the other to it, or rely on BLE broadcast between
two Bluetooth 5 handsets; both roads need no bonding. Check the node id in band A differs
on the two units. With relay mode off the engine lives with the screen — keep the
application in the foreground. With it on, it does not; see below.

### Relay mode

Every unit already rebroadcasts what it hears, within the hop count (PROTOCOL.md §8). What
relay mode changes is **whether it keeps doing so with the screen off**. Off, the engine
lives and dies with the activity, which is the right lifetime for a screen and the wrong one
for a unit whose operator has put it in a pocket to wade through water. On, the engine
belongs to a foreground service that outlives the activity: the radios stay up, a partial
wake lock keeps the processor answering scan results, and a silent notification says what
the unit is hearing. The service is `START_STICKY`, and builds its own engine, so a kill
under memory pressure brings the relay back working rather than back empty.

It is a switch in the control room, **off by default**, and it says on the card what it
costs. The radios and the processor stay on; that is battery, and it is the operator's
decision. The notification carries **Stop relaying**, which the service answers itself and
records, so the switch on the screen agrees with it when the operator comes back.

| Preconditions the code already meets | Why it matters |
| --- | --- |
| BLE scans with a `ScanFilter` on the service UUID | Android 8.1 and later kill an unfiltered scan when the screen goes off |
| Wi-Fi holds a `MulticastLock` | Power save otherwise drops broadcast datagrams before they reach the socket |
| Foreground service | Exempt from Doze's network restrictions, which is what lets the Wi-Fi road keep receiving |
| `connectedDevice` foreground type | What Android 14 requires of a service whose job is Bluetooth and Wi-Fi |

### Which roads a unit sends on

The three roads run at once and always have. What the operator can choose, on the mode and
radio screen, is which of them **routine traffic** goes down: each road has its own switch.
Not a chooser — a chosen radio would tell the operator their message left on one road when
it left on three. Two rules make the switches safe to offer:

- **A road that is off is still up, and still heard.** The switch is on sending only.
  Nothing an operator can set makes a unit deaf on a radio.
- **An alert goes down every road that is up, switched off or not.** `Link.send` carries
  one bit of context for this, set by `Session` for an `ALERT` and by the engine for a
  relayed one. A preference set on a quiet afternoon must never be why an evacuation order
  stayed on one handset. A queued alert keeps the bit through the outbox.

The last road on cannot be switched off — the control refuses, and the row says why — and
a stored selection that names nothing that exists reads back as everything. A selection
that would leave no road standing at the moment of sending (only a road that is down, say)
is set aside and the frame goes everywhere: the right failure for a radio is to transmit.

### The hop count

`TTL` is the operator's, 0 to 7, stepped in the control room and applied to frames this
unit originates. Zero is direct range only — read by the receiver, never rebroadcast. A
frame passing through is decremented from whatever *its* sender chose; this unit's setting
has no say in that. The ceiling is protection against a slip of the finger, not a protocol
limit: the byte allows 255, and on a channel with a loop in it that is a storm bounded only
by the seen-set.

**Pairing is the most common cause of demonstration failure.** Devices are paired before
the session, QR provisioning is the fallback, and a third pre-configured handset is kept
ready (risk P-03). See [DEMO.md](DEMO.md).

## 9. Metrics

Each implementation reports, into `LinkMetrics` and thence into `latency.csv`:

| Metric | Note |
| --- | --- |
| RSSI | Where the transport exposes it (BT, BLE, Wi-Fi) |
| Round-trip time | Measured on `HEARTBEAT`/`ACK` pairs |
| Frame loss | Gaps in per-sender `SEQ` |
| Queue depth | Outbound backlog; a rising value is the first symptom of a saturated link |
| Bytes sent and received | Drives the on-screen byte counter used in demonstration step 3 |
