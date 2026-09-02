# Wire protocol

**Protocol version 1.** Normative. MUST / SHOULD / MAY carry RFC 2119 meanings.

Byte order is **big-endian** everywhere. All multi-byte integers are unsigned unless
stated. Implemented by `core-proto`, which has no Android dependency and is unit-tested and
fuzzed on the JVM.

Eleven bytes of header and two of trailer buy addressing, channels, priority, relay,
integrity and authentication. The payload is what remains.

---

## 1. Frame layout

```
 byte   0      1      2   3      4      5   6      7      8      9     10     11 …    n-2 n-1
      ┌──────┬──────┬──────────┬──────┬──────────┬──────┬──────┬──────┬──────┬───────┬─────────┐
      │MAGIC │ TYPE │   SEQ    │FLAGS │   LEN    │ SRC  │ DST  │ GRP  │ TTL  │PAYLOAD│  CRC16  │
      │ VER  │ LANG │          │      │          │      │      │      │      │       │         │
      └──────┴──────┴──────────┴──────┴──────────┴──────┴──────┴──────┴──────┴───────┴─────────┘
        └──────────────────── header, 11 bytes ─────────────────────┘         └─ trailer, 2 ─┘
```

| Field | Bytes | Definition |
| --- | --- | --- |
| `MAGIC`/`VER` | 1 | High nibble `0xA`, a fixed sentinel used to resynchronise a byte stream after corruption. Low nibble is the protocol version, currently `0x1`. A frame whose high nibble is not `0xA` MUST be discarded and the reader MUST resynchronise. |
| `TYPE`/`LANG` | 1 | High nibble: message type (§2). Low nibble: language index 0–9 (§3). Values `0xA`–`0xF` in the low nibble are reserved and MUST be rejected. |
| `SEQ` | 2 | Per-sender sequence number, big-endian. Increments once per transmitted frame, wraps at `0xFFFF`. Drives acknowledgement, duplicate suppression and the replay window. |
| `FLAGS` | 1 | Bitfield (§7). |
| `LEN` | 2 | Payload length in bytes, big-endian, **not** including header or CRC. MUST be ≤ 1024. This field is the basis of stream framing. |
| `SRC` | 1 | Sender node identifier (§8). |
| `DST` | 1 | Destination node, or `0xFF` for broadcast. |
| `GRP` | 1 | Channel identifier — the equivalent of a walkie-talkie channel. |
| `TTL` | 1 | Remaining relay hops. Decremented on forward; a frame arriving with `TTL == 0` MUST NOT be forwarded. Default 3. |
| `PAYLOAD` | `LEN` | Packed text, template identifier, coordinates, or control data. Sealed when `ENCRYPTED` is set (§6). |
| `CRC16` | 2 | CRC-16/CCITT-FALSE over bytes 0 … n-3 inclusive — the header and the payload exactly as transmitted, ciphertext included. |

### CRC parameters

CRC-16/CCITT-FALSE: polynomial `0x1021`, initial value `0xFFFF`, no reflection, no final
XOR. Check value for the ASCII string `123456789` is `0x29B1`; a test asserting this MUST
exist.

The CRC is an integrity check against corruption, **not** a security control. It is
computed and verified even when the payload is authenticated, because it lets a corrupt
frame be discarded before the more expensive AEAD verification and before any relay
decision.

### Total sizes

| Frame | Header | Payload | Tag | Total | Time on a 300 bps link |
| --- | --- | --- | --- | --- | --- |
| Template alert, unauthenticated | 13 B | 1 B | — | 14 B | 0.4 s |
| Template alert, 8-byte tag | 13 B | 1 B | 8 B | 22 B | 0.6 s |
| Template alert with coordinates, 8-byte tag | 13 B | 9 B | 8 B | 30 B | 0.8 s |
| Packed Hindi sentence, unauthenticated | 13 B | 32 B | — | 45 B | 1.2 s |
| Packed Hindi sentence, 8-byte tag | 13 B | 32 B | 8 B | 53 B | 1.4 s |
| Packed Hindi sentence, 16-byte tag | 13 B | 32 B | 16 B | 61 B | 1.6 s |
| Plain UTF-8 sentence, unauthenticated | 13 B | 99 B | — | 112 B | 3.0 s |
| Equivalent Opus audio at 6 kbps | — | 2 250 B | — | 2 250 B | 60 s |

