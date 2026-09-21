# Offline MCEF distribution

DivZero compiles against the independent `gaoshanliuni/MCEF-Offline` fork, using an exact GitHub Release tag and SHA-256 values in `gradle/mcef-offline.lock.json`. It does not follow `latest` or build a moving upstream branch. The baseline remains MCEF 2.2.0 / Minecraft 26.1.1 / Java 25, matching the existing DivZero Minecraft 26.1.2 integration.

## Player installation

Download the DivZero main JAR, WebGUI, and **one** matching `mcef-offline-neoforge-<platform>.jar` from the DivZero Release Assets. Supported offline attachments: Windows x64, Linux x64, macOS Intel, macOS Apple Silicon. Select the architecture of the Java runtime running the game. Do not install multiple MCEF variants or install the original MCEF alongside the fork. The original online MCEF attachment is retained as an optional alternative only. API, sources, JSON and license attachments are not installable mods.

The platform JAR includes its entire fixed JCEF/CEF runtime. The fork extracts it locally, verifies its bytes, reuses valid cached installations and repairs corrupted files from its own JAR. It does not request a remote checksum or fall back to downloading the runtime. Webpages and AI APIs still require their normal network access. This is not an offline Minecraft installer.

Native Windows ARM64 and Linux ARM64 packages are deliberately absent: the pinned upstream packages with those names contain x64 binaries. The fork verifies PE/ELF/Mach-O headers instead of trusting filenames. See its `offline/UNSUPPORTED_PLATFORMS.md` for details.

## Build and release flow

`gradle/mcef-offline.gradle` replaces the ordinary MCEF implementation dependency with the fork's fixed compile-only API JAR and the current platform's runtime JAR. Both are downloaded only from the pinned MCEF-Offline Release and verified before compilation. `-PmcefOfflinePlatform=macos_arm64` overrides platform selection; dedicated-server profiles omit the client-only runtime. The original `webguiLocked` dependency remains solely to verify the optional online attachment.

The existing `build-jar.yml` workflow still builds/tests DivZero and stages its normal individual assets. It then runs `scripts/stage-offline-mcef.ps1` to add four offline JARs, corresponding source, notices and the fork release manifest. Hashes, dependency metadata and installation instructions are updated. A complete release dry-run verifies all new assets before upload. The publication job rechecks the immutable lock and the returned public download URLs. No combined installation ZIP is published.

## Updating the fork

1. Publish a successful tested Release in MCEF-Offline.
2. Review its source commit, native runtime changes, platform architecture checks and `mcef-offline-release.json`.
3. Update the downstream lock with that exact tag/source commit and all matching asset SHA-256/size records. API, source, platform JARs and manifest must come from the same release. Include the notices record too.
4. Run the DivZero build and release dry-run; do not silently fall back to upstream if the fork cannot be resolved or a checksum differs.

The fork provides its modified source and pinned JCEF source in `mcef-offline-corresponding-sources.jar`; license resources accompany the binaries. The older source attachment only corresponds to the optional original online MCEF. Retain these notices when redistributing.

Validation distinguishes compilation, packaging, actual bundled extraction/repair tests, and real game/browser rendering. A successful CI release is not a claim that every supported platform has passed in-game rendering or that DivZero has completed V1 acceptance. Test in a backed-up instance with game dependencies already installed, clear the fork's cache, disconnect networking and verify first-start local page rendering.
