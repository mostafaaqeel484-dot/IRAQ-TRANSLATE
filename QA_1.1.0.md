# QA — IRAQ TRANSLATE 1.1.0 (source edition)

Test date: 2026-11-05

## Executed and passed

- [x] Kotlin `MediaSessionParserSmoke`: player position extrapolation / paused state.
- [x] Kotlin `SubtitleCandidateScorerSmoke`: Arabic dialogue matching.
- [x] Kotlin `AdvancedSyncSmoke`: PTS, seek/pause, fingerprint match.
- [x] Kotlin `AlignmentEngineSmoke`: alignment anchors and audio fingerprints.
- [x] Kotlin `TimingMathSmoke`: new manual delay/advance arithmetic, negative media time at playback start, clamp ±15 s.
- [x] `AndroidManifest.xml` XML parse and manifest class-file existence for all 11 registered app components.
- [x] Kotlin source-level syntax scan found no syntax/`expecting` errors. Full typechecking not possible without the Android/AndroidX classpath.

## Not executed / open validation

- [ ] Full Android Gradle build and APK signing: Android SDK and Gradle runtime unavailable in this environment.
- [ ] Device ADB mDNS pairing and connection; wireless debugging device/ports/network required.
- [ ] Actual playback/MediaProjection capture and Echo Reference system permission on Android TV.
- [ ] Overlay stroke/RTL rendering at TV resolutions and remote DPAD navigation.
- [ ] Subtitle import at playback position, seek/pause sync, service lifecycle and Android 15 foreground-service constraints.
- [ ] IMDb endpoint reachability and third-party subtitle provider API responses.

The source is a functional implementation attempt grounded in the evidence, not a verified reference-equivalent release APK.
