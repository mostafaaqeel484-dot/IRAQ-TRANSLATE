#!/usr/bin/env bash
# Pure Kotlin JVM smoke tests; does NOT replace an Android SDK build/device test.
set -euo pipefail
cd "$(dirname "$0")/.."
command -v kotlinc >/dev/null || { echo 'Please install Kotlin compiler'; exit 2; }
command -v java >/dev/null || { echo 'Please install JDK 17+'; exit 2; }
D="$(mktemp -d)"
trap 'rm -rf "$D"' EXIT
P=app/src/main/java/com/iraqtranslate
kotlinc \
 "$P/SubtitleAlignmentEngine.kt" "$P/SubtitleCandidateScorer.kt" \
 "$P/AudioFingerprintEngine.kt" "$P/ReferenceAudioFingerprintMatcher.kt" \
 "$P/VideoPtsClock.kt" "$P/MediaSessionPositionParser.kt" "$P/SubtitleTimingMath.kt" \
 "$P/XmlTvGuide.kt" "$P/SubtitleSyncPolicy.kt" "$P/TitleMatchRules.kt" \
 qa/AlignmentEngineSmoke.kt qa/AdvancedSyncSmoke.kt qa/SubtitleCandidateScorerSmoke.kt \
 qa/MediaSessionParserSmoke.kt qa/TimingMathSmoke.kt qa/NewDiscoveryAndSyncSmoke.kt \
 -include-runtime -d "$D/tests.jar"
for test in AlignmentEngineSmoke AdvancedSyncSmoke SubtitleCandidateScorerSmoke \
  MediaSessionParserSmoke TimingMathSmoke NewDiscoveryAndSyncSmoke; do
  java -cp "$D/tests.jar" "${test}Kt"
done
echo 'ALL 6 LOCAL SMOKE TESTS PASSED. Full Android build/device integration NOT tested.'
