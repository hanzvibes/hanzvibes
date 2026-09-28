#!/usr/bin/env bash
set -euxo pipefail
JNI="$PWD/app/src/main/jniLibs/arm64-v8a"
docker run --rm --platform linux/arm64 \
  -v "$JNI:/ai:ro" \
  alpine:3.22 sh -lc '
    set -eux
    /ai/libcodex.so --version
    /ai/libmusl-loader.so --library-path /ai /ai/libopencode.so --version
    mkdir -p /tmp/kai-agy/etc/tls
    cp /etc/ssl/certs/ca-certificates.crt /tmp/kai-agy/etc/tls/cert.pem
    printf "nameserver 1.1.1.1\n" >/tmp/kai-agy/etc/resolv.conf
    PREFIX=/tmp/kai-agy GODEBUG=netdns=cgo SSL_CERT_FILE=/tmp/kai-agy/etc/tls/cert.pem \
      /ai/libagyld.so --library-path /ai /ai/libagycore.so --version
    PREFIX=/tmp/kai-agy GODEBUG=netdns=cgo SSL_CERT_FILE=/tmp/kai-agy/etc/tls/cert.pem \
      /ai/libagyld.so --library-path /ai /ai/libagycore.so --help >/dev/null
  '
