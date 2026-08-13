# Stable APK signing

Floating VoiceBubble uses microphone and Accessibility privileges. Its signing private key is therefore treated as an update-security credential and must **never** be committed to this public repository.

## One-time key creation

Set private passwords in the shell, then run:

```bash
export FVB_SIGNING_STORE_PASSWORD='...'
export FVB_SIGNING_KEY_PASSWORD='...'
export FVB_SIGNING_KEY_ALIAS='floating-voicebubble'
bash scripts/generate-signing-keystore.sh ~/secure/floating-voicebubble-signing.jks
```

Back up the generated keystore permanently. Losing it means future APKs cannot update an installation signed by that key.

## GitHub Actions secrets

Configure these repository Actions secrets:

- `FVB_SIGNING_KEYSTORE_B64`: base64 of the complete JKS file
- `FVB_SIGNING_STORE_PASSWORD`
- `FVB_SIGNING_KEY_ALIAS`
- `FVB_SIGNING_KEY_PASSWORD`
- optional `FVB_SIGNING_CERT_SHA256`: expected certificate SHA-256 fingerprint, without spaces

Example base64 commands:

```bash
# GNU/Linux
base64 -w0 ~/secure/floating-voicebubble-signing.jks

# macOS
base64 < ~/secure/floating-voicebubble-signing.jks | tr -d '\n'
```

When all required secrets are present, both debug and release APKs use the same stable signing identity. CI verifies the resulting APK signature and, when `FVB_SIGNING_CERT_SHA256` is configured, verifies the certificate fingerprint exactly.

If the signing secrets are absent, CI deliberately falls back to the Android debug signer for build/test continuity and emits a warning. Such an APK is **not** the stable-install artifact and may require uninstalling an APK produced on another fresh runner.
