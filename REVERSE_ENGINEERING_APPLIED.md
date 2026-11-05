# Reverse-engineering implementation record — IRAQ TRANSLATE 1.0.5

Target analyzed: `AI-Translator(2).apk`

- Package found in APK: `com.starnet.aitranslator`
- Version found in manifest: `1.0.72`
- Project package: `com.iraqtranslate`
- Project version: `1.0.5` (versionCode 6)
- Scope: reproduce the relevant audio-to-Arabic subtitle pipeline, not the original app's unrelated EPG, IPTV metadata, or subscription features.

## Confirmed target-APK evidence used

- DEX class descriptors include `com.starnet.aitranslator.ShellAudioHelper` and `com.starnet.aitranslator.TranslatorService`.
- DEX strings include `echo reference did not start`, `AudioRecord.read returned`, and `Audio permission is needed to capture IPTV apps`.
- DEX strings include ADB protocol/authentication names such as `ADB_AUTH_RSAPUBLICKEY`, `ADB_AUTH_SIGNATURE`, `ADB_AUTH_TOKEN`, and the message `ADB pairing is required.`
- DEX strings include Sherpa Whisper model paths for `sherpa-onnx-whisper-tiny`, `sherpa-onnx-whisper-tiny.en`, `sherpa-onnx-whisper-base`, and `sherpa-onnx-whisper-base.en`.
- DEX strings include `https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/` and the tiny encoder/decoder model filenames.
- DEX strings include the Google Translate web endpoint used by the target app.
- The APK includes Sherpa-ONNX and Vosk integration, and its app manifest includes `RECORD_AUDIO`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`, network permissions, and Android TV support.
- The target app contains multiple Vosk model identifiers. The current official Vosk model catalog does not list `vosk-model-small-ar-0.3`; the implementation uses the published Arabic `vosk-model-ar-mgb2-0.4` archive instead of shipping a dead URL.

## Implemented in this project

### 1. ADB pairing and shell transport

- Added `libadb-android` 3.1.1, Conscrypt 2.5.3, and the companion X.509 helper dependency.
- Added persistent RSA identity/certificate storage for ADB authentication.
- Added Wireless debugging pairing (host, pairing port, six-digit pairing code) and a separate ADB connection port.
- Added direct ADB TCP connection support, including port 5555 when enabled by the user from a trusted host.
- Added fixed diagnostic shell commands for device identity and `dumpsys media.audio_flinger` Echo Reference/Tuner inspection.
- Added an `app_process` helper launched over the authenticated ADB shell stream. It attempts to open audio source `1997` (`ECHO_REFERENCE`) at 48 kHz stereo PCM32 and streams bytes directly over ADB. It does not save audio to a file and does not use `MIC`.

### 2. Echo Reference and fallback audio capture

- ADB Echo Reference is now the preferred capture path.
- The shell helper emits a protocol header only after `AudioRecord` starts successfully; the app validates it before treating the stream as audio.
- If the device firmware denies Echo Reference access to the ADB shell UID, the ADB path reports the error rather than pretending it works.
- An explicit Android `AudioPlaybackCapture` fallback remains available. It captures `USAGE_MEDIA` and `USAGE_GAME` only, using MediaProjection audio capture; no video frames or screen recording are implemented.
- Android requires the `RECORD_AUDIO` permission as an authorization gate for `AudioPlaybackCapture`. The fallback does not configure `AudioRecord` with `MIC` and does not record the user's speech.

### 3. 48 kHz stereo and FIR path

- Added a 48 kHz stereo PCM32 reader for the ADB Echo Reference stream.
- Added conversion from signed PCM32 stereo to PCM16 stereo.
- Added a stateful 63-tap low-pass FIR decimator from 48 kHz stereo to 16 kHz mono.
- The MediaProjection fallback prefers 48 kHz stereo PCM16 with a 384000-byte buffer, then falls back to 16 kHz mono if the platform rejects that format.
- The original APK's exact 63 FIR coefficients were not recoverable from the available evidence. This project uses a documented windowed-sinc 63-tap design; it is not claimed to be a byte-for-byte coefficient match.

### 4. Speech recognition and automatic language identification

- Added Sherpa-ONNX 1.12.15 spoken-language identification using the multilingual Whisper-tiny encoder/decoder.
- The 1.12.15 Android AAR is fetched from a pinned Hugging Face revision during Gradle `preBuild` and SHA-256 verified (`350d4a42…b947c236`); this avoids silently depending on a POM-only Maven coordinate.
- The two ONNX model files are downloaded separately from the Hugging Face model repository and validated before use.
- The service waits for five seconds of audible playback samples before language identification; silence is ignored.
- Added Vosk model catalog entries for the languages available in the APK/official small-model catalog.
- After detection, the service selects the matching installed Vosk model. If that model is missing, it stops with an explicit message instead of silently using the wrong language model.
- Arabic speech is transcribed and shown as Arabic; non-Arabic speech is sent to the Arabic translation stage.

### 5. Captions and timing

- Vosk word timing is enabled.
- The end time of the final recognized word is mapped to the monotonic capture clock.
- Added affine timestamp mapping (`a × timestamp + b`) and one-second caption buckets, matching the structure found in the reverse-engineering notes.
- The source playback API does not expose the original IPTV media PTS here; therefore synchronization is based on the local capture clock, not a verified source-video PTS mapping.
- Captions are displayed in the existing overlay; the overlay is shown automatically when permission is already granted.

## Explicit limitations / not claimed as complete

1. The Echo Reference helper has not been executed on the user's Android TV. It depends on the firmware granting the system-only `CAPTURE_AUDIO_OUTPUT` permission to the ADB shell UID. AOSP does not grant this permission to ordinary third-party apps, and some TV builds may also deny it to the shell UID.
2. The exact original `ShellAudioHelper` DEX method body and its FIR coefficient array were not recovered. The new helper reproduces the observable audio-source/format path, not the exact original implementation.
3. The public Google Translate `translate_a/single` endpoint is undocumented and can change or rate-limit requests. A contracted translation API is required for production reliability.
4. The exact original subtitle time calibration coefficients and IPTV media PTS bridge remain unverified.
5. ADB pairing, Echo Reference capture, Vosk inference, Sherpa language identification, and overlay behavior have not been hardware-tested.
6. The current environment has no Android SDK or Gradle executable, so no APK build result is claimed.

## Build and test requirements

- Build with Android Gradle Plugin 8.7.3, Kotlin 2.0.21, JDK 17, and Android SDK 35.
- Device minimum API: 29 (Android 10), because the fallback uses AudioPlaybackCapture.
- For the ADB route, enable Wireless debugging, pair, connect, then run the Echo Reference capture action.
- If Echo Reference is denied by firmware, test the explicit Android playback-capture fallback. It still captures playback audio only; it does not record microphone input or screen frames.

## Automatic movie/episode subtitle discovery (1.0.4)
- Added `AutomaticSubtitleManager`: rolling recognized-dialogue window is sent to Wyzie `/quote` to identify the title/episode and obtain the matched dialogue's media timestamp.
- On a match, Arabic subtitle retrieval runs in this order: Wyzie Synced `/sync` when sufficient VAD speech segments are available and the key supports Pro sync; standard Wyzie `/search`; OpenSubtitles.com `/subtitles` + `/download`; SubDL `/api/v1/subtitles` + `dl.subdl.com`; then SubtitleCat HTML search/detail/direct Arabic SRT.
- Supports movie and TV season/episode fields. SRT/VTT timing cues are parsed in memory; the quote's media timestamp is mapped to the local monotonic capture clock and captions are rendered against the inferred media timeline.
- Added on-device API-key fields for Wyzie, OpenSubtitles.com and SubDL. Keys are user-provided and are not embedded in the APK/source. They are encrypted with an AES-GCM key held by Android Keystore.
- SubtitleCat is integrated through public HTML search/detail pages because no documented public API was found. This is a scraper, not an official API integration; markup changes or anti-bot measures can break it.
- This is dialogue-based title discovery, not file-name or video-frame recognition. Wyzie quote index coverage limits identification. The app cannot read the IPTV player's file name, TMDB/IMDb metadata or original video PTS through the capture APIs used here.
- Playback VAD intervals are sent to Wyzie Synced `/sync` when available; this Pro service returns an offset/FPS-corrected subtitle. Repeated quote matches also provide a local affine timebase for gradual drift correction when the Pro service is unavailable. This remains an inferred media timeline because the capture API does not expose the IPTV player's source PTS.
- Provider calls require user API keys and internet access. API limits, provider availability, title coverage, and Arabic subtitle availability affect results. If no external subtitle is found, the existing live speech-to-Arabic translation remains the fallback.


## 1.0.4 subtitle alignment additions

- `VoiceActivityDetector.kt`: 16 kHz playback PCM VAD with 20 ms framing, adaptive noise estimate, ZCR gate, pre-roll, hangover, and minimum speech duration.
- `AutomaticSubtitleManager.kt`: VAD speech intervals are converted to session-relative media seconds and submitted to Wyzie `POST /sync`; returned synced SRT is accepted only with a valid URL and confidence >= 0.35. Wyzie Synced is Pro-only.
- Quote matches are accumulated as capture/media time anchors. Median pairwise slope and median offset estimate an affine timebase; slope is bounded to 0.94–1.06 to cover common frame-rate mismatches while rejecting outliers.
- SubtitleCat HTML adapter searches `https://www.subtitlecat.com/index.php?search=...`, parses result detail pages, selects `download_ar`, and fetches the direct SRT. Host/path allowlists prevent arbitrary URL fetches. This is scraping, not an official API integration.
- Provider fallback sequence: Wyzie Synced, Wyzie standard, OpenSubtitles.com, SubDL, SubtitleCat.
- VAD and network provider integration are source-level changes only; live network behavior and TV playback must be tested on device.


