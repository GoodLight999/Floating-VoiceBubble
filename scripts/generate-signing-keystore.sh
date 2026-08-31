#!/usr/bin/env bash
set -euo pipefail

OUT="${1:-floating-voicebubble-signing.jks}"
ALIAS="${FVB_SIGNING_KEY_ALIAS:-floating-voicebubble}"
: "${FVB_SIGNING_STORE_PASSWORD:?Set FVB_SIGNING_STORE_PASSWORD before running this script}"
KEY_PASSWORD="${FVB_SIGNING_KEY_PASSWORD:-$FVB_SIGNING_STORE_PASSWORD}"

if [[ -e "$OUT" ]]; then
  echo "Refusing to overwrite existing keystore: $OUT" >&2
  exit 2
fi

keytool -genkeypair \
  -keystore "$OUT" \
  -storetype JKS \
  -storepass "$FVB_SIGNING_STORE_PASSWORD" \
  -alias "$ALIAS" \
  -keypass "$KEY_PASSWORD" \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -dname "CN=Floating VoiceBubble Stable Signing, O=GoodLight999" \
  -sigalg SHA256withRSA

keytool -list -v \
  -keystore "$OUT" \
  -storepass "$FVB_SIGNING_STORE_PASSWORD" \
  -alias "$ALIAS" \
  | grep -E "Alias name:|SHA256:"

echo
echo "Created $OUT. Keep this file private and back it up permanently."
echo "For GitHub Actions, base64-encode the entire file into FVB_SIGNING_KEYSTORE_B64"
echo "and configure the store password, alias, and key password as GitHub Actions secrets."
