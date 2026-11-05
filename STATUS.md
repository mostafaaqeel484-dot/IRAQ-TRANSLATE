# IRAQ TRANSLATE 1.0.8 — status

## Implemented in source

- [x] Android TV launcher and overlay controls
- [x] F1 then 3 shortcut via Accessibility service
- [x] ADB TLS pairing using SPAKE2-capable ADB library
- [x] Separate ADB connection port and direct TCP connection path
- [x] ADB shell device/audio-route diagnostics
- [x] ADB `app_process` Echo Reference helper, 48 kHz stereo PCM32 streaming
- [x] No microphone source selected by either capture backend
- [x] Android MediaProjection playback-capture fallback for MEDIA/GAME usages only
- [x] 63-tap FIR 48 kHz → 16 kHz mono decimator
- [x] Sherpa-ONNX Whisper-tiny language identification
- [x] Vosk model catalog and language-to-model mapping
- [x] Arabic translation pipeline and overlay update
- [x] Vosk word timing, affine clock mapping, one-second caption buckets
- [x] Safe model download and ZIP extraction

## Not yet verified

- [ ] Gradle build / APK assembly (no Gradle or Android SDK in current environment)
- [ ] ADB pairing against the actual TV firmware
- [ ] Whether the TV's ADB shell UID is granted system-only CAPTURE_AUDIO_OUTPUT and can open ECHO_REFERENCE source 1997
- [ ] Echo Reference stream format and byte alignment on the target hardware
- [ ] Sherpa language-identification result quality with actual IPTV audio
- [ ] Vosk model accuracy and Arabic translation latency on the TV
- [ ] F1 then 3 behavior with the actual remote control
- [ ] Read exact IPTV player title/episode or video PTS directly; capture API does not expose it

## Important permissions distinction

`RECORD_AUDIO` is needed by Android's MediaProjection AudioPlaybackCapture fallback, but the fallback does not use the MIC source. The preferred ADB helper requests `ECHO_REFERENCE` (source 1997), which is a system-only playback-reference source. It may work under the ADB shell UID on some firmware and fail on others. No screen frames are captured by either path.

## Local verification performed

- [x] XML manifests/resources parse successfully.
- [x] Pure JVM test: one second of 48 kHz stereo PCM16 produces 16 kHz mono output.
- [x] Pure JVM test: 48 kHz stereo PCM32 split across partial input reads is reassembled and decimated to 16 kHz mono.
- [x] Pure JVM test: caption text is grouped into the same one-second time bucket.
- [x] Pure JVM VAD test: silence is suppressed and two synthetic speech regions are detected.
- [x] Subtitle manager/VAD Kotlin syntax and type check using minimal Android/JSON stubs.
- [x] Subtitle parser QA: SRT and WebVTT timestamps parsed correctly.
- [x] SubtitleCat search/detail regex fixture check for relative result paths and `download_ar` SRT links.
- [ ] Android/Kotlin compilation against the real SDK and external AAR dependencies.

## Subtitle automation status — 1.0.4
- [x] Identify movie/episode from recognized dialogue via Wyzie quote search (rolling dialogue window).
- [x] Search/download Arabic subtitles from Wyzie.
- [x] OpenSubtitles.com API search/download adapter.
- [x] SubDL API search/download fallback.
- [x] Parse subtitle cues and align local playback clock using the quote's media timestamp.
- [x] UI fields to save provider API keys.
- [x] SubtitleCat HTML search/detail/download fallback (scraping; live request not verified).
- [x] Playback VAD and Wyzie Synced Pro `/sync` integration in source; requires Pro key and live verification.
- [ ] Read exact IPTV player title/episode or video PTS directly; capture API does not expose it.
- [ ] Build APK and test providers/device; Android SDK/Gradle are not available in this environment.


## 1.0.4 updates

- Added playback-only streaming VAD and speech-interval collection for both MediaProjection and ADB Echo Reference capture paths.
- Added Wyzie Synced POST `/sync` integration using detected speech intervals, Arabic subtitle selection, confidence validation, and returned synced SRT URL. This requires a Pro key; regular Wyzie subtitle retrieval remains the fallback.
- Added repeated quote-anchor calibration using a median affine fit to correct offset and gradual FPS drift for ordinary downloaded subtitles.
- Added SubtitleCat HTML search/detail/download fallback for existing Arabic SRT tracks. No undocumented translation endpoint is called.
- No Android APK build or real-TV/network smoke test was possible in this environment.

## Version 1.0.5 — subtitle/audio alignment work

Implemented in source:
- Arabic transcript-to-subtitle word-sequence matching with Unicode/Arabic normalization, edit-distance scoring, confidence threshold, and cue-window search (`SubtitleAlignmentEngine`).
- Quote-derived timing anchors seed the local aligner; accepted word-match anchors refine affine offset and playback-rate scale over time.
- Local alignment confidence and anchor count are exposed in diagnostic status/log output.
- 64-bit spectral fingerprints are generated from 40 ms playback PCM frames; repeated acoustic signatures are detected for playback-continuity diagnostics (`AudioFingerprintEngine`). These are capture-side fingerprints only; no external reference-audio fingerprint catalogue/service is integrated.
- `AudioRecord.getTimestamp(TIMEBASE_MONOTONIC)` is sampled through `PlaybackClock` to detect capture-clock discontinuities. This is an audio capture clock, not the video's media presentation timestamp (PTS).
- VAD speech regions continue feeding Wyzie Synced; translated Arabic dialogue now feeds local word-sequence alignment whenever an external Arabic subtitle is active.

