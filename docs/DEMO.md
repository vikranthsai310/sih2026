# Demonstration runbook

Seven minutes, rehearsed to a fixed sequence. Each step answers a question the jury is
already holding. The script is fixed in week 8 and **not improvised**.

## 1. The sequence

| # | Action | Question it answers | Time |
| --- | --- | --- | --- |
| 1 | Both handsets on the table, **aeroplane mode visibly enabled**, Bluetooth only | Is this really offline? Settled in three seconds, before any claim is made | 0:20 |
| 2 | Hold transmit; speak a Tamil sentence. The second handset speaks it in Tamil | Does the core loop work? | 0:40 |
| 3 | Display the byte counter: transmitted 44 B, equivalent audio 96 000 B, ratio 2 182× | What have you actually achieved? The compression figure, **measured live** rather than asserted | 0:40 |
| 4 | Point to the permanent latency strip: STT 210 ms, link 40 ms, TTS 180 ms, total 780 ms | How fast is it? Instrumentation on screen, not on a slide | 0:30 |
| 5 | Lock the second handset and set it to silent. Send an alert. It wakes and announces at full volume | Does the alert requirement work as specified? | 0:50 |
| 6 | Release push-to-talk. Hold an ordinary two-way conversation with no button | Does telephone mode work? | 0:50 |
| 7 | Switch the receiving handset to Hindi. Send a template alert in Tamil; it is announced in Hindi | What else does the architecture give you? Cross-language operation with no translation model | 0:50 |
| 8 | **If hardware permits:** disable Bluetooth between the handsets; route through the LoRa pair across the room | Does this work on a real radio link? The strongest single moment available | 1:00 |
| 9 | Close on the scorecard: application size, memory, idle CPU, WER per language at four SNRs, RTF, latency distribution — all measured on an entry-tier handset | Can you prove any of it? | 1:00 |

Total 6:40, leaving twenty seconds of slack. Do not add a tenth step.

## 2. Why this order

Step 1 settles the offline claim **before** any claim is made, so nothing that follows is
suspected. Step 2 proves the loop exists before any number is quoted — a number about a
system nobody has seen work is worthless. Steps 3 and 4 are the two figures the rubric
weights most heavily, delivered while the loop is still fresh. Step 5 discharges a specific
written requirement (R8) in a way that cannot be faked. Steps 6 and 7 are breadth. Step 8
is the differentiator. Step 9 converts the whole thing from a demonstration into evidence.

If the session is cut short, the priority order is **1, 2, 3, 5, 9**. Those five answer
"is it real, does it work, how much does it compress, does the alert requirement work, can
you prove it" — which is the whole assessment.

## 3. Equipment

| Item | Quantity | Note |
| --- | --- | --- |
| Target handset | 2 | Both with the **identical APK**; checksum verified |
| Spare handset | 1 | Already paired, charged, in the bag (P-03) |
| LoRa nodes | 2 | Charged, pre-paired over Bluetooth SPP, antennas fitted |
| Power banks | 2 | Handsets at 100 %, not charging during the demo — charging changes thermal behaviour |
| Printed scorecard | 2 copies | For step 9, in case the projector fails |
| QR provisioning card | 1 | Printed fallback if re-pairing is needed |

## 4. Pre-flight checklist

Run 30 minutes before. Every item has failed for someone.

- [ ] Both handsets charged above 80 %, not plugged in
- [ ] Identical APK checksum on both handsets
- [ ] Handsets **already paired** in Bluetooth settings and already paired with each other
- [ ] `LINK OK` showing on both, each visible in the other's roster
- [ ] Aeroplane mode toggled on and the loop verified once, fully, end to end
- [ ] Alert test run on the receiving handset — vendor audio policy verified on *this* device
- [ ] Both handsets set to the languages used in steps 2 and 7
- [ ] Latency strip visible and updating
- [ ] Byte counter reset to zero
- [ ] Notifications, updates and battery saver disabled on both handsets
- [ ] Screen timeout set to 10 minutes
- [ ] LoRa nodes powered, paired, and one message verified across the room
- [ ] Scorecard CSVs exported and the printed copies in hand
- [ ] Spare handset powered on and provisioned
- [ ] **Thermal:** handsets idle-cool, not just used for a rehearsal five minutes earlier

The last item matters more than it looks. A handset that has just run a rehearsal is warm,
and warm handsets throttle. Rehearse, then let them rest.

## 5. Failure recovery

Rehearse these too. A recovery performed calmly reads as competence; the same recovery
improvised reads as a broken system.

| Failure | Recovery | Fallback |
| --- | --- | --- |
| Link does not connect | Toggle transport in settings — it reconnects in ~3 s | Swap in the spare handset, already provisioned |
| Pairing lost | Show the QR, scan it — 10 s | Printed QR card |
| Recognition wrong in a noisy hall | Say the sentence again, closer. **Then point at the confidence indicator and the noise-SNR row of the scorecard** | Turn a failure into the noise-evaluation talking point — this is a strength, not a save |
| Alert does not fire | Run the alert test from settings on the receiving handset | Video of the locked-handset test, recorded in week 5 |
| LoRa link fails | Skip step 8, say so plainly, move to step 9 | Never debug hardware in front of a jury |
| App crashes | Restart — the service reloads in under 2 s and reconnects automatically | Spare handset |
| Complete failure of both handsets | Move to step 9 with the printed scorecard and the recorded video | The measurements stand on their own |

**Rule: never debug on stage.** Move to the next step, or to the scorecard. A team that
keeps moving looks prepared; a team crouched over a phone does not.

## 6. Questions to have answers ready for

| Question | Answer |
| --- | --- |
| "Why not just use a codec?" | Opus bottoms out at 6 kbps. The link is 300 bps. It is a bound, not an engineering gap — and against Opus we are still 42× smaller |
| "Isn't 2 182× misleading — what about encryption?" | Correct, and the honest figure is 1 600× authenticated, 52 B on the wire. Both are in the scorecard |
| "What if the recogniser is wrong?" | Confidence is on the wire and on screen; the sender sees the text before it sends; alerts require confirmation; negations are weighted in the biasing lexicon |
| "Does the cross-language feature translate anything?" | No, and we do not claim it does. It works for template-coded messages only, because both devices hold the same table in ten languages. Free-form messages are delivered in the language spoken |
| "How is this different from Meshtastic?" | Meshtastic sends typed text. The entire point here is that the operator never types or reads — which is what makes it usable by someone who cannot |
| "What is your worst language?" | Name it, give the number, give the reason and the mitigation. Answering this well is worth more than a uniform set of suspiciously similar figures |
| "Did you measure this on a flagship?" | No. Every figure names the entry-tier device it came from and the soak duration |
| "Is it really offline?" | The APK declares no `INTERNET` permission. The platform enforces it; we do not merely assert it |

## 7. Rehearsal log

Run the full sequence three times without intervention before the session. Record each
run.

| Run | Date | Steps completed | Failures | End-to-end median | Notes |
| --- | --- | --- | --- | --- | --- |
| 1 | | | | | |
| 2 | | | | | |
| 3 | | | | | |
