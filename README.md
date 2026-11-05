# IRAQ TRANSLATE 1.0.5

Android TV speech-to-Arabic subtitle source project based on the reverse-engineering findings from `AI-Translator(2).apk`.

## Audio capture modes

1. **ADB Echo Reference (preferred when firmware grants access):** Pair with the TV's Wireless debugging service, connect to its ADB port, then start the ADB Echo Reference translation. The app launches `ShellAudioHelper` with `app_process` under the ADB shell UID. The helper requests source 1997 (`ECHO_REFERENCE`) and streams 48 kHz stereo PCM32 over ADB. Android reserves this source behind the system-only `CAPTURE_AUDIO_OUTPUT` permission; the TV firmware must grant it to the ADB shell UID. It does not use a microphone or write audio files.
2. **Android playback-capture fallback:** Uses MediaProjection `AudioPlaybackCaptureConfiguration` restricted to media and game playback. Android requires `RECORD_AUDIO` permission as an authorization gate, but this code does not select `MIC` and does not capture screen frames.

## Setup

1. Install on Android TV (Android 10+).
2. Download the Sherpa-ONNX Whisper-tiny language-identification model.
3. Download Vosk models for the source languages expected in the TV stream. Automatic language detection selects among installed models.
4. On the TV, enable Developer options → Wireless debugging.
5. Enter the TV IP, pair port, and six-digit pairing code; then connect using the separate connection port. For legacy TCP debugging, connect to port 5555 after enabling it from a trusted ADB host.
6. Start ADB Echo Reference translation. If the firmware denies Echo Reference to the shell UID, use the explicit Android playback-capture fallback.
7. ADB grants a powerful shell on the paired device. Pair only on a trusted network and turn Wireless debugging off after testing.
8. Grant overlay permission and enable the Accessibility service for F1 then 3.

## Model downloads

- Sherpa model files: Hugging Face `csukuangfj/sherpa-onnx-whisper-tiny`.
- Vosk model files: official Alpha Cephei model archive URLs.
- Arabic uses `vosk-model-ar-mgb2-0.4`; `vosk-model-small-ar-0.3` appears as a string in the target APK but is not listed in the current official Vosk model catalog.

## Build

Open this project in Android Studio with JDK 17 and Android SDK 35. Dependencies include Vosk Android, Sherpa-ONNX Android 1.12.15, LibADB Android 3.1.1, Conscrypt, and the X.509 helper. The first Gradle `preBuild` downloads the Sherpa 1.12.15 AAR from a pinned Hugging Face revision and verifies SHA-256 before compilation; the AAR is not duplicated inside the source ZIP. If Android Studio sync does not resolve the local AAR before the first build, run `scripts/download_sherpa_aar.sh` (Linux/macOS) or `scripts/download_sherpa_aar.ps1` (Windows), then sync again. The Gradle `preBuild` task also downloads and verifies it automatically during a build.

The source has not been built in this environment because Android SDK and Gradle are unavailable. Device-specific ADB/Echo Reference access must be tested on the actual TV firmware.

See `REVERSE_ENGINEERING_APPLIED.md` for evidence mapping, limitations, and the list of remaining verification work.

## Automatic movie/episode subtitle lookup (1.0.4)
During playback, recognized dialogue is accumulated into a rolling window and sent to Wyzie Quote to identify the movie or episode and its dialogue timestamp. The app searches Arabic subtitles through Wyzie, OpenSubtitles.com, SubDL, then SubtitleCat. SubtitleCat is accessed through its public HTML search/detail pages because no public API is documented; this adapter may break if the site changes markup or blocks automated requests.

Playback-only VAD produces speech intervals. With a Wyzie Pro key, these intervals are sent to Wyzie Synced, which selects a matching Arabic subtitle and returns offset/FPS-corrected timing. If Pro sync is unavailable, the app uses repeated quote matches to estimate an affine playback timebase and correct gradual drift. If no external subtitle is found, live speech recognition and Arabic translation continue as fallback.

Configure provider keys in the app's **Automatic movie / episode subtitles** section. Wyzie free keys can perform quote/search operations; Wyzie Synced requires Pro (each successful sync costs 5 requests, plus a separate download request). SubDL requires its API key; OpenSubtitles.com requires an application API key. Keys are encrypted with AES-GCM using Android Keystore and are not bundled in the APK. Do not share keys or publish them in source control.

Provider availability and Arabic subtitle coverage are not guaranteed. The VAD is a lightweight energy/zero-crossing detector, not a neural VAD. Device/provider testing and APK compilation remain outstanding.


## Automatic subtitle alignment (1.0.4)

