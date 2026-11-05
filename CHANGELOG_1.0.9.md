# IRAQ TRANSLATE 1.0.9

## Implemented
- Split PCM capture and DSP/ASR processing into separate producer and consumer threads.
- Added a bounded queue of 50 timestamped PCM chunks (~10 seconds at the current 200 ms read size).
- Added explicit queue-overrun shutdown to prevent silent sample loss and timeline compression.
- Added Media3 `VideoFrameMetadataListener` bridge forwarding genuine rendered-frame PTS to `CaptureService`.

## Not completed
- The repository does not contain an IPTV player instance. `Media3DecoderPtsBridge` must be attached by the host player before `prepare()`.
- No true CTC forced-alignment model/inference path is integrated. Vosk timestamps and fuzzy subtitle matching are not forced alignment.
- No APK build or physical-device validation was performed.
