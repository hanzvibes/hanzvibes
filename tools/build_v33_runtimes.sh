#!/usr/bin/env bash
set -euxo pipefail

JNI="app/src/main/jniLibs/arm64-v8a"
TMP="/tmp/kai-v33"
rm -rf "$TMP" "$JNI"
mkdir -p "$TMP/codex" "$TMP/opencode" "$TMP/agy" "$JNI" app/src/main/assets

curl -fL --retry 3 \
  https://github.com/openai/codex/releases/download/rust-v0.158.0/codex-aarch64-unknown-linux-musl.tar.gz \
  -o "$TMP/codex.tar.gz"
tar -xzf "$TMP/codex.tar.gz" -C "$TMP/codex"
CODEX="$(find "$TMP/codex" -type f -name 'codex*' | head -1)"
test -n "$CODEX"
cp "$CODEX" "$JNI/libcodex.so"

curl -fL --retry 3 \
  https://github.com/anomalyco/opencode/releases/download/v1.18.33/opencode-linux-arm64-musl.tar.gz \
  -o "$TMP/opencode.tar.gz"
tar -xzf "$TMP/opencode.tar.gz" -C "$TMP/opencode"
OPENCODE="$(find "$TMP/opencode" -type f -name opencode | head -1)"
test -n "$OPENCODE"
cp "$OPENCODE" "$JNI/libopencode.so"

curl -fL --retry 3 \
  https://github.com/wallentx/antigravity-cli-termux/releases/download/v1.2.12/antigravity-termux-standalone.tar.gz \
  -o "$TMP/agy-standalone.tar.gz"
echo "88484f703ead95c3246496847269848413375aa20a7d758be6ac438b289d67f0  $TMP/agy-standalone.tar.gz" | sha256sum -c -
tar -xzf "$TMP/agy-standalone.tar.gz" -C "$TMP/agy"
test -s "$TMP/agy/agy.va39"
cp "$TMP/agy/agy.va39" "$JNI/libagycore.so"

sudo apt-get update -qq
sudo apt-get install -y -qq gcc-aarch64-linux-gnu make patchelf ca-certificates

GLIBC_BASE="https://packages-cf.termux.dev/apt/termux-glibc"
curl -fL --retry 3 \
  "$GLIBC_BASE/dists/glibc/stable/binary-aarch64/Packages" \
  -o "$TMP/glibc-Packages"
grep -Fq "Package: glibc" "$TMP/glibc-Packages"
GLIBC_FILE="$(awk 'BEGIN{p=0} /^Package: glibc$/{p=1} p && /^Filename: /{print $2; exit}' "$TMP/glibc-Packages")"
test -n "$GLIBC_FILE"
curl -fL --retry 3 "$GLIBC_BASE/$GLIBC_FILE" -o "$TMP/termux-glibc.deb"
mkdir -p "$TMP/termux-glibc"
dpkg-deb -x "$TMP/termux-glibc.deb" "$TMP/termux-glibc"
GLIBC_DIR="$(find "$TMP/termux-glibc" -type d -path '*/glibc/lib' | head -1)"
test -n "$GLIBC_DIR"
python3 tools/package_agy_glibc.py "$GLIBC_DIR" "$JNI"

curl -fL --retry 3 https://musl.libc.org/releases/musl-1.2.5.tar.gz -o "$TMP/musl.tar.gz"
echo "a9a118bbe84d8764da0ea0d28b3ab3fae8477fc7e4085d90102b8596fc7c75e4  $TMP/musl.tar.gz" | sha256sum -c -
tar -xzf "$TMP/musl.tar.gz" -C "$TMP"
sed -i 's|"/etc/resolv.conf"|"/data/data/com.kai.terminal/files/etc/resolv.conf"|' "$TMP/musl-1.2.5/src/network/resolvconf.c"
(
  cd "$TMP/musl-1.2.5"
  CC=aarch64-linux-gnu-gcc ./configure
  make CC=aarch64-linux-gnu-gcc -j2
)
cp "$TMP/musl-1.2.5/lib/libc.so" "$JNI/libmusl-loader.so"

docker run --rm --platform linux/arm64 \
  -v "$PWD/$JNI:/out" alpine:3.22 sh -lc '
    set -eux
    apk add --no-cache libstdc++
    cp -L /usr/lib/libstdc++.so.6 /out/libstdcpp.so
    cp -L /usr/lib/libgcc_s.so.1 /out/libgcccompat.so
  '
sudo chown -R "$(id -u):$(id -g)" "$JNI"

patchelf --replace-needed libstdc++.so.6 libstdcpp.so "$JNI/libopencode.so"
patchelf --replace-needed libgcc_s.so.1 libgcccompat.so "$JNI/libopencode.so"
patchelf --replace-needed libgcc_s.so.1 libgcccompat.so "$JNI/libstdcpp.so"

cp /etc/ssl/certs/ca-certificates.crt app/src/main/assets/agy-ca.pem
chmod 755 "$JNI"/*.so
file "$JNI"/*.so
du -h "$JNI"/*.so
