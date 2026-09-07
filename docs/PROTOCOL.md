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
 byte   0      1      2   3      4      5   6      7      8      9     10 …     n-2 n-1
      ┌──────┬──────┬──────────┬──────┬──────────┬──────┬──────┬──────┬───────┬─────────┐
      │MAGIC │ TYPE │   SEQ    │FLAGS │   LEN    │ SRC  │KEYID │ TTL  │PAYLOAD│  CRC16  │
      │ VER  │ LANG │          │      │          │      │      │      │       │         │
      └──────┴──────┴──────────┴──────┴──────────┴──────┴──────┴──────┴───────┴─────────┘
        └────────────────── header, 10 bytes ──────────────────┘        └─ trailer, 2 ─┘
```

| Field | Bytes | Definition |
| --- | --- | --- |
| `MAGIC`/`VER` | 1 | High nibble `0xA`, a fixed sentinel used to resynchronise a byte stream after corruption. Low nibble is the protocol version, currently `0x1`. A frame whose high nibble is not `0xA` MUST be discarded and the reader MUST resynchronise. |
| `TYPE`/`LANG` | 1 | High nibble: message type (§2). Low nibble: language index 0–9 (§3). Values `0xA`–`0xF` in the low nibble are reserved and MUST be rejected. |
| `SEQ` | 2 | Per-sender sequence number, big-endian. Increments once per transmitted frame, wraps at `0xFFFF`. Drives acknowledgement, duplicate suppression and the replay window. |
| `FLAGS` | 1 | Bitfield (§7). |
| `LEN` | 2 | Payload length in bytes, big-endian, **not** including header or CRC. MUST be ≤ 1024. This field is the basis of stream framing. |
| `SRC` | 1 | Sender node identifier (§8). **There is no destination field** — every frame is broadcast to every unit holding the key, exactly as a walkie-talkie is. See §8. |
| `KEYID` | 1 | First byte of SHA-256 of the shared key. A **cheap reject filter**, not a security control — it lets a frame from an unpaired transmitter be dropped without running AEAD verification. Derived automatically; never configured, never shown to the user. A collision (1 in 256) costs one wasted verification and is then rejected by the tag. |
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
| Template alert, unauthenticated | 12 B | 1 B | — | **13 B** | 0.3 s |
| Template alert, 8-byte tag | 12 B | 1 B | 8 B | 21 B | 0.6 s |
| Template alert with coordinates, 8-byte tag | 12 B | 9 B | 8 B | 29 B | 0.8 s |
| Packed Hindi sentence, unauthenticated | 12 B | 32 B | — | **44 B** | 1.2 s |
| Packed Hindi sentence, 8-byte tag | 12 B | 32 B | 8 B | 52 B | 1.4 s |
| Packed Hindi sentence, 16-byte tag | 12 B | 32 B | 16 B | 60 B | 1.6 s |
| Plain UTF-8 sentence, unauthenticated | 12 B | 99 B | — | 111 B | 3.0 s |
| Equivalent Opus audio at 6 kbps | — | 2 250 B | — | 2 250 B | 60 s |

> **Quote the authenticated figure.** The 44 B number is the unauthenticated frame. In
> any deployment worth defending, `ENCRYPTED` is set, and the honest figure is 52 B on a
> low-rate link or 60 B on Bluetooth. Against raw PCM that is still 1 600× and against the
> Opus floor it is 43×. Both numbers are defensible; only one of them survives a question
> from a technical jury.
>
> **A note on the 13 B template frame.** The design document quotes "13 B — header plus a
> one-byte message ID" and a 7 385× ratio. With an 11-byte header that was arithmetically
> impossible; with the destination byte removed it is exactly right. The headline figure in
> `docs/source/iTantra.html` is now literally true rather than approximately so.

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

**`TEXT` — packed Hindi sentence, unauthenticated · 44 B**

```
 off   0    1    2  3    4     5  6    7    8    9   10 … 41    42 43
     ┌────┬────┬──────┬─────┬──────┬────┬────┬────┬─────────┬───────┐
     │ A1 │ 11 │ nnnn │ 8A  │ 0020 │ 02 │ 07 │ 03 │ 32 B    │ CRC16 │
     └────┴────┴──────┴─────┴──────┴────┴────┴────┴─────────┴───────┘
       │    │           │      │      │    │    │  packed text
       │    │           │      │      │    │    └ TTL   3 hops
       │    │           │      │      │    └ KEYID derived from the key
       │    │           │      │      └ SRC   node 02 — no destination field
       │    │           │      └ LEN   0x0020 = 32 payload bytes
       │    │           └ FLAGS 0x8A = FINAL | PACKED | CONFIDENCE 2
       │    └ TYPE 1 TEXT · LANG 1 Hindi
       └ MAGIC A · VER 1