Not fully achievable from the current app's capture permissions/interfaces:
- True video PTS integration is not available from Android `AudioPlaybackCapture` or the public `AudioRecord` timestamp API. `AudioTimestamp` reports the captured audio hardware/monotonic timeline, not the IPTV player's video PTS. Exact pause/seek/rate-change synchronization requires a supported player MediaSession/PlaybackState position source, a player plugin/SDK callback, or privileged vendor TV framework access. No such access is assumed.
- Reference-based audio fingerprint identification is not implemented: it requires reference audio fingerprints for candidate media or an external matching service. The implemented fingerprint engine detects repeated/near-identical captured acoustic frames and supports continuity diagnostics only.
- FPS drift is estimated locally from multiple dialogue/subtitle anchors and optionally by Wyzie Synced. It is not independently verified against source video frame rate/PTS.
- Word alignment is performed on translated Arabic phrase token sequences against Arabic subtitle cues. It is not phoneme-level forced alignment and cannot guarantee word timestamps across cross-language translation.

Validation:
- Pure Kotlin compilation passed for `SubtitleAlignmentEngine`, `AudioFingerprintEngine`, and `VoiceActivityDetector`.
- Alignment smoke test passed for exact Arabic phrase matching, multiple timing anchors, and rejection of unrelated dialogue.
- Android APK build and physical TV tests remain unperformed because Android SDK/Gradle/device toolchain is not available in this environment.

Additional v1.0.5 playback clock path:
- In ADB Echo Reference mode, poll `dumpsys media_session` once per second and parse the active IPTV player's published PlaybackState position, speed, and state.
- When a valid MediaSession position is available, subtitle cue selection follows that player timeline. Paused/buffering states freeze the cue clock; seek and playback-rate changes are reflected on the next poll. If the app does not publish MediaSession state, local audio/quote/word anchors remain the fallback.
- This is a player-reported playback position, not decoder-level video PTS. It is conditional on the IPTV player publishing a readable MediaSession; some apps/firmware expose none or stale values.

- When MediaSession position is available, each accepted Arabic word-sequence match also creates a player-position → subtitle-time anchor. A second robust affine fit corrects subtitle-file offset/drift relative to the player's own position, rather than assuming the player's position and subtitle timeline are identical.
- [x] Pure Kotlin smoke test: Arabic phrase-to-subtitle word-sequence alignment, confidence rejection, and affine player/subtitle drift fitting.
- [x] Pure Kotlin smoke test: 64-bit spectral fingerprint repeated-frame detection.
- [x] Pure Kotlin smoke test: MediaSession playing-position extrapolation and paused-position freeze.
- [ ] Validate `dumpsys media_session` output and player position accuracy on the actual IPTV receiver.
- [ ] Validate local alignment confidence and drift correction on real movie/episode playback.

## Version 1.0.6 — synchronization integration foundations

- [x] Added `VideoPtsClock`: decoder-fed PTS in microseconds, playback-rate extrapolation, pause freeze, and seek/discontinuity detection.
- [x] Added `AutomaticSubtitleManager.updateDecoderVideoPts(...)`; fresh decoder PTS has priority over MediaSession and inferred audio timeline when selecting an SRT cue.
- [x] Added `ReferenceAudioFingerprintMatcher`: match captured 40 ms spectral landmarks against a supplied reference catalogue with Hamming-distance filtering and consensus offset estimation.
- [x] Connected fingerprint observations from `CaptureService` to the reference matcher; stable matches are emitted to diagnostics.
- [x] Added smoke tests for PTS clock behavior and reference fingerprint offset consensus.
- [ ] Supply a real reference fingerprint corpus and map its media IDs to IMDb/title/episode metadata. No corpus is bundled.
- [ ] Integrate `updateDecoderVideoPts` with the actual IPTV player's decoder/render callback. Android playback capture and MediaSession cannot supply decoder-level PTS by themselves.
- [ ] Implement and validate a same-language phoneme/CTC forced aligner with a compatible acoustic model. Current translated-Arabic cue matching remains phrase-level and is not phoneme forced alignment.
- [ ] Android Gradle build and physical TV/IPTV integration tests remain outstanding.


## Version 1.0.7 — synchronization hardening

