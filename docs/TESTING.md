# Testing

The test strategy follows the dependency graph: everything that can be tested without a
handset is tested without a handset, because a test that needs two paired phones will not
be run often enough to catch anything.

## 1. Layers

| Layer | Where | What | Runs |
| --- | --- | --- | --- |
| Unit — pure JVM | `core-proto` | Frame codec, CRC, script packing, template matching, replay window, AEAD | Every commit, seconds |
| Unit — JVM | `core-tts` | Normalisation rules, clause splitting | Every commit |
| Unit — JVM | `core-asr` | Endpointing state machine, confidence quantisation, VAD tier logic | Every commit |
| Fuzz | `core-proto` | Decoder against malformed input | Nightly, 10⁶ inputs |
| Instrumented | `core-audio`, `core-link` | Audio routing, alert policy, socket framing | Every merge to `main`, on the target handset |
| Integration | `app` | Loopback link: encode → decode in-process, full pipeline without radios | Every merge |
| Two-device manual | — | Scripted scenarios, §5 | Weekly, and before every demonstration |
| Soak | — | Thermal, four-device relay, endurance | Weekly from week 4 |
| Benchmark | `bench` | WER, RTF, latency, resource | Weekly from week 4, and for the final scorecard |

`core-proto` has no Android dependency specifically so that the highest-risk code in the
project — the frame codec — is testable in milliseconds on a laptop.

## 2. Protocol tests