> **Quote the authenticated figure.** The 45 B number is the unauthenticated frame. In
> any deployment worth defending, `ENCRYPTED` is set, and the honest figure is 53 B on a
> low-rate link or 61 B on Bluetooth. Against raw PCM that is still 1 574× and against the
> Opus floor it is 42×. Both numbers are defensible; only one of them survives a question
> from a technical jury.

---

## 2. Message types

| Code | Type | Semantics |
| --- | --- | --- |
| `0x1` | `TEXT` | Recognised speech, packed or plain. Fire and forget. |
| `0x2` | `ALERT` | Priority traffic. Pre-empts the transmit queue, is acknowledged and retried, and is announced on the alarm stream at the receiver. |
| `0x3` | `ACK` | Acknowledges a sequence number. Payload is the 2-byte acknowledged `SEQ`. |
| `0x4` | `PTT_CTL` | Floor seize and release. Payload is 1 byte: `0x01` seize, `0x00` release. Drives the channel-busy indicator. |
| `0x5` | `HEARTBEAT` | Liveness, every 2 s. Payload is defined in §9. |
| `0x6` | `TEMPLATE` | Single-byte message identifier from the shared table (§5). |
| `0x7` | `POSITION` | Eight-byte packed latitude and longitude (§10). |
| `0x8` | `AUDIO_FB` | Opus fallback when recogniser confidence is below threshold. Wi-Fi transport only; MUST be refused on BLE and serial transports. |
| `0x0`, `0x9`–`0xF` | — | Reserved. A frame with a reserved type MUST be discarded silently. |

`ACK` and `HEARTBEAT` MUST NOT be relayed. `ALERT` MUST be relayed if `TTL` permits.

### 2.1 Type carries handling; flags carry encoding

`TYPE` says how a frame is *handled* — queue priority, acknowledgement, whether the
receiver announces it on the alarm stream. `FLAGS` says how the payload is *encoded* —
`PACKED` for script packing, `TEMPLATE` for a one-byte identifier.

The two are independent, which is what makes a **template-coded alert** expressible:
`TYPE_ALERT` with `TEMPLATE` set. `TYPE_TEMPLATE` is retained as shorthand for a routine,
non-priority template message and is exactly equivalent to `TYPE_TEXT` with `TEMPLATE`
set; a receiver MUST treat the two identically.

A frame MUST NOT set both `PACKED` and `TEMPLATE`.

### 2.2 Frame layouts by type

Worked byte maps. Every field is big-endian; `SEQ` is shown as `nnnn`.

**`TEXT` — packed Hindi sentence, unauthenticated · 45 B**

```
 off   0    1    2  3    4     5  6    7    8    9   10   11 … 42   43 44
     ┌────┬────┬──────┬─────┬──────┬────┬────┬────┬────┬─────────┬───────┐
     │ A1 │ 11 │ nnnn │ 8A  │ 0020 │ 02 │ FF │ 07 │ 03 │ 32 B    │ CRC16 │
     └────┴────┴──────┴─────┴──────┴────┴────┴────┴────┴─────────┴───────┘
       │    │           │      │      │    │    │    │  packed text
       │    │           │      │      │    │    │    └ TTL   3 hops
       │    │           │      │      │    │    └ GRP   channel 7
       │    │           │      │      │    └ DST   FF broadcast
       │    │           │      │      └ SRC   node 02
       │    │           │      └ LEN   0x0020 = 32 payload bytes
       │    │           └ FLAGS 0x8A = FINAL | PACKED | CONFIDENCE 2
       │    └ TYPE 1 TEXT · LANG 1 Hindi
       └ MAGIC A · VER 1
```

**`TEXT` — same sentence, AES-256-GCM with a 16-byte tag · 61 B**

```
 off   0    1    2  3    4     5  6    7    8    9   10   11 … 58   59 60
     ┌────┬────┬──────┬─────┬──────┬────┬────┬────┬────┬─────────┬───────┐
     │ A1 │ 11 │ nnnn │ AA  │ 0030 │ 02 │ FF │ 07 │ 03 │ 32 + 16 │ CRC16 │
     └────┴────┴──────┴─────┴──────┴────┴────┴────┴────┴─────────┴───────┘
                        │      │                        ciphertext ‖ tag
                        │      └ LEN counts ciphertext PLUS tag
                        └ FLAGS 0xAA adds ENCRYPTED
```

