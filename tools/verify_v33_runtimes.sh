#!/usr/bin/env bash
set -euxo pipefail
JNI="app/src/main/jniLibs/arm64-v8a"

test -x "$JNI/libcodex.so"
test -x "$JNI/libopencode.so"
test -x "$JNI/libmusl-loader.so"
test -x "$JNI/libagyld.so"
test -x "$JNI/libagycore.so"
test -s app/src/main/assets/agy-ca.pem

for f in "$JNI"/*.so; do
  readelf -h "$f" | grep -E 'Class:|Machine:|Type:'
done

patchelf --print-needed "$JNI/libopencode.so" | grep -Fx libstdcpp.so
patchelf --print-needed "$JNI/libopencode.so" | grep -Fx libgcccompat.so
patchelf --print-needed "$JNI/libagycore.so" | grep -E '^libagyrt_'
