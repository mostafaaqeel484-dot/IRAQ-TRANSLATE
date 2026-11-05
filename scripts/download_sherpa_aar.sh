#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/libs/sherpa-onnx-static-link-onnxruntime-1.12.15.aar"
PART="$DEST.part"
URL="https://huggingface.co/csukuangfj/sherpa-onnx-libs/resolve/dea0ed4bc892fe6725c87874bea5c2e7cc946132/android/aar/1.12.15/sherpa-onnx-static-link-onnxruntime-1.12.15.aar?download=true"
SHA256="350d4a42a7ccde5a3b7dda207f954d6d57de981fe747106b0e3091e7b947c236"
verify_hash() {
  local file="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    echo "$SHA256  $file" | sha256sum -c --status
  elif command -v shasum >/dev/null 2>&1; then
    [[ "$(shasum -a 256 "$file" | awk '{print $1}')" == "$SHA256" ]]
  else
    echo "Need sha256sum or shasum to verify the AAR." >&2
    return 2
  fi
}
mkdir -p "$(dirname "$DEST")"
if [[ -f "$DEST" ]] && verify_hash "$DEST"; then
  echo "Sherpa-ONNX AAR already verified."
  exit 0
fi
rm -f "$PART"
curl --fail --location --retry 3 --connect-timeout 20 "$URL" --output "$PART"
verify_hash "$PART"
mv "$PART" "$DEST"
echo "Sherpa-ONNX AAR downloaded and verified: $DEST"