The nonce is not on the wire. It is derived as `EPOCH ‖ SRC ‖ SEQ ‖ 0x00×5` (§6.2), and
the associated data is the full 11-byte header, so `SRC`, `DST`, `TYPE` and `FLAGS` are all
bound into the tag.

**`ALERT` — template code with coordinates, 8-byte tag · 30 B**

```
 off   0    1    2  3    4     5  6    7    8    9   10   11 … 27   28 29
     ┌────┬────┬──────┬─────┬──────┬────┬────┬────┬────┬─────────┬───────┐
     │ A1 │ 21 │ nnnn │ A7  │ 0011 │ 02 │ FF │ 07 │ 03 │ 9 + 8   │ CRC16 │
     └────┴────┴──────┴─────┴──────┴────┴────┴────┴────┴─────────┴───────┘
            │           │                              │
            │           │                              └ 01 = template id,
            │           │                                then 8 B position
            │           └ FLAGS 0xA7 = FINAL | ENCRYPTED | TEMPLATE | CONF 3
            └ TYPE 2 ALERT · LANG 1 Hindi
```

One payload byte of meaning. The receiver renders template `0x01` in **its own** selected
language, so this frame is announced in Tamil on a Tamil handset with no translation model
(§5.1).

**Remaining types** — header is identical in every case; only `TYPE`, `FLAGS` and the
payload differ.

| Type | Byte 1 | Payload | `LEN` | Total unauth. | Notes |
| --- | --- | --- | --- | --- | --- |
| `TEXT` plain UTF-8 | `1L` | UTF-8 bytes | var | 13 + n | `PACKED` clear |
| `TEXT` packed | `1L` | Packed bytes | var | 13 + n | `PACKED` set |
| `ALERT` | `2L` | As `TEXT` or template | var | 13 + n | Acknowledged and retried |
| `ACK` | `3L` | Acknowledged `SEQ` | 2 | **15 B** | Never relayed |
| `PTT_CTL` | `4L` | `01` seize / `00` release | 1 | **14 B** | Drives the busy indicator |
| `HEARTBEAT` | `5L` | §9 payload | 12 | **25 B** | Every 2 s; never relayed |
| `TEMPLATE` | `6L` | Template id | 1 | **14 B** | Routine template traffic |
| `POSITION` | `7L` | Lat, lon (§10) | 8 | **21 B** | |
| `AUDIO_FB` | `8L` | Opus frame | var | 13 + n | Wi-Fi only; refused elsewhere |

`L` is the language index nibble, 0–9.

Add 8 or 16 bytes to every total when `ENCRYPTED` is set, per the tag length negotiated for
the transport (§6.3).

---

## 3. Language indices

Normative and fixed. Changing this table is a protocol version change.

| Idx | Language | Script | Unicode block base |
| --- | --- | --- | --- |
| 0 | English | Latin | — (ASCII, see §4.3) |
| 1 | Hindi | Devanagari | `U+0900` |
| 2 | Bengali | Bengali | `U+0980` |
| 3 | Marathi | Devanagari | `U+0900` |
| 4 | Telugu | Telugu | `U+0C00` |
| 5 | Tamil | Tamil | `U+0B80` |
| 6 | Gujarati | Gujarati | `U+0A80` |
| 7 | Kannada | Kannada | `U+0C80` |
| 8 | Malayalam | Malayalam | `U+0D00` |
| 9 | Odia | Odia | `U+0B00` |

Each Indic block spans exactly 128 codepoints. Hindi and Marathi share Devanagari and
therefore share a packing table; they remain distinct indices because they select different
acoustic models, voices and normalisation rules.

---

## 4. Level 2 — script packing

UTF-8 spends three bytes per character on every Indic script, because UTF-8 must be able
to represent all scripts at once. The frame header already declares the language, so the
receiver knows which 128-codepoint block applies. Subtracting the block base yields a
single byte per character.

The transformation is lossless, table-driven, costs microseconds, and is available to us
only because we control both endpoints.

### 4.1 The alphabet

A packed payload is a sequence of bytes interpreted as follows.