```

**`TEXT` — same sentence, AES-256-GCM with a 16-byte tag · 60 B**

```
 off   0    1    2  3    4     5  6    7    8    9   10 … 57    58 59
     ┌────┬────┬──────┬─────┬──────┬────┬────┬────┬─────────┬───────┐
     │ A1 │ 11 │ nnnn │ AA  │ 0030 │ 02 │ 07 │ 03 │ 32 + 16 │ CRC16 │
     └────┴────┴──────┴─────┴──────┴────┴────┴────┴─────────┴───────┘
                        │      │                   ciphertext ‖ tag
                        │      └ LEN counts ciphertext PLUS tag
                        └ FLAGS 0xAA adds ENCRYPTED
```

The nonce is not on the wire. It is derived as `EPOCH ‖ SRC ‖ SEQ ‖ 0x00×5` (§6.2), and
the associated data is the full 10-byte header, so `SRC`, `TYPE` and `FLAGS` are all bound
into the tag.

**`ALERT` — template code with coordinates, 8-byte tag · 29 B**

```
 off   0    1    2  3    4     5  6    7    8    9   10 … 26    27 28
     ┌────┬────┬──────┬─────┬──────┬────┬────┬────┬─────────┬───────┐
     │ A1 │ 21 │ nnnn │ A7  │ 0011 │ 02 │ 07 │ 03 │ 9 + 8   │ CRC16 │
     └────┴────┴──────┴─────┴──────┴────┴────┴────┴─────────┴───────┘
            │           │                         │
            │           │                         └ 01 = template id,
            │           │                           then 8 B position
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
| `TEXT` plain UTF-8 | `1L` | UTF-8 bytes | var | 12 + n | `PACKED` clear |
| `TEXT` packed | `1L` | Packed bytes | var | 12 + n | `PACKED` set |
| `ALERT` | `2L` | As `TEXT` or template | var | 12 + n | Acknowledged and retried |
| `ACK` | `3L` | Acknowledged `SEQ` | 2 | **14 B** | Never relayed |
| `PTT_CTL` | `4L` | `01` seize / `00` release | 1 | **13 B** | Drives the busy indicator |
| `HEARTBEAT` | `5L` | §9 payload | 12 | **24 B** | Every 2 s; never relayed |
| `TEMPLATE` | `6L` | Template id | 1 | **13 B** | Routine template traffic |
| `POSITION` | `7L` | Lat, lon (§10) | 8 | **20 B** | |
| `AUDIO_FB` | `8L` | Opus frame | var | 12 + n | Wi-Fi only; refused elsewhere |

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
- Both are distributed with the shared key at pairing and stored alongside it.
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
recogniser's own confidence for the utterance is high.

> **What that threshold means in practice.** Similarity is `1 - distance / max(tokens)`, so
> a single misrecognised word only survives in a sentence of **seven tokens or more**:
> 1 − 1/7 = 0.857 passes, 1 − 1/6 = 0.833 does not. Most operational sentences are shorter,
> so a template code in practice requires near-exact recognition, and the system falls back
> to Level 2 more often than the threshold alone suggests. That is the correct side to err
> on — script packing costs a few bytes, whereas matching the wrong template speaks the
> wrong sentence at maximum volume. Both conditions are required: a
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
- **Key:** 256-bit pre-shared key, generated by the first unit, distributed by QR
  at provisioning, stored in Android Keystore, never written to DataStore, a file, or a
  log.
