# Running it on handsets

What works on real phones today, how to get it there, and — as precisely as possible —
what does **not** work yet and why.

## 1. What this build actually does

Install on two or more Android phones, bond them in Bluetooth settings, open the app.
Pressing the transmit control sends a message that travels the **entire real path**:

```
  template match ─► seal (AES-256-GCM, tag length chosen by the transport)
                 ─► frame (12 B header + CRC) ─► RFCOMM
                 ─► CRC ─► KEYID ─► AEAD verify ─► replay window
                 ─► render in the RECEIVER'S language ─► band E
```

Every unit receives every frame — there is no destination field — and a unit out of direct
range is reached by another rebroadcasting, TTL 3.

**What is not in this build:** speech, at either end. There are no acoustic model or voice
files (`models/asr/` is empty, every hash in `models/manifest.json` is still a placeholder),
so the transmit control sends a **template code** rather than something you said, and an
arriving message is **displayed** rather than spoken. Band F's frame size is real; its
latency figures stay as `—` until there is a recogniser to time.

That is the transport, the cryptography and the cross-language delivery, proven on real
radios. It is most of the system and it is not the whole of it.

## 2. Build and install

```
tools/fetch_sherpa.sh                 # once, ~47 MB
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Install the **same APK** on every handset. Different builds mean different template tables,
and a table mismatch is a safety defect rather than an inconvenience — see
[PROTOCOL.md §5.2](PROTOCOL.md#52-profile-binding).

## 3. Before opening the app

1. **Bond the handsets in Android's Bluetooth settings**, every pair, before launching.
   RFCOMM connects to bonded devices only; the app does not pair them for you (that is
   W6.11).
2. Grant **Nearby devices** and **Microphone** when asked. Microphone is not used yet and is
   requested once so the permission flow is exercised.
3. Bluetooth on, aeroplane mode **on** if you want to prove there is no network involved.

## 4. What you should see

| On screen | Meaning |
| --- | --- |
| `BASE · node 07` | This unit's id, derived from the installation id. **Every handset must show a different number** |
| `● LINK OK` | At least one peer is connected |
| `2 units` | Peers currently reachable |
| Band F `29 B 3310×` | The last frame's real size, and its ratio against three seconds of audio |

Press **PUSH TO TALK** on one handset. Within about a second the sentence appears in band E
on the others, and band F shows the frame size on both.

### The one worth showing a jury

Tap the language in band B on one handset until it reads **தமிழ்**. Leave the other on
**हिन्दी**. Now press transmit.

The same byte arrives and each handset renders it in **its own** language. Nothing
translated anything — both hold the same 24-sentence table in ten languages, and the sender
transmitted one byte of payload. That is the cross-language claim, demonstrated rather than
asserted.

### Three or more handsets

Bluetooth Classic carries one RFCOMM connection per socket, so with three or more units not
every pair will hold a direct link. That is not a failure — it is where **relay** earns its
place. A message from C that cannot reach A directly arrives via B, and the seen-set stops
it looping. Watch band A: a unit can show `LINK OK` with fewer peers than there are handsets
in the room and still receive everything.

## 5. What will not work, and why

| Symptom | Cause |
| --- | --- |
| `NO LINK` forever | The handsets are not bonded in Bluetooth settings, or the permission was denied |
| Two handsets show the **same** node number | A 1-in-254 id collision. Both drop each other's frames as their own transmission. Clear app data on one to re-derive |
| Nothing is spoken aloud | Expected. No voice models — see §1 |
| Band F latency shows `—` | Expected. Nothing measures a stage yet |
| A message appears twice | Should not happen; the replay window and relay seen-set both prevent it. If it does, capture the frame and add it to the fuzz corpus (C.7) |

## 6. What is unsafe about this build

**The key is not a secret.** Until pairing exists (W6.11) every unit built from this
repository holds the same fixed development key, and it is written in
`NodeIdentity.developmentKey()` in plain sight. Frames are authenticated against corruption
and against nothing else. Do not use this build anywhere real, and do not describe it as
secure — `SECURITY.md` audit item 5 stays open for exactly this reason.

## 7. Where the numbers come from

Nothing on screen is typed in. The frame size in band F is `wire.size` from the frame that
was actually transmitted, and the ratio beside it is that against 96 000 B — three seconds
of 16 kHz 16-bit mono. The same four figures are computed from the frame codec in
`bench` `CompressionRatios` and checked against `docs/` by `tools/check_doc_numbers.py`.