| Byte range | Meaning |
| --- | --- |
| `0x00`–`0x1A`, `0x1C`–`0x7F` | Literal ASCII codepoint. Covers space, Latin digits, punctuation, and Latin text embedded in an Indic message. |
| `0x1B` | **Escape.** The next three bytes are a 24-bit big-endian Unicode scalar value. |
| `0x80`–`0xFF` | Codepoint `blockBase + (byte - 0x80)`. Covers the entire 128-codepoint block, not merely the ~70 that occur in ordinary running text. |

`0x1B` (ASCII `ESC`) is never valid running text, which is what makes it safe as the
escape marker. A literal `U+001B` is encoded as `1B 00 00 1B`.

### 4.2 Why the escape is mandatory

Real operational messages are not pure Indic script. They contain Latin digits (`17`),
punctuation (`,`), unit symbols, embedded English callsigns, and — critically —
**ZWJ `U+200D` and ZWNJ `U+200C`**, which are outside every Indic block but change the
rendering and, in some languages, the meaning of a conjunct. A packer without an escape is
silently lossy on ordinary input, which is a correctness defect rather than a compression
inefficiency.

Escaped characters cost 4 bytes each. They are rare by construction: ASCII is free in the
`0x00`–`0x7F` range, and everything in the declared script is free in `0x80`–`0xFF`.

### 4.3 English and mixed content

For `LANG == 0` (English), packing is ASCII passthrough: any codepoint ≤ `0x7F` other than
`0x1B` is written literally, everything else is escaped. Because UTF-8 already encodes
ASCII in one byte, the encoder MUST clear the `PACKED` flag and transmit plain UTF-8
whenever packing does not reduce the payload. Receivers MUST honour the flag rather than
assume.

### 4.4 Encoder obligations

1. Normalise the text to **Unicode NFC** before packing. Two visually identical strings
   that differ in composition would otherwise pack to different byte sequences and defeat
   template matching.
2. Reject a payload that would exceed `LEN` limits after packing, and fragment (§11) or
   truncate at a word boundary with the `PARTIAL` flag set.
3. Round-trip assert in debug builds: `unpack(pack(s, lang)) == s` for every transmitted
   string. This invariant is cheap and catches table errors immediately.

### 4.5 Worked example

`हमें तुरंत मदद चाहिए` — 20 characters including spaces.

| Encoding | Size |
| --- | --- |
| UTF-8 | 54 B (18 Devanagari × 3, plus 2 spaces × 1) |
| Packed | 20 B (18 in `0x80`–`0xFF`, 2 spaces as `0x20`) |

2.7× over UTF-8, on top of the ~770× already achieved by recognising the speech at all.

---

## 5. Level 3 — template codes

In an emergency, vocabulary is small and predictable. A shared table maps common
operational sentences to single-byte identifiers.

```
0x01  We need medical assistance
0x02  Fire — evacuate immediately
0x03  Position secure, no casualties
0x04  Send a boat
0x05  Request immediate extraction
...   up to 255 entries per deployment profile
```

After recognition, the text is fuzzy-matched against the table. On a confident match the
system transmits `TYPE_TEMPLATE` with a one-byte payload. The receiver's table renders the
full sentence in **its own selected language** and speaks it. On a weak match the system
falls back to Level 2 automatically. The user is never aware of the switch.

### 5.1 Cross-language delivery

Because every device holds the table in all ten languages, a template-coded message is
spoken in whichever language the receiver has selected. A Hindi speaker's alert reaches a
Tamil speaker in Tamil — with no translation model, no additional download, and no
additional latency. Cross-language operation falls out of the compression scheme.

This works **only** for template-coded traffic. Free-form `TEXT` is delivered in the
language it was spoken in. Say so plainly; it is an architectural consequence, and
overclaiming it as general translation invites a question that has no good answer.

### 5.2 Profile binding

> A gap in the design document, closed here. Two devices holding different template tables
> would render different sentences from the same byte. That is a **safety defect**, not a
> compatibility inconvenience: byte `0x02` meaning "evacuate immediately" on one handset
> and "position secure" on another is exactly the failure this system must not have.

- A deployment profile is identified by `profileId` (2 bytes) and a `profileDigest` — the
  first 4 bytes of the SHA-256 of the canonical serialisation of the table.
- Both are distributed with the group key at provisioning and stored in group
  configuration.