- **Plaintext:** the payload only.
- **Associated data:** the 10-byte header **with the `TTL` byte set to zero**. This binds
  `SRC`, `KEYID`, `TYPE`, `SEQ`, `LEN`, `LANG` and `FLAGS` into the authentication tag, so
  none of them can be altered by an attacker without invalidating the frame. In particular
  the sender identity is authenticated, which is what defeats impersonation (risk S-01).

  > **Amended 2026-09-06.** The TTL is the one header field a relay legitimately changes,
  > and a relay does not hold the key — it forwards what it heard. With the TTL bound, every
  > relayed frame failed verification at the next hop and multi-hop delivery (§8, *Relay*)
  > never worked. Zeroing that byte in the associated data lets a relay decrement it and
  > nothing else. A relay that altered any other byte still produces a frame that fails.
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
- `EPOCH` is announced in a **hello** (§9) so a joining or restarting receiver learns it
  without a handshake, and is otherwise **discovered**: the receiver tries candidate epochs
  — the last one verified for that sender, the announced one, its own and its neighbours,
  then a bounded range from zero — and only a candidate whose tag verifies is accepted. A
  hint is never believed on its own.
- `EPOCH` is **seeded from the clock**: a start never issues an epoch below the number of
  minutes since 2026-01-01T00:00Z. A reinstall wipes the persisted counter, and a counter
  back at zero would both reuse nonces already used under this key and be refused by every
  peer as a replay. With the seed, a reinstalled unit's epoch is still higher than anything
  it sent before. A handset whose clock reads before the origin falls back to the counter.
- This guarantees the `(key, nonce)` pair is never reused, which is the one failure mode
  that destroys GCM entirely.

**Rekeying.** A shared key MUST be rotated if `EPOCH` would exceed `0xFFFFFFFF`, if a device
is lost, or on any change to the set of paired units. Rotation is a re-provisioning: a new QR
code.

### 6.3 Tag length

Tag length is a per-key configuration value negotiated at provisioning, chosen by
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
interface MUST display a permanent unmistakable **UNSECURED** banner while the units are
paired that way. There is no silent path to unauthenticated operation.

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

**Every frame goes to every unit. There is no destination field and no private message.**
The problem statement asks for something that "should work like a walkie talkie", and a
walkie-talkie has no address book — if you can hear the channel, you hear everything on it.
Selection is by key, not by address: hold the key and you are on the net, or you are not.

```kotlin
fun accept(f: Frame): Boolean =
    f.keyId == myKeyId            // cheap reject before AEAD; the tag is the real test
```

One byte, one comparison, and it is only an optimisation — a frame that passes it still has
to produce a valid authentication tag. Removing `DST` removed a byte from every frame, two
branches from the receive path, and one screen from the application.

### Reserved node identifiers

| Value | Meaning |
| --- | --- |
| `0x00` | Unassigned — a device that has not completed provisioning. MUST NOT transmit |
| `0x01` | First unit, conventionally the base station |
| `0x02`–`0xFD` | Assigned nodes |
| `0xFE` | Reserved for gateway or relay hardware |
| `0xFF` | Reserved. Formerly the broadcast destination; every frame is now broadcast, so no unit may claim it |

### Relay

Where a device cannot reach its destination directly, intermediate devices forward the
frame and decrement `TTL`. A seen-set of recent `(SRC, EPOCH, SEQ)` triples suppresses
duplicates; without it, three mutually visible devices generate an unbounded broadcast
storm (risk T-10).

```kotlin
if (f.ttl > 0 && seen.add(Triple(f.src, f.epoch, f.seq)))
    link.send(f.copy(ttl = f.ttl - 1))
```

The seen-set is bounded at 512 entries with LRU eviction, and forwarding is delayed by a
random 0–50 ms to avoid synchronised collisions. Relaying is performed on the **sealed
frame as received** — a relay does not decrypt, cannot decrypt unless it is paired,
and MUST NOT re-seal. `TTL` therefore sits outside the authenticated header for relay
purposes; see the note below.

> **Known limitation, stated deliberately.** `TTL` is inside the associated data, so
> decrementing it invalidates the tag. Version 1 resolves this by having the relay forward
> the frame **unmodified** and rely on the seen-set alone for loop suppression, accepting a
> bounded flood within one paired set. A future version will move `TTL` outside the
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

> **Amended 2026-09-06 — the hello.** A heartbeat sealed with the sender's epoch cannot
> tell a receiver what that epoch is, which is the one thing the receiver cannot otherwise
> know; that circularity is why the 12-byte payload above was never sent. What is sent
> instead is a **hello**: type `HEARTBEAT`, `ENCRYPTED` clear, `TTL` 0, payload the 4-byte
> `EPOCH` alone, every 5 s and once on start. It is not authenticated and is not trusted:
> a receiver uses it only as the first candidate when verifying that sender's next frame
> (§6.2). A forged hello costs the receiver one wasted tag check. It is never relayed, and
> it carries no presence, battery or state — a unit is "heard from" only by an
> authenticated frame.