- AudioRecord capture chunks now use AudioTimestamp framePosition/TIMEBASE_BOOTTIME to estimate the actual chunk start time; the 16 kHz resampled PCM keeps that capture-time association. ADB Echo Reference uses a monotonic chunk-duration estimate.
- Vosk recognition time origin is set on the first PCM chunk actually submitted to the recognizer, not when the model is loaded.
- Partial Vosk hypotheses remain available for live translated captions but are excluded from movie identification and external-SRT alignment anchors.
- Reference fingerprint matches are gated by minimum hit count/confidence and can calibrate the subtitle timebase only when their mediaId exactly matches the active IMDb/season/episode identity. A match discovered before subtitle download is applied when the subtitle becomes active.
- Decoder PTS discontinuities invalidate the old player-to-subtitle calibration and MediaSession position, preventing stale mapping after seek. Decoder PTS still requires an actual player-side integration; this version does not manufacture decoder timestamps.

Limitations: no reference fingerprint catalogue is bundled; no actual forced-alignment acoustic model is included; external IPTV decoder PTS cannot be received until the player integration calls the decoder timestamp interface. Android APK build and physical TV playback validation remain pending.


### 1.0.7 follow-up hardening
- [x] AudioRecord capture timestamp uses `TIMEBASE_BOOTTIME` to share the `elapsedRealtime` timebase.
- [x] Map AudioRecord framePosition to PCM chunk start time and reduce stereo read chunks to 200 ms to lower recognition latency.
- [x] Set Vosk timeline origin on first PCM chunk passed to the recognizer.
- [x] Exclude partial hypotheses and untimed final hypotheses from external subtitle synchronization anchors.
- [x] Apply matching reference fingerprint offsets only after exact media identity validation; apply a match discovered before subtitle activation once cues load.
- [x] Add indexed four-band Hamming search, periodic matching, CSV catalogue import, and a WAV-to-catalogue generator.
- [x] Invalidate stale player calibration after decoder PTS discontinuity.
- [ ] Actual decoder PTS producer in IPTV player.
- [ ] Acoustic Forced Alignment model and integration.
- [ ] Full Android build and real-TV seek/pause/buffering synchronization test.


## Version 1.0.8 — subtitle candidate and fingerprint validation

Implemented in source:
- Added `SubtitleCandidateScorer`: compares recent translated Arabic dialogue phrases against short adjacent SRT cue windows before a downloaded subtitle is activated.
- Candidate scoring is deliberately conservative: when there is not enough translated dialogue evidence, it does not reject the candidate. When evidence is available, low-overlap candidates are rejected and the provider search continues.
- Translation now feeds the candidate dialogue buffer before the asynchronous subtitle discovery request, so the first downloaded candidates can be checked against the current dialogue.
- Wyzie quote lookup no longer permanently suppresses a phrase merely because a request returned no quote or malformed data; `lastQuery` is committed only after a valid media identity and timestamp are returned.
- Reference fingerprint matching now keeps several near-equal hash candidates instead of choosing an arbitrary first item from a `HashSet`.
- Fingerprint consensus now checks temporal sequence progression. Similar spectral hashes at unrelated or non-monotonic reference positions are rejected rather than treated as a stable media lock.
- Added `qa/SubtitleCandidateScorerSmoke.kt` and extended `qa/AdvancedSyncSmoke.kt` with a negative temporal-consensus test.

Important limitations:
- This release is source-level work only. It has not been compiled into an APK or tested on an IPTV receiver.
- Candidate subtitle scoring compares translated text, not phonemes. It can still be confused by paraphrased translations, alternate cuts, or sparse dialogue. If too little dialogue has been translated, the candidate is allowed through rather than falsely rejected.
- The capture and recognition stages still run in the same processing loop. Long language-identification/model-loading work can delay reads and cause audio loss on some devices. A separate bounded capture/processing queue remains a required next fix.
- Subtitle discovery network requests now run outside the manager monitor. Phrase throttling and media-identity commits use short synchronized sections, so subtitle ticker/alignment reads are not blocked for the duration of provider requests.
- The current matcher still requires a supplied `reference_fingerprints.csv`; no reference catalogue is bundled.
- Decoder PTS remains an integration hook, not a timestamp source automatically supplied by generic IPTV applications.
- Forced alignment is not implemented.


## Version 1.0.9 — capture queue and Media3 PTS adapter

- Audio capture now runs on a dedicated producer thread and writes timestamped 16 kHz PCM chunks to a bounded 50-chunk queue (approximately 10 seconds at 200 ms per chunk).
- Vosk, language identification, VAD, fingerprinting, and translation processing run on the separate consumer thread. Each PCM chunk carries its own capture-start elapsed-realtime timestamp.
- Queue overrun is treated as a fatal capture-session error; the app stops the session instead of silently dropping samples and corrupting synchronization.
- Added `Media3DecoderPtsBridge`, which receives actual video-frame presentation timestamps from Media3 `VideoFrameMetadataListener` and forwards them to `CaptureService`. The bridge is an integration adapter only: this repository currently has no IPTV `ExoPlayer` instance, so a host IPTV player must attach it before `prepare()`.
- Forced Alignment is not yet integrated. Existing Vosk word timing and subtitle text matching are not represented as forced alignment. A production aligner requires a CTC acoustic model matching the original spoken language and device-memory validation.
- No APK build or on-device IPTV validation was completed in this environment.