- Every `HEARTBEAT` carries `profileDigest` (§9).
- A device receiving a `HEARTBEAT` whose digest differs from its own MUST:
  1. raise a persistent `TEMPLATE MISMATCH` warning in the interface, naming the peer, and
  2. refuse to *send* `TYPE_TEMPLATE` frames for the remainder of the session, falling
     back to Level 2, and
  3. continue to *accept* `TYPE_TEMPLATE` frames only if the digest matched at the time
     the frame's `SEQ` was issued — in practice, refuse them and request no retry.

Degrading to script packing costs bytes. Speaking the wrong sentence costs more.

### 5.3 Matching rule

Matching is normalised-token Levenshtein over the recognised text against the table in the
**sender's** language. A match is accepted when the similarity ratio is ≥ 0.85 **and** the
recogniser's own confidence for the utterance is high. Both conditions are required: a
confident recognition of the wrong sentence and a hesitant recognition of the right one
are both unsafe. On acceptance, `FLAGS.CONFIDENCE` is set to `3` (template-matched).

Alert-class templates MUST additionally be confirmed by the sender before transmission
(risk S-03).

---

## 6. Cryptography

Address filtering is a convention, not a control — any device can set any destination byte.
In a system whose purpose is to raise alarms, the ability to inject a fraudulent
maximum-volume evacuation order is a weapon. **Encryption is the real address.**

### 6.1 Construction

- **AEAD:** AES-256-GCM.
- **Key:** 256-bit pre-shared group key, generated by the group creator, distributed by QR
  at provisioning, stored in Android Keystore, never written to DataStore, a file, or a
  log.
- **Plaintext:** the payload only.
- **Associated data:** the **entire 11-byte header**. This binds `SRC`, `DST`, `GRP`,
  `TYPE`, `SEQ` and `FLAGS` into the authentication tag, so none of them can be altered by
  an attacker without invalidating the frame. In particular the sender identity is
  authenticated, which is what defeats impersonation (risk S-01).
- **Ciphertext and tag** replace the payload; `LEN` counts ciphertext **plus** tag.

### 6.2 Deterministic nonce

> The design document specifies AES-GCM but quotes payload sizes with no room for a nonce.
> Resolved here: the nonce is **derived, not transmitted**.

```
nonce (12 bytes) = EPOCH (4 B, BE) ‖ SRC (1 B) ‖ SEQ (2 B, BE) ‖ 0x00 × 5
```

- `EPOCH` is a per-sender 32-bit counter, persisted, incremented **every time `SEQ` wraps**
  and every time the service starts. The receiver tracks the highest `EPOCH` seen per
  sender and rejects any frame carrying a lower one.
- `EPOCH` is carried in `HEARTBEAT` (§9) so a joining or restarting receiver learns it
  without a handshake.
- This guarantees the `(key, nonce)` pair is never reused, which is the one failure mode
  that destroys GCM entirely.

**Rekeying.** A group key MUST be rotated if `EPOCH` would exceed `0xFFFFFFFF`, if a device
is lost, or on any change to group membership. Rotation is a re-provisioning: a new QR
code.

### 6.3 Tag length

Tag length is a per-group configuration value negotiated at provisioning, chosen by
transport class.

| Transport class | Tag | Rationale |
| --- | --- | --- |
| Bluetooth Classic, BLE, Wi-Fi | 16 B | Bytes are free; use the full tag |
| Serial to LoRa or HF | 8 B | 8 bytes is 27 seconds of airtime saved per message at 300 bps |

A truncated 64-bit tag raises the per-attempt forgery probability to 2⁻⁶⁴. That remains
sound only if forgery attempts are bounded, so on low-rate transports the receiver MUST
rate-limit authentication failures: **more than 16 failed verifications from one `SRC`
within 60 s puts the link into `DEGRADED` and surfaces a warning.** Without that limit,
truncation is not defensible.

### 6.4 Replay window

A 64-entry sliding window per `SRC`, keyed on `(EPOCH, SEQ)`.

```
accept if   EPOCH > highestEpoch[src]                       → reset window, accept
       or  (EPOCH == highestEpoch[src]
            and SEQ is newer than the window                → slide, accept
                or within the window and not yet seen)      → mark, accept
reject otherwise
```

