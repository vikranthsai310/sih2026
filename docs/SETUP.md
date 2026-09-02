# Development setup

## 1. Toolchain

Pin these. A team that is not on the same toolchain spends week 8 debugging build
differences.

| Tool | Version | Note |
| --- | --- | --- |
| JDK | 17 (Temurin) | Required by AGP 8.x |
| Android Studio | Ladybug or later | |
| Android Gradle Plugin | 8.7.x | |
| Gradle | 8.10.x | Wrapper is committed; never run a local `gradle` |
| Kotlin | 2.0.x | |
| compileSdk / targetSdk | 35 | |
| minSdk | 26 | See [ARCHITECTURE.md §7](ARCHITECTURE.md#7-technology-decisions) |
| NDK | Only if building sherpa-onnx from source | The prebuilt AAR is preferred |
| Python | 3.10+ | Model export and quantisation only, never at runtime |

### Python environment for `tools/`

```bash
python -m venv .venv
source .venv/bin/activate          # Windows: .venv\Scripts\activate
pip install -r tools/requirements.txt
```

## 2. Target hardware

| Item | Requirement |
| --- | --- |
| **Primary target handset** | 4 GB RAM, entry-tier Snapdragon 4-series or MediaTek Helio G-series, Android 11+ |
| Second handset | Any Android 8+ device, for the two-device loop |
| Third handset | Pre-configured spare for the demonstration (risk P-03) |
| LoRa pair | 2 × ESP32 + SX1276/SX1278 at 865.5 MHz, ~₹1 500 |

> **Acquire the target handset in week 1.** Risk P-02: developing on a flagship conceals
> performance failures, and every published number must come from this device. A team that
> discovers in week 7 that its flagship figures do not hold on entry-tier hardware has lost
> the efficiency and latency marks it thought it had.

Record the exact model here once acquired — every scorecard names it:

```
Target device:  ____________________   (model, SoC, RAM, Android version)
Acquired:       ____________________
```

## 3. First build

```bash
git clone https://github.com/vikranthsai310/sih2026.git
cd sih2026
./gradlew assembleDebug
./gradlew :core-proto:test          # fast, no device needed
```

`core-proto` tests run without an emulator and without a handset. If they do not pass,
nothing else is worth trying.

## 4. Models

Model binaries are **not** in version control — only `models/manifest.json` and the
checksums. Fetch them:

```bash
python tools/fetch_models.py --lang hi,en          # week 1: Vosk + Piper
python tools/fetch_models.py --lang all            # week 7: all ten
```

The script downloads to `models/<lang>/`, verifies SHA-256 against the manifest, and
refuses to install a pack whose checksum does not match. Pushing model binaries to the
repository will exceed GitHub's file size limit and is blocked by `.gitignore`; if you find
yourself fighting it, you are doing the wrong thing.

Export and quantisation of production models is a separate procedure in
[MODELS.md §5](MODELS.md#5-export-and-quantisation-pipeline).

## 5. Running on a device

```bash
./gradlew installDebug
adb shell am start -n in.itantra/.MainActivity
adb logcat -s iTantra:V                             # tagged logging only
```

Grant `RECORD_AUDIO`, `BLUETOOTH_CONNECT` and `BLUETOOTH_SCAN` on first launch. The app
requests no location and no internet permission — if a build ever asks for either,
something has gone wrong and it is a release blocker, not a nuisance.

### Two-device bring-up

1. Install the **same APK** on both handsets — verify with `adb shell pm path` and compare
   checksums. Symmetry (R9) is a requirement, not an accident.
2. Pair the handsets in Android Bluetooth settings **before** launching.
3. On device A: create group, display QR. On device B: scan it.
4. Both should show `LINK OK` and each other in the roster within 5 s.

## 6. Profiling

| What | Tool | Command |
| --- | --- | --- |
| Idle CPU | Perfetto | `record_android_trace -o idle.perfetto -t 600s sched freq idle` |
| Active CPU | Perfetto | Same, while speaking continuously |
| Memory | Android Studio Memory Profiler | Sustained load, one language resident |
| Battery | `batterystats` | `adb shell dumpsys batterystats --reset`, run 8 h, then `--charged` |
| Thermal | `dumpsys thermalservice` | Sample during the 30-minute soak |
| Latency | The app itself | Metrics screen → export CSV → `adb pull` |

Measurement conditions are normative and are in
[EVALUATION.md §1](EVALUATION.md#1-measurement-conditions). Figures taken outside those
conditions are not reportable.

## 7. Code style

- ktlint, enforced in CI. Warnings are errors in `core-proto`.
- Public API in `core-*` modules carries KDoc. Internals do not need it.
- No logging on the capture thread, ever — no string formatting, no allocation. This is a
  correctness constraint, not a style preference.
- Requirement identifiers (`R3`), risk identifiers (`T-08`) and message type names may be
  cited directly in comments and commit messages; they are stable across the whole
  document set.

## 8. Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| RFCOMM connects then throughput collapses | Bluetooth discovery still running | `adapter.cancelDiscovery()` before `connect()` |
| Frames arrive corrupted under load | Missing `readFully` loop | [PROTOCOL.md §13](PROTOCOL.md#13-stream-framing) |
| First transmission is silent or truncated | Models not yet resident | Transmit must stay disabled until state is `READY` (T-11) |
| Wi-Fi Direct never discovers a peer | `WifiP2pManager` vendor inconsistency | Use the hosted-network path (T-09) |
| RTF doubles after ten minutes | Thermal throttling | Expected. Report sustained figures; reduce thread count under thermal pressure (T-03) |
| Alert is silent on one specific handset | Vendor audio policy | Run the alert test in settings on that device; check all six steps of [UX.md §3](UX.md#3-alert-delivery) |
| AEAD verification fails after a restart | `EPOCH` not persisted or not incremented | This is correctness-critical; see [SECURITY.md §4](SECURITY.md#4-cryptographic-detail) |
