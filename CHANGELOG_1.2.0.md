# Changelog v1.2.0

- MediaSelection + explicit IMDb film/season/episode selection consumed by `CaptureService`.
- XMLTV EPG import (local SAF + user HTTPS), current programme resolution, channel scheduling and exact-only IMDb title matching.
- Direct SRT provider fallback from a selected media identity without Wyzie /quote.
- Optional SubSource official-route provider with user X-API-Key (response parsing experimental; not live-verified).
- SubtitleSyncPolicy gates external SRT until speech anchor or usable player/decoder clock; translated audio remains available while waiting.
- TimingPanel speech re-sync service action; invalidation of stale anchors on detected seek/discontinuity.
- PCM activity health shown in ControlHub.
- Fixed Kotlin ArrayDeque.addLast compilation bug in AutomaticSubtitleManager.
- Added new Kotlin smoke test; six local smoke tests pass.

No reference binaries/server assets copied. Not an Android-built APK. See `README_1.2.0_AR.md` and `QA_1.2.0.md`.