## 1.0.4 source-level QA

- Pure JVM VAD test passed: silence was rejected and two synthetic speech regions were detected.
- Subtitle parser tests passed for SRT and WebVTT timestamp layouts.
- SubtitleCat search/detail regular expressions passed a representative fixture using relative `subs/...html` links and an Arabic `download_ar` link.
- `AutomaticSubtitleManager.kt` and `VoiceActivityDetector.kt` passed Kotlin compilation with minimal Android/JSON stubs. This is not an Android SDK build and does not validate external service responses.
- Android Gradle build, real provider requests, Wyzie Pro sync, ADB playback, and IPTV TV testing remain outstanding.

## 1.0.5 audio/subtitle synchronization implementation

- `SubtitleAlignmentEngine.kt`: normalizes Arabic Unicode/diacritics, tokenizes dialogue, searches adjacent SRT cue windows, scores token sequences using word-level Levenshtein similarity plus token overlap, rejects low-confidence matches, and estimates affine timing scale/offset from accepted anchors.
- `AutomaticSubtitleManager.kt`: uses translated Arabic dialogue to align against Arabic subtitle cues; quote timestamps seed the local capture-to-subtitle clock; accepted matches refine the local clock and, when MediaSession is available, also calibrate player-position-to-subtitle timing. Subtitle confidence and anchor counts are logged.
- `AudioFingerprintEngine.kt`: creates 64-bit spectral signatures every 40 ms and flags repeated/near-identical captured audio frames. This is continuity/duplicate detection, not reference-catalogue content recognition.
- `MediaSessionPositionParser.kt` and `AdbBridge.readMediaSessionPosition()`: parse the IPTV player's published Android PlaybackState position, speed and state from `dumpsys media_session`; the ADB capture mode polls once per second. The subtitle clock follows pause/buffering, seek and playback speed when the player exposes a valid session. Missing/stale sessions expire and fall back to local audio/quote alignment.
- `PlaybackClock.kt`: reads `AudioRecord` monotonic timestamps and detects capture-clock discontinuities. This timestamp is not video PTS.

