# The transport layer

Implemented by `core-link`. Four physical media, one interface. The application never
learns which one is carrying its bytes.

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

A typical sentence fits in a single packet after negotiation. Frames exceeding the
negotiated MTU are fragmented per [PROTOCOL.md §11](PROTOCOL.md#11-fragmentation).

Power consumption is roughly a tenth of Bluetooth Classic, which makes BLE the correct
**standby** transport and is what supports the eight-hour endurance claim. It is also
natively broadcast, which makes all-units operation straightforward.

## 4. Wi-Fi — Direct or hosted network

Two mechanisms produce the same result: an isolated local network between the devices, with
no router, no SIM and no internet. Once either exists, transport is plain TCP on port
`38173`, with peer discovery by a single UDP broadcast on the subnet.

> **Engineering recommendation.** Prefer the **hosted-network** path — the ordinary hotspot
> toggle with mobile data off. `WifiP2pManager` discovery is inconsistent across Samsung,
> Xiaomi and Realme builds and has consumed a substantial fraction of many teams'
> schedules. The hosted network requires no P2P API at all. `WifiP2pManager` is treated as
> optional (risk T-09).

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
| Native broadcast | No | Yes | Multicast | Yes |
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
| Discovery | RFCOMM: bonded devices first, then a bounded 12 s scan. BLE: advertise and scan on the service UUID. Wi-Fi: UDP broadcast on port `38174`, 1 s interval, 10 attempts |
| Connection | Role is decided at provisioning — the first unit is the host. No negotiation on the wire |
| Heartbeat | Every 2 s; three consecutive misses mark the peer offline |
| Backoff | Exponential with jitter, 1 s → 30 s, reset on a successful frame |
| Store and forward | Frames queue in the outbox while disconnected and flush in order on reconnection |
| Degraded | Surfaced in the UI with a reason string. The service never silently stops trying |

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
