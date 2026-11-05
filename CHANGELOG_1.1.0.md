# IRAQ TRANSLATE — v1.1.0 (source-only)

- Added Arabic ControlHubActivity as launcher; kept original MainActivity for advanced settings.
- Built PairingPanelActivity with ADB TLS pairing/connection plus mDNS discovery, separate ports, and connection status.
- Replaced simple OverlayController TextView with adjustable translucent GlassSubtitle, bottom positioning and live preview.
- Added SubtitleTimingMath and SubtitleTimingStore with real manual offset in subtitle cue lookup, plus TimingPanelActivity.
- Added runtime NowPlaying, Sync status, and capture state based on existing AutomaticSubtitleManager and CaptureService.
- Added SearchActivity with IMDb title lookup and local SRT selection integrated into AutomaticSubtitleManager at the next capture session.
- Added ChoicePanelActivity sharing selected-language preferences with the original engine.
- Added optional BootReceiver notification only, and an informational activation/update panel (no proprietary services copied).
- Extended manifest for screens and boot-notification opt-in, kept targetSdk 35, updated version to 1.1.0.
- All five local Kotlin smoke tests passed. No Android SDK/Gradle in build environment; no APK build or physical receiver test.

See `README_NEW_SOURCE_AR.md` for setup, limitations and unresolved reference features.
