# Pinned SM8750 Hexagon runtime

This directory contains the eight libraries needed by Leap 8's isolated HTP v79 worker,
the official public C/C++ headers and the upstream MIT license. They are copied without
modification from the official ggml-org b11982 Android ARM64 Snapdragon archive.
`manifest.json` records its URL, 63,373,914-byte archive size, official archive SHA-256,
and the size and SHA-256 of every retained upstream file.

The archive includes more CLI/server libraries and DSP variants; these are intentionally
not retained or packaged. No phone-owned vendor library is included. The b11982 runtime
must never replace the separate b10333 core in `llama-runtime/src/main/jniLibs/`.

Run `pwsh -File tools/build-sm8750.ps1 -VerifyOnly` from the project root to check these
files without compiling, downloading anything or accessing the connected phone.
Run the same helper without `-VerifyOnly`, with JDK/SDK options as needed, to build.
The generated APK bundles these libraries as assets, extracted and hash-checked at runtime
before loading in `:leap8_npu` only. The JNI adapter is rebuilt from project source.

This is a reproducible runtime-input bundle, not a complete source distribution of
llama.cpp. The corresponding upstream source is
<https://github.com/ggml-org/llama.cpp/tree/b11982>. See `LICENSE` and the project's
`THIRD_PARTY_NOTICES.md` for attribution and other dependency obligations.
