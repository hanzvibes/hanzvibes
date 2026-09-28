#!/usr/bin/env bash
set -euxo pipefail
APK="${1:?APK path required}"
ROOT="/tmp/kai-v33-apk"
rm -rf "$ROOT"
mkdir -p "$ROOT/native" "$ROOT/assets"
unzip -q "$APK" 'lib/arm64-v8a/*' -d "$ROOT/native"
unzip -q "$APK" 'assets/agy-ca.pem' 'assets/agy-lib-map.txt' -d "$ROOT/assets"
chmod 755 "$ROOT/native/lib/arm64-v8a/"*.so

docker run --rm --platform linux/arm64 \
  -v "$ROOT/native/lib/arm64-v8a:/ai:ro" \
  -v "$ROOT/assets/assets:/assets:ro" \
  alpine:3.22 sh -lc '
    set -eux
    codex() { /ai/libcodex.so "$@"; }
    opencode() { /ai/libmusl-loader.so --library-path /ai /ai/libopencode.so "$@"; }

    mkdir -p /tmp/kai-agy/lib /tmp/kai-agy/etc/tls
    cp /assets/agy-ca.pem /tmp/kai-agy/etc/tls/cert.pem
    printf "nameserver 1.1.1.1\n" >/tmp/kai-agy/etc/resolv.conf
    while IFS="$(printf "\t")" read -r original safe; do
      case "$original" in ""|"#"*) continue;; esac
      ln -sf "/ai/$safe" "/tmp/kai-agy/lib/$original"
    done </assets/agy-lib-map.txt
    agy() {
      PREFIX=/tmp/kai-agy GODEBUG=netdns=cgo SSL_CERT_FILE=/tmp/kai-agy/etc/tls/cert.pem \
        /ai/libagyld.so --library-path /tmp/kai-agy/lib /ai/libagycore.so "$@"
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
