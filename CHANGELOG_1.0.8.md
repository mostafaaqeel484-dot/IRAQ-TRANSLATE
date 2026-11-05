# IRAQ TRANSLATE 1.0.8

## Changes
- Added Arabic subtitle candidate scoring before activation, using recent translated dialogue phrases.
- Kept dialogue samples from distant scenes separate during scoring; cleared older samples when media identity changes.
- Moved provider network I/O outside the `AutomaticSubtitleManager` monitor. Only phrase throttling and media identity/anchor commits remain synchronized.
- Improved Wyzie quote retry behavior so empty or malformed quote responses do not permanently mark the phrase as processed.
- Improved reference fingerprint matching by retaining several near-equal candidates per query frame.
- Added per-frame collision resolution within each offset hypothesis and temporal sequence verification.
- Added a negative fingerprint-consensus test and a subtitle candidate scoring smoke test.
- Bumped Android versionCode to 9 and versionName to 1.0.8.

## Verification
- `SubtitleCandidateScorer.kt`, `ReferenceAudioFingerprintMatcher.kt`, `AudioFingerprintEngine.kt`, and `VideoPtsClock.kt` compiled with the local Kotlin compiler.
- `SubtitleCandidateScorerSmokeKt` passed.
- `AdvancedSyncSmokeKt` passed, including positive offset matching, rejection of temporally incoherent matches, and noisy hash matching.
- Full Android Gradle build was not run: Gradle wrapper/Gradle and Android SDK are not available in this environment.
- No APK was produced and no physical IPTV receiver test was performed.

## Remaining
- Separate bounded capture/processing pipeline is still needed to prevent loss during language identification/model loading.
- Decoder PTS still requires integration with an IPTV player's decoder callback.
- Forced alignment is not implemented.
- A real reference fingerprint catalogue must be supplied by the user; none is bundled.
