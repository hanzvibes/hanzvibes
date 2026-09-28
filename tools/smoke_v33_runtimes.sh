#!/usr/bin/env bash
set -euxo pipefail
JNI="$PWD/app/src/main/jniLibs/arm64-v8a"
ASSETS="$PWD/app/src/main/assets"

docker run --rm --platform linux/arm64 \
  -v "$JNI:/ai:ro" \
  -v "$ASSETS:/assets:ro" \
  alpine:3.22 sh -lc '
    set -eux
    /ai/libcodex.so --version
    /ai/libmusl-loader.so --library-path /ai /ai/libopencode.so --version

    mkdir -p /tmp/kai-agy/lib /tmp/kai-agy/etc/tls
    cp /assets/agy-ca.pem /tmp/kai-agy/etc/tls/cert.pem
    printf "nameserver 1.1.1.1\n" >/tmp/kai-agy/etc/resolv.conf
    while IFS="$(printf "\t")" read -r original safe; do
      case "$original" in ""|"#"*) continue;; esac
      ln -sf "/ai/$safe" "/tmp/kai-agy/lib/$original"
    done </assets/agy-lib-map.txt

    PREFIX=/tmp/kai-agy GODEBUG=netdns=cgo SSL_CERT_FILE=/tmp/kai-agy/etc/tls/cert.pem \
      /ai/libagyld.so --library-path /tmp/kai-agy/lib /ai/libagycore.so --version
    PREFIX=/tmp/kai-agy GODEBUG=netdns=cgo SSL_CERT_FILE=/tmp/kai-agy/etc/tls/cert.pem \
      /ai/libagyld.so --library-path /tmp/kai-agy/lib /ai/libagycore.so --help >/dev/null
  '