- Streaming 16 kHz playback-only VAD creates speech intervals (20 ms frames, adaptive noise floor, zero-crossing gate, 300 ms hangover, 160 ms minimum speech). It does not open or select the microphone.
- Speech intervals are retained in the current capture session and passed to Wyzie Synced after a title/episode is identified. Wyzie Synced requires a Wyzie Pro key and performs subtitle matching, constant offset correction, and frame-rate correction. If unavailable or rejected, normal provider search continues.
- Repeated Wyzie quote matches provide time anchors. A robust median affine fit updates subtitle playback time and compensates for gradual timing drift.
- SubtitleCat is integrated as a final fallback through its public HTML search/detail pages: `index.php?search=...`, `download_ar` link, and direct Arabic SRT URL. It has no documented public API; this adapter is HTML scraping and can break if the site changes markup or blocks automated requests.
- Provider order: Wyzie Synced → Wyzie standard → OpenSubtitles.com → SubDL → SubtitleCat.

### Alignment limits

- VAD is a lightweight energy/zero-crossing detector, not a neural Silero/WebRTC model. Loud music or effects can cause false speech regions.
- Wyzie Synced is a Pro feature; free keys fall back to quote-anchor affine timing.
- The local affine drift correction requires multiple successful quote anchors separated by at least 30 seconds. It is not a substitute for source-player PTS access.
- SubtitleCat search/download is unverified against live requests in this build environment.

### Audio/subtitle synchronization (v1.0.5)

The app now aligns translated Arabic dialogue against Arabic subtitle cue text using word-sequence similarity. Accepted matches create timing anchors; robust affine fitting estimates both fixed offset and gradual rate drift. VAD speech intervals continue to feed the optional Wyzie Synced endpoint. A 64-bit spectral fingerprint stream detects repeated acoustic frames for continuity diagnostics.

Important boundaries: the fingerprint stream is not a reference-audio identification service. The Android capture APIs expose an audio capture clock, not the IPTV video's media PTS. Exact synchronization across pause/seek and source FPS changes therefore still requires a player-provided playback position/PTS integration or privileged vendor access. Local word alignment is phrase/word-sequence based on translated Arabic, not phoneme-level forced alignment. Build and TV integration tests are still required.

In ADB Echo Reference mode, the service also polls Android `dumpsys media_session` for the IPTV player's published PlaybackState position/speed/state. When present, subtitle timing follows that player clock across pause, seek and playback-speed changes. This is not decoder-level video PTS and only works when the player publishes a readable, current MediaSession.


Pure synchronization smoke tests (requires Kotlin compiler):

```bash
kotlinc app/src/main/java/com/iraqtranslate/SubtitleAlignmentEngine.kt \
  app/src/main/java/com/iraqtranslate/AudioFingerprintEngine.kt \
  app/src/main/java/com/iraqtranslate/VoiceActivityDetector.kt \
  -d /tmp/iraq-sync.jar
kotlinc -classpath /tmp/iraq-sync.jar qa/AlignmentEngineSmoke.kt -include-runtime -d /tmp/align-smoke.jar
java -cp /tmp/align-smoke.jar:/tmp/iraq-sync.jar AlignmentEngineSmokeKt

kotlinc app/src/main/java/com/iraqtranslate/MediaSessionPositionParser.kt -d /tmp/media-session.jar
kotlinc -classpath /tmp/media-session.jar qa/MediaSessionParserSmoke.kt -include-runtime -d /tmp/media-smoke.jar
java -cp /tmp/media-smoke.jar:/tmp/media-session.jar MediaSessionParserSmokeKt
```

Accepted dialogue/subtitle matches also calibrate a player-position-to-subtitle-time transform. This lets the app correct subtitle-file offset/drift while using the MediaSession position as the playback clock.

### Synchronization extensions (1.0.6)

- `VideoPtsClock` now accepts decoder-frame presentation timestamps in microseconds and extrapolates the video timeline using the player's monotonic observation time and playback speed. It freezes while paused and flags large PTS jumps/seeks. `AutomaticSubtitleManager.updateDecoderVideoPts(ptsUs, observedElapsedMs, speed, playing)` is the integration entry point; `cueAt()` prefers fresh decoder PTS, then MediaSession, then audio/quote anchors. **This is not populated automatically by Android AudioPlaybackCapture or by the existing ADB shell helper.** The IPTV player itself (or a vendor decoder integration) must call this entry point from its frame/renderer callback. Without that integration the app continues using MediaSession/audio fallback.
- `ReferenceAudioFingerprintMatcher` matches captured 40 ms spectral fingerprints against a caller-supplied reference catalogue, using indexed four-band Hamming search and consensus on media ID plus timeline offset. Import `reference_fingerprints.csv` from the app UI; the capture service loads it from app-private storage at session start. A match calibrates the active subtitle timebase only when `mediaId` exactly equals the active `IMDb:season:episode` identity. No reference corpus is bundled. `tools/BuildFingerprintCatalogue.kt` creates compatible fingerprints from a 16 kHz mono PCM16 WAV file.
- The existing Arabic subtitle matcher remains text/phrase alignment. This release does not claim phoneme-level forced alignment. Vosk word timestamps describe its ASR hypothesis timing; they are not a forced alignment of a separate Arabic translation. A genuine cross-language phoneme aligner requires a suitable acoustic/phoneme model and a transcript in the same spoken language, plus a mapping from source words to translated subtitle segments.

