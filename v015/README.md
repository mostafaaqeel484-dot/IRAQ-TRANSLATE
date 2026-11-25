# AI Translate V0.15.1 — Live Audio + OCR (experimental)

This is a **new, independent V0.15 implementation** placed in the existing Gazal repository, not a binary update of the separately pictured V0.14 source (that exact source was unavailable). Existing `:app` is untouched.

## Outputs
- `:v015:assembleMobileDebug` → `v015/build/outputs/apk/mobile/debug/v015-mobile-debug.apk`
- `:v015:assembleMt5889Debug` → `v015/build/outputs/apk/mt5889/debug/v015-mt5889-debug.apk`

## Live audio — new in 0.15.1
- Continuous local **AudioRecord 16kHz PCM16** capture from eligible game/media playback via Android AudioPlaybackCapture and user MediaProjection consent.
- Long-running microphone capture for an external audio source (e.g. listening to a television using phone or microphone-equipped device).
- On-device streaming English/Japanese speech-to-text through Vosk; official 40MB/48MB models downloaded once, then cached in private app storage. No raw audio uploaded.
- Final ASR phrases and throttled partial recognition translated to Arabic and displayed persistently over other apps. No strobing between partial updates.
- The same six translation engines apply to speech-transcribed text. If a cloud provider was explicitly chosen, speech-to-text **text**, not raw audio, may leave the device.
- **Limits**: Native HDMI, secure playback, DVB TV audio, and apps with disabled AudioPlaybackCapture may not be capturable. No privileged OS bypass is implemented. Microphone option is an explicit fallback. Real-device verification pending.

## Implemented
- Android 10+ screen capture with **explicit MediaProjection consent per session**, and foreground service for API 34.
- ML Kit English (Latin) and Japanese OCR → Arabic translation; downloaded ML Kit offline translation model needed on first use.
- Displays Arabic captions in an overlay after user grants overlay permission; black translucent background and larger TV subtitles.
- OCR deduplication, 160-entry in-memory LRU translation cache, asynchronous single cloud worker and local fallback.
- Six providers: ML Kit local, LibreTranslate custom HTTPS server, Gemini, Groq, Azure Translator and DeepL. Only the selected provider receives extracted OCR *text*, never screenshots. Secrets are encrypted using the Android Keystore (AES-GCM).
- Native D-pad accessible TV controls and mobile buttons; test input and optional microphone SpeechRecognizer intent.

## Limitations and safety
- **No guarantee of DVB/HDMI/native television audio/video capture.** Other apps may block capture. Protected content / FLAG_SECURE can't be captured.
- The voice button uses system speech recognizer for microphone **not** audio directly from games/TV.
- Real-time speed, MediaProjection compatibility and overlay display need **physical device QA**. Successful GitHub CI compilation is not hardware validation.
- API keys are provided by the user; provider quotas/billing vary. Cloud providers require connectivity; local model initial download needs internet. 
- When an offline model is missing and download fails, user sees an error; it does not claim success.
- MT5889 hardware family is not sufficient to infer whether OS userspace is 32- or 64-bit; this Java/ML Kit implementation is **not architecture-pinned**, and ARM ABI compatibility must be tested on the target TV.

## Install
Download the corresponding debug APK from the GitHub Actions build artifact, unzip it, and install via `adb install -r filename.apk`. Mobile and TV have distinct package IDs and can coexist.
