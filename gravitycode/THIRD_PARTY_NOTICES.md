# Third-party notices

GravityCode v0.2 packages the Android/Termux build of **PRoot** and its runtime dependencies so the app can launch a Linux userspace without requiring Termux. PRoot is distributed under GPL-2.0-or-later. The packaged files are fetched at build time from the official Termux package repositories using pinned SHA-256 digests.

- PRoot: https://github.com/proot-me/proot
- Termux packages: https://github.com/termux/termux-packages

The runtime provisioning and sandbox-launch design was informed by the open-source **AndCode** project (MIT):

- https://github.com/yuga-hashimoto/and-code

Google Antigravity CLI itself is **not redistributed inside the APK**. On explicit user request (Setup runtime), GravityCode downloads the unmodified official `agy_cli_linux_arm64_musl.tar.gz` release from `google-antigravity/antigravity-cli`, verifies its pinned SHA-256, and installs it into the app-private runtime. Antigravity CLI remains governed by Google's own terms.