Without this, a recorded alert can be replayed indefinitely (risk S-02). The window is
per-sender and survives reconnection but not a service restart — which is exactly what
`EPOCH` exists to cover.

### 6.5 Unauthenticated operation

`ENCRYPTED` MAY be cleared only in a bench or demonstration configuration, and the
interface MUST display a permanent unmistakable **UNSECURED** banner while any group is
configured that way. There is no silent path to unauthenticated operation.

---

## 7. Flag bits

| Bit | Name | Meaning |
| --- | --- | --- |
| 7 | `FINAL` | Complete utterance; safe to synthesise |
| 6 | `PARTIAL` | Stabilised prefix; the receiver MAY begin early synthesis but MUST NOT treat it as final |
| 5 | `ENCRYPTED` | Payload is AES-GCM sealed per §6 |
| 4 | `FRAGMENT` | More fragments follow; see §11. BLE and serial transports only |
| 3 | `PACKED` | Payload uses single-byte script packing (§4). When clear, the payload is plain UTF-8 |
| 2 | `TEMPLATE` | Payload is a one-byte template identifier (§5), optionally followed by a `POSITION` body. Lets any `TYPE` carry a template code — see §2.1 |
| 1–0 | `CONFIDENCE` | 0 low, 1 medium, 2 high, 3 template-matched |

`FINAL` and `PARTIAL` MUST NOT both be set. A frame with both set MUST be discarded.
`PACKED` and `TEMPLATE` MUST NOT both be set. A frame with both set MUST be discarded.

---

## 8. Addressing

Radio does not route; it broadcasts. Every device within range receives every
transmission, and selection happens at the receiver. This is how aviation, military and
amateur radio have always worked, and iTantra reproduces it in software.

```kotlin
fun accept(f: Frame): Boolean = when {
    f.grp != myGroup   -> false   // different channel — ignore silently
    f.dst == BROADCAST -> true    // addressed to all units
    f.dst == myNode    -> true    // addressed to me
    else               -> false   // someone else's traffic
}
```

Three bytes deliver broadcast operation, private one-to-one messages, and independent
channels sharing one physical medium.

### Reserved node identifiers

| Value | Meaning |
| --- | --- |
| `0x00` | Unassigned — a device that has not completed provisioning. MUST NOT transmit |
| `0x01` | Group creator, conventionally the base station |
| `0x02`–`0xFD` | Assigned nodes |
| `0xFE` | Reserved for gateway or relay hardware |
| `0xFF` | Broadcast — all units on the channel |

### Relay

Where a device cannot reach its destination directly, intermediate devices forward the
frame and decrement `TTL`. A seen-set of recent `(SRC, EPOCH, SEQ)` triples suppresses
duplicates; without it, three mutually visible devices generate an unbounded broadcast
storm (risk T-10).

```kotlin
if (f.dst != myNode && f.ttl > 0 && seen.add(Triple(f.src, f.epoch, f.seq)))
    link.send(f.copy(ttl = f.ttl - 1))
```

The seen-set is bounded at 512 entries with LRU eviction, and forwarding is delayed by a
random 0–50 ms to avoid synchronised collisions. Relaying is performed on the **sealed
frame as received** — a relay does not decrypt, cannot decrypt unless it is in the group,
and MUST NOT re-seal. `TTL` therefore sits outside the authenticated header for relay
purposes; see the note below.

> **Known limitation, stated deliberately.** `TTL` is inside the associated data, so
> decrementing it invalidates the tag. Version 1 resolves this by having the relay forward
> the frame **unmodified** and rely on the seen-set alone for loop suppression, accepting a
> bounded flood within one group. A future version will move `TTL` outside the
> authenticated region. This is disclosed rather than discovered.

---

## 9. Heartbeat payload

Sent every 2 s by every provisioned node, so the interface can distinguish a quiet channel
from a dead one.

| Offset | Size | Field |
| --- | --- | --- |
| 0 | 4 | `EPOCH`, current, big-endian |
| 4 | 4 | `profileDigest`, first 4 bytes of SHA-256 of the template table (§5.2) |
| 8 | 1 | Battery percentage, 0–100, or `0xFF` if unknown |
| 9 | 1 | Link quality, 0–255, transport-defined (RSSI-derived where available) |
| 10 | 1 | State: `0` READY, `1` BUSY (floor held), `2` DEGRADED |
| 11 | 1 | Active language index |

