#!/usr/bin/env bash
set -euxo pipefail
APK="${1:?APK path required}"
ROOT="/tmp/kai-v33-apk"
rm -rf "$ROOT"
mkdir -p "$ROOT"
unzip -q "$APK" 'lib/arm64-v8a/*' -d "$ROOT"
chmod 755 "$ROOT"/lib/arm64-v8a/*.so

docker run --rm --platform linux/arm64 \
  -v "$ROOT/lib/arm64-v8a:/ai:ro" \
  alpine:3.22 sh -lc '
    set -eux
    codex() { /ai/libcodex.so "$@"; }
    opencode() { /ai/libmusl-loader.so --library-path /ai /ai/libopencode.so "$@"; }
    agy() {
      PREFIX=/tmp/kai-agy GODEBUG=netdns=cgo SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt \
        /ai/libagyld.so --library-path /ai /ai/libagycore.so "$@"
    }

    codex --version
    opencode --version
    agy --version
    codex login --help >/dev/null
    opencode auth login --help >/tmp/opencode-help.txt 2>&1
    grep -F -- "--method" /tmp/opencode-help.txt
    agy --help >/dev/null

    printf "Codex: "; codex --version
    printf "OpenCode: "; opencode --version
    printf "Antigravity: "; agy --version
  '