### Remaining hard limits

- Android AudioPlaybackCapture and AudioRecord do not expose the IPTV decoder's video PTS. MediaSession PlaybackState is a player-reported position, not frame-level PTS, and depends on the IPTV player publishing correct state. A player that does not publish MediaSession data cannot provide exact seek/pause/video-clock synchronization through this route.
- The spectral fingerprint engine has no reference fingerprint database or remote reference-audio matching endpoint. It cannot identify a movie from sound alone; title discovery remains Wyzie Quote dialogue matching.
- Cross-language matching compares translated Arabic phrase tokens with Arabic subtitle cue tokens. It is not phoneme-level forced alignment and does not produce exact per-word timestamps after translation.
- FPS drift is estimated by affine regression over multiple quote/word anchors and can be corrected against MediaSession time when available. This requires enough accepted anchors and is not a decoder-level frame-rate measurement.
- No Android APK build or physical IPTV TV test has been performed in this environment.

## 1.0.6 synchronization extension

- `VideoPtsClock.kt` accepts frame PTS supplied by an IPTV decoder/render integration, extrapolates position from monotonic elapsed time and speed, freezes when paused, and marks large jumps. `AutomaticSubtitleManager.updateDecoderVideoPts()` is the hand-off point. `cueAt()` prefers this clock when fresh, then falls back to the existing MediaSession and audio/quote clocks. The current app does not independently extract another app's decoder PTS; the player/vendor integration must provide it.
- `ReferenceAudioFingerprintMatcher.kt` adds local reference-catalogue matching. It votes on media identity and capture-to-media offset using multiple spectral landmarks. `CaptureService` forwards generated fingerprints to the manager. A catalogue must be supplied with `setReferenceFingerprintCatalogue`; no reference corpus or automatic corpus builder is included.
- No phoneme-level forced aligner is claimed. Vosk word timestamps are ASR timings, while translated Arabic phrase-to-SRT matching remains a text alignment heuristic. A same-language acoustic forced-alignment model and source-to-translation segment mapping are still required.