12 bytes. `HEARTBEAT` MUST be authenticated like any other frame and MUST NOT be relayed.

---

## 10. Position payload

| Offset | Size | Field |
| --- | --- | --- |
| 0 | 4 | Latitude, signed, degrees × 10⁷, big-endian |
| 4 | 4 | Longitude, signed, degrees × 10⁷, big-endian |

Roughly 1 cm resolution, which is far more than required and still only 8 bytes. A
`TEMPLATE` frame MAY be followed immediately by a `POSITION` frame with the same `SEQ`
semantics to produce the 30 B "template alert with coordinates" case in §1.

---

## 11. Fragmentation

Only BLE (244 B usable after MTU negotiation) and serial transports need it. RFCOMM and
TCP are byte streams and MUST NOT fragment.

- The sender splits the **sealed** payload into chunks of at most `mtu - 13` bytes.
- Every fragment except the last sets `FRAGMENT`.
- All fragments of one message share `SEQ`; fragment order is transmission order.
- The receiver reassembles into a buffer capped at 1024 B and discards the partial message
  if the next fragment does not arrive within 2 s.
- AEAD verification happens **after** reassembly, on the complete payload.

---

## 12. Reliability

| Mechanism | Applies to | Behaviour |
| --- | --- | --- |
| CRC-16 | All frames | Corrupt frames are discarded, never spoken. A garbled instruction is more dangerous than a missing one |
| Acknowledge and retry | `ALERT` only | Up to 3 retries at 300 ms. Ordinary conversation is fire-and-forget — retransmitting stale speech is worse than losing it |
| Heartbeat | Link | Every 2 s; three missed heartbeats mark the peer offline |
| Store and forward | All | Frames queue in the outbox when the peer is unreachable and flush on reconnection, with pending / sent / delivered states surfaced in the UI |
| Reconnection | Link | Exponential backoff with jitter, 1 s to 30 s; the service restores the socket without user action |

---

## 13. Stream framing

RFCOMM and TCP preserve byte order but not message boundaries. Writing 45 bytes and then
38 may be read as 20 bytes and then 63. Every frame carries an explicit `LEN`, and every
read loops until the declared number of bytes has arrived.

```kotlin
fun InputStream.readFully(buf: ByteArray) {
    var off = 0
    while (off < buf.size) {
        val n = read(buf, off, buf.size - off)
        if (n < 0) throw EOFException()
        off += n
    }
}
```

Omitting this loop produces a system that works on a desk and fails under load. It is the
single most common defect in implementations of this class of project (risk T-08).

### Resynchronisation

On a CRC failure or an implausible `LEN`, the reader MUST scan forward byte by byte for the
next `0xA1` sentinel and attempt to parse from there. A decoder MUST NOT close the socket
on a bad frame; a noisy serial link produces bad frames routinely and closing the socket
turns a recoverable glitch into an outage.

---

## 14. Conformance checklist

An implementation is conformant when all of the following hold. These map one-to-one onto
tests in `core-proto`.

- [ ] `encode(decode(f)) == f` for every frame in the corpus
- [ ] `unpack(pack(s, lang)) == s` for every language and the full Unicode fuzz corpus
- [ ] CRC-16/CCITT-FALSE of `123456789` is `0x29B1`
- [ ] A frame with a bad CRC is discarded and the reader resynchronises on `0xA1`
- [ ] A frame with `FINAL` and `PARTIAL` both set is discarded
- [ ] A frame with `PACKED` and `TEMPLATE` both set is discarded
- [ ] `TYPE_TEMPLATE` and `TYPE_TEXT` with `TEMPLATE` set are handled identically
- [ ] A frame with a reserved `TYPE` or a `LANG` above 9 is discarded
- [ ] A replayed `(SRC, EPOCH, SEQ)` is rejected
- [ ] A frame with a modified `SRC` fails AEAD verification
- [ ] A frame with a modified `TTL` fails AEAD verification (documented limitation §8)
- [ ] The same `(key, nonce)` is never produced twice across a `SEQ` wrap
- [ ] A `TEMPLATE` frame is refused when `profileDigest` does not match
- [ ] The decoder survives 10⁶ truncated, interleaved and bit-flipped inputs without
      throwing an uncaught exception or allocating unboundedly
