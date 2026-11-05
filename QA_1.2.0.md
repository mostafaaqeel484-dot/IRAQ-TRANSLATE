# QA — IRAQ-TRANSLATE 1.2.0

## Executed in local environment

- `scripts/run_local_qa.sh`: **6 / 6 PASS**.
  1. Arabic word alignment, speech cue anchor confidence, fingerprint samples.
  2. Decoder PTS continuation, pause and seek, audio fingerprint catalogue matches.
  3. Candidate SRT scoring and rejection of unrelated Arabic dialogue.
  4. MediaSession position parsing and pause behavior.
  5. Manual subtitle timing sign/limits.
  6. XMLTV programme selection, XXE/DOCTYPE rejection, conservative IMDb title matching, SRT synchronization gating.
- Kotlin source-only typecheck: **PASS** for `AutomaticSubtitleManager` and its data/algorithm dependencies, using temporary Android/JSON test stubs. Not a real Android build.
- AndroidManifest structure: checked XML parsing and existence of referenced project class files (see separate audit outcome).

## Not executed / not verified

- Full Gradle `assembleDebug` (Android SDK, Gradle unavailable in this environment).
- Full Compose/reference APK equivalence; the original R8-deobfuscated algorithms are not accessible.
- App installation, TV D-pad focus, DRM and audio-capture permission behavior on actual TV hardware.
- Provider response schema, rate limits and live credentials of SubSource, Wyzie quote/sync, or other external subtitle providers.
- XMLTV/EPG performance on very large receiver guides beyond the enforced 8MB source bound.
- End-to-end movie recognition and automatic subtitle alignment with real television content.

## Integration acceptance checklist (device required)

- [ ] Android Studio sync/build and app signing successful.
- [ ] Open and navigate ControlHub, Search, XMLTV EPG, ADB, TimingPanel using TV remote.
- [ ] Select IMDb title -> confirm `RuntimeStatus` title and provider; select season/episode; source-specific SRT downloads verified.
- [ ] Import XMLTV with correct timezone -> select current channel -> change programme -> confirm new selection.
- [ ] Confirm SRT remains in WAITING state without sufficient speech evidence; live translated speech remains visible.
- [ ] Hear 2+ distinct dialogue phrases matching SRT -> confidence/anchor updates; trigger speech re-sync and observe recovery.
- [ ] Pause and seek in actual supported player; no stale cues after seek.
- [ ] Force interrupted ADB/MediaProjection stream; inspect PCM warning and re-grant capture permission as needed.