## Version 1.0.7 — synchronization hardening

- AudioRecord capture chunks now use AudioTimestamp framePosition/TIMEBASE_BOOTTIME to estimate the actual chunk start time; the 16 kHz resampled PCM keeps that capture-time association. ADB Echo Reference uses a monotonic chunk-duration estimate.
- Vosk recognition time origin is set on the first PCM chunk actually submitted to the recognizer, not when the model is loaded.
- Partial Vosk hypotheses remain available for live translated captions but are excluded from movie identification and external-SRT alignment anchors.
- Reference fingerprint matches are gated by minimum hit count/confidence and can calibrate the subtitle timebase only when their mediaId exactly matches the active IMDb/season/episode identity. A match discovered before subtitle download is applied when the subtitle becomes active.
- Decoder PTS discontinuities invalidate the old player-to-subtitle calibration and MediaSession position, preventing stale mapping after seek. Decoder PTS still requires an actual player-side integration; this version does not manufacture decoder timestamps.

Limitations: no reference fingerprint catalogue is bundled; no actual forced-alignment acoustic model is included; external IPTV decoder PTS cannot be received until the player integration calls the decoder timestamp interface. Android APK build and physical TV playback validation remain pending.


## 1.0.7 synchronization hardening

AudioRecord now maps each PCM chunk to `TIMEBASE_BOOTTIME` using AudioTimestamp.framePosition, and stereo capture reads 200 ms blocks instead of 2-second blocks. Vosk time origin is set on the first chunk actually consumed. Partial recognition and final results without valid word timing are barred from external-SRT calibration. Reference matching now uses a four-band Hamming index, is throttled to one pass per second, supports CSV import from app-private storage, and has a WAV catalogue-generation utility. Fingerprint offsets are applied only when the reference media identity exactly matches the active quote identity. Decoder PTS discontinuity clears stale player calibration.

Still incomplete: decoder PTS has no producer until the IPTV player calls `CaptureService.onDecoderVideoFramePts`; no acoustic forced-alignment model is included; no reference catalogue ships with the app; full Android build and physical TV validation remain pending.