The new pure-Kotlin smoke test covers decoder PTS extrapolation, pause freeze, seek discontinuity, and reference-fingerprint offset consensus. The player callback and reference-catalogue ingestion still require integration with the actual IPTV player / media corpus.


## Version 1.0.7 — synchronization hardening

- AudioRecord capture chunks now use AudioTimestamp framePosition/TIMEBASE_BOOTTIME to estimate the actual chunk start time; the 16 kHz resampled PCM keeps that capture-time association. ADB Echo Reference uses a monotonic chunk-duration estimate.
- Vosk recognition time origin is set on the first PCM chunk actually submitted to the recognizer, not when the model is loaded.
- Partial Vosk hypotheses remain available for live translated captions but are excluded from movie identification and external-SRT alignment anchors.
- Reference fingerprint matches are gated by minimum hit count/confidence and can calibrate the subtitle timebase only when their mediaId exactly matches the active IMDb/season/episode identity. A match discovered before subtitle download is applied when the subtitle becomes active.
- Decoder PTS discontinuities invalidate the old player-to-subtitle calibration and MediaSession position, preventing stale mapping after seek. Decoder PTS still requires an actual player-side integration; this version does not manufacture decoder timestamps.

Limitations: no reference fingerprint catalogue is bundled; no actual forced-alignment acoustic model is included; external IPTV decoder PTS cannot be received until the player integration calls the decoder timestamp interface. Android APK build and physical TV playback validation remain pending.


### Decoder PTS integration contract (1.0.7)

When the IPTV player is embedded in the same Android application, bind to the non-exported `CaptureService`, cast its `IBinder` to `CaptureService.LocalBinder`, obtain `service()`, and call `onDecoderVideoFramePts(presentationTimeUs, playbackSpeed, playing)` from the player's decoder/render callback with the frame's actual presentation timestamp in microseconds. Do not feed `SystemClock` time, MediaSession position, audio timestamps, or wall-clock values into this method. For a separate external IPTV application, this private binder is intentionally unavailable; a supported player API, plugin, or same-app integration is required.

`PlaybackClock` now requests `AudioTimestamp.TIMEBASE_BOOTTIME`, which aligns the timestamp clock with `SystemClock.elapsedRealtime()`; it maps the most recently captured AudioRecord frame back to the start of each PCM chunk. The ADB Echo Reference path estimates chunk start from read completion minus chunk duration and therefore remains less exact due to pipe buffering.

Vosk final word timestamps are accepted as synchronization anchors only when the JSON contains a valid final-word end time. Partial hypotheses are not used to identify media or adjust external subtitle timing.


### Reference fingerprint catalogue format (1.0.7)

CSV columns: `mediaId,mediaTimeMs,hashHex`. Use one row per 40 ms landmark. `mediaId` must exactly match the quote identity used by the app, for example `tt1234567:1:2` or `tt1234567:-1:-1`. Generate from a 16 kHz, mono, PCM16 WAV using `tools/BuildFingerprintCatalogue.kt`, then import the CSV through **Import reference audio fingerprint CSV**. The importer stores it in app-private files and the capture service loads it on the next start. The current in-memory catalogue is capped at 400,000 rows; this is intended for a small local catalogue, not an internet-scale movie database.

Example build command (from project root):

```bash
kotlinc app/src/main/java/com/iraqtranslate/AudioFingerprintEngine.kt tools/BuildFingerprintCatalogue.kt -include-runtime -d build-fingerprint.jar
java -jar build-fingerprint.jar tt1234567:1:2 episode-16k-mono.wav reference_fingerprints.csv
```


## Version 1.0.8 source update

This source update adds pre-activation Arabic subtitle candidate scoring and temporal sequence validation for reference audio fingerprints. It also improves retry behavior for unsuccessful Wyzie quote lookups. See `STATUS.md` for implemented changes and remaining unverified work. This is not a tested APK build.


## Version 1.0.9 — capture queue and Media3 PTS adapter

- Audio capture now runs on a dedicated producer thread and writes timestamped 16 kHz PCM chunks to a bounded 50-chunk queue (approximately 10 seconds at 200 ms per chunk).
- Vosk, language identification, VAD, fingerprinting, and translation processing run on the separate consumer thread. Each PCM chunk carries its own capture-start elapsed-realtime timestamp.
- Queue overrun is treated as a fatal capture-session error; the app stops the session instead of silently dropping samples and corrupting synchronization.
- Added `Media3DecoderPtsBridge`, which receives actual video-frame presentation timestamps from Media3 `VideoFrameMetadataListener` and forwards them to `CaptureService`. The bridge is an integration adapter only: this repository currently has no IPTV `ExoPlayer` instance, so a host IPTV player must attach it before `prepare()`.
- Forced Alignment is not yet integrated. Existing Vosk word timing and subtitle text matching are not represented as forced alignment. A production aligner requires a CTC acoustic model matching the original spoken language and device-memory validation.
- No APK build or on-device IPTV validation was completed in this environment.