> **Amended 2026-09-07 — presence and locate.** Two sealed control frames now exist:
>
> - **Presence**: type `HEARTBEAT` with `ENCRYPTED` set (the flag is what tells it from
>   the hello). Payload: version, flags, the unit's **name** (UTF-8, at most 24 bytes),
>   optionally its **position** (latitude and longitude as signed 32-bit micro-degrees,
>   accuracy in metres, age in seconds), and battery. Sent every 10 s, and every second
>   while the unit is beaconing for a locator. Never relayed. A receiver counts a unit as
>   present for 35 s after its last authenticated frame, which is what the "N units"
>   figure means.
> - **Locate**: type `POSITION`, sealed, relayed. Payload: version, command (start or
>   stop), target node id. The target answers by beaconing its presence with position every
>   second for ten minutes, renewed while the locator keeps asking. No consent dialog: the
>   request is authenticated under the net's key.
>
> Position is sent **only** while beaconing or locating, and only inside these sealed
> frames. `core-proto/Presence.kt`, `SessionPresenceTest`.
>
> **The arrow, on the receiving side.** The locator draws the target's true bearing (from
> the two positions) against the handset's own heading. The heading is the platform's
> rotation vector — magnetometer, accelerometer and gyroscope fused — read as the direction
> the phone is *pointing*: its top edge when flat, the back of the phone when held up,
> blended by how far it is raised, so the arrow is right at every angle a phone is held at
> and does not swing as it is lifted. Only the bearing, which jumps with each position fix,
> is smoothed; the compass is followed on every reading, fifty a second. The magnetometer's
> own accuracy flag is surfaced as a calibration note, and the two headings are printed in
> degrees under the arrow so it can be checked against a map. `app/platform/Heading.kt`,
> `HeadingTest`. The arrow turns with the compass on every reading whether or not it has a
> target: at the unit when both fixes are fresh and further apart than their combined
> error, at north otherwise, with the caption saying which. Within that error the siren,
> from signal strength, takes over, and the figure is given in centimetres under three
> metres with the spread of the recent readings beside it. Indoors, with no fix on either
> side, there is one more direction source: the operator's own body, which takes ten to
> twenty decibels out of a Bluetooth signal it stands in the way of. Every reading is
> tagged with the compass heading it arrived at, and once a turn on the spot has covered
> most of the circle the power-weighted circular mean of those headings is the arrow
> ("SIGNAL STRONGEST THIS WAY"), with its width from how sharply the signal peaked. The
> screen shows the degrees covered while the operator turns. `Locator.sweepOf`,
> `LocatorSweepTest`.

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

- The sender splits the **sealed** payload into chunks of at most `mtu - 12` bytes.
- Every fragment except the last sets `FRAGMENT`.
- All fragments of one message share `SEQ`; fragment order is transmission order.
- The receiver reassembles into a buffer capped at 1024 B and discards the partial message
  if the next fragment does not arrive within 2 s.
- AEAD verification happens **after** reassembly, on the complete payload. The
  implementation did this the other way round until 2026-09-06 and no fragmented message
  ever arrived; `SessionPathTest` now sends one through a 64-byte link.
- The sender sizes fragments to the link's MTU **as it is at the time of sending** — the
  narrowest peer that is up — not to a figure fixed when the session was built.
- A fragmented message is relayed as its fragments, each once, after the whole has been
  verified. The relay seen-set (§8) keys on the fragment index as well as `(SRC, EPOCH,
  SEQ)`, or the second fragment would be suppressed as a duplicate of the first.

---

## 12. Reliability

| Mechanism | Applies to | Behaviour |
| --- | --- | --- |
| CRC-16 | All frames | Corrupt frames are discarded, never spoken. A garbled instruction is more dangerous than a missing one |
| Acknowledge and retry | `ALERT` only | Every unit that accepts an `ALERT` sends an `ACK`, so the sender receives several. The alert counts as delivered on the **first** ack; retries stop then, up to 3 at 300 ms. The interface shows `3 of 6 units`, which is better information than a single tick. Ordinary conversation is fire-and-forget — retransmitting stale speech is worse than losing it |
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