The conformance checklist in
[PROTOCOL.md §14](PROTOCOL.md#14-conformance-checklist) maps one-to-one onto test cases.
Beyond it:

### Property tests

| Property | Corpus |
| --- | --- |
| `decode(encode(f)) == f` | 10 000 randomly generated frames across all types, flags, lengths |
| `unpack(pack(s, lang)) == s` | Per language: benchmark transcripts, plus a generated corpus containing every codepoint in the block, ASCII, ZWJ/ZWNJ, and surrogate pairs |
| Packed size ≤ UTF-8 size | Every string in the benchmark transcripts. A regression here means the escape rule is firing too often |
| Nonce uniqueness | Simulated 10⁷ frames across `SEQ` wraps and service restarts; assert no `(key, nonce)` repeat |

### Fuzzing the decoder

Risk T-08. The decoder is fed:

- Truncated frames at every possible cut point
- Two frames interleaved byte-wise
- Random bit flips at every position, at 1, 2 and 8 flips
- `LEN` fields claiming 0, 1, 1023, 1024, 1025, 65535
- Streams with no `0xA1` sentinel for megabytes
- Valid frames with a trailing partial frame

**Pass criterion:** no uncaught exception, no unbounded allocation, no socket closure, and
correct recovery on the next valid frame. A decoder that throws on bad input produces a
system that works on a desk and fails under load.

## 3. Audio and alert tests

Instrumented, on the target handset, because vendor audio policy varies and an emulator
proves nothing here.

| Test | Assertion |
| --- | --- |
| Alert on locked handset | Audio plays at max alarm volume, screen wakes, vibration fires |
| Alert with ringer silenced | Audio still plays |
| Alert in Do Not Disturb | Audio still plays |
| Alert during music playback | Music ducks or pauses; alert is audible |
| Alert during a phone call | Documented behaviour; the platform wins, and the alert is queued and repeated after |
| Volume restoration | Stream volume returns to its prior value after the alert |
| Focus loss ignored | A competing focus request does not stop alert playback |
| Mic pre-empted by a call | State goes `DEGRADED` with a reason; recovers automatically on release |

The volume restoration test exists because forgetting it leaves the handset permanently at
maximum alarm volume — a defect certain to be discovered during a demonstration.

## 4. Latency regression

A CI job runs the loopback integration test and asserts stage budgets against
[EVALUATION.md §4](EVALUATION.md#4-latency--20--of-the-mark). A commit that pushes any
stage above its budget fails the build.

Loopback figures are not reportable — they lack the radio and the real device — but they
catch a 3× regression the day it lands rather than the week of the demonstration.

## 5. Two-device manual scenarios

Run weekly and before every demonstration. Each has a pass criterion, and results are
recorded with date, build and device.

| # | Scenario | Pass criterion |
| --- | --- | --- |
| M1 | PTT, Hindi, clean room, 10 utterances | All 10 delivered and intelligible; median end-to-end < 800 ms |
| M2 | Phone mode, two-way conversation, 2 minutes | No deadlock, barge-in works, no audio gap mid-sentence |
| M3 | Alert to locked, silenced handset | Announces at full volume; volume restored after |
| M4 | Cross-language template: Tamil sender, Hindi receiver | Correct Hindi sentence spoken |
| M5 | Language switch mid-session | Completes < 2 s; no crash; next message correct |
| M6 | Walk out of range and back | `DEGRADED` shown, store-and-forward queues, flushes in order on reconnect |
| M7 | Simultaneous PTT press | Both told busy; no garbled transmission; backoff resolves |
| M8 | Transport switch BT → BLE → Wi-Fi | Each reconnects without restarting the app |
| M9 | Airplane mode, Bluetooth only | Full loop works; this is demonstration step 1 |
| M10 | Kill the app on one device, restart | Reconnects automatically; `EPOCH` incremented; no replay rejection of new frames |
| M11 | Noisy environment (crowd recording at ~10 dB SNR) | Critical terms survive; template fallback engages where confidence drops |
| M12 | Four devices, relay, one out of direct range | Message arrives once, not repeatedly; no broadcast storm |

M10 and M12 are the ones most likely to be skipped and most likely to fail. Do not skip
them.

## 6. Soak tests

| Test | Duration | Assertion |
| --- | --- | --- |
| Thermal | 30 min continuous inference | RTF at the end < 2× RTF at the start; the system never enters `ERROR`; sustained figures recorded |
| Endurance | 8 h, BLE, screen off, listening | Battery drain permits > 8 h; no memory growth; no dropped connection unrecovered |
| Relay | 4 devices, 1 h | No broadcast storm; seen-set bounded; every message arrives exactly once |
| Memory | 1 h continuous use | Resident memory flat; no leak in the capture ring or the outbox |

Thermal soak begins in **week 6** and runs weekly thereafter (risk T-03). Finding thermal
throttling in week 8 is finding it too late.

## 7. Continuous integration

| Stage | Gate |
| --- | --- |
| Build | All modules compile; dependency rules enforced |
| Lint and format | ktlint, Android Lint; warnings are errors in `core-proto` |
| Unit tests | All JVM tests pass |
| Coverage | `core-proto` ≥ 90 % line coverage. Other modules are not gated on coverage |
| Licence audit | Every dependency appears in `LICENSES.md`; a new dependency without an entry fails the build (risk P-04) |
| Secret scan | No key material, keystore, or credential in the diff |
| Normalisation suite | 100 % pass, all languages |
| Latency regression | Loopback stage budgets |
| Nightly | Fuzz 10⁶ inputs; instrumented tests on the target handset |

The licence audit gate is what makes P-04 a low risk rather than a week-8 emergency. It
costs nothing and it cannot be forgotten.

## 8. Test data

| Asset | Source | Use |
| --- | --- | --- |
| Benchmark audio | IndicSUPERB, Kathbath, IndicVoices, FLEURS | WER measurement |
| Noise set | Crowd, wind, engine, siren | SNR mixing at a fixed seed |
| Normalisation fixtures | Hand-written, ≥ 60 cases per language | Regression suite |
| Frame corpus | Generated + captured from real sessions | Codec property tests |
| Fuzz corpus | Generated, plus every input that ever caused a failure | Regression against past defects |

Any input that causes a failure is added to the corpus permanently. That is how the fuzz
corpus becomes more valuable than the fuzzer.
