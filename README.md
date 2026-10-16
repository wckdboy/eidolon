# Eidolon

**Your eye. Your reality.** An Android-first camera with two creative intentions and two levels of control.

- **Pure:** sensor RAW/DNG plus a companion JPEG where the camera supports it. Requests noise reduction and edge enhancement OFF when exposed by the device. Falls back visibly to JPEG when the RAW session is unavailable.
- **Bloom:** a warm, saturated rendering with gentler contrast and an adjustable intensity. A shared color matrix drives the preview and saved JPEG. This is the first color treatment, not a finished computational photography pipeline.
- **Fun:** automatic exposure and focus, a big shutter, composition grid, camera switching, and quick access to the last photo.
- **Pro:** manual ISO and shutter speed, manual focus, exposure compensation in auto exposure, live metering, and an automatic reset. Unsupported controls stay disabled.

Native Kotlin / Jetpack Compose UI; Camera2 capture; local MediaStore storage. Android 10+ (API 29). No account, analytics, network permission, location collection, or cloud processing.

## Download

Get **[v0.1.0-alpha.2 — Clear View](https://github.com/wckdboy/eidolon/releases/tag/v0.1.0-alpha.2)** from GitHub Releases. This is an early testing release, not a production camera recommendation.

| Asset | Purpose |
| --- | --- |
| `eidolon-0.1.0-alpha.2-release.apk` | Signed, non-debuggable universal APK; recommended for trying the app. |
| `eidolon-0.1.0-alpha.2-debug.apk` | Debuggable universal APK with a separate application ID; can coexist with the release app. |
| `README.md` | These setup instructions, features, and known limits. |
| `SHA256SUMS` | Download checksums. |
| `BUILD-INFO.txt` | Source commit, build versions, validation, and signing certificate fingerprint. |

Download an APK on your Android phone, open it, and allow installation from your browser or file manager when Android asks. APKs support Android 10 and newer. The release app uses `studio.eidolon.camera`; the debug app uses `studio.eidolon.camera.debug`.

If you installed the earlier locally built debug APK under `studio.eidolon.camera`, uninstall that build before installing the signed release APK: their signatures differ. Photos already saved in Pictures/Eidolon remain in shared storage.

Both variants save into Pictures/Eidolon. iOS builds are not available yet.

## Run

Open this directory in Android Studio, use JDK 17+, and install Android SDK platform 37. Connect a phone with USB debugging enabled, or use an Android emulator.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Set `ANDROID_HOME` to your SDK directory, or create an untracked `local.properties` containing `sdk.dir=/path/to/Android/sdk`.

Grant camera access from the opening screen. Photos appear under **Pictures/Eidolon**. Pure produces a matched `.dng` / `.jpg` pair when available; Bloom produces a `.jpg`. The gallery button opens the last photo through your installed image viewer.

## Release builds

The maintainer's signing identity lives in the ignored `.signing/` directory. Keep an encrypted backup of that directory: future compatible release updates require the same signing key. Never upload it as a release asset or commit it.

For a new independent signing identity, run this once with JDK 17+ on PATH or `JAVA_HOME` set:

```sh
python3 scripts/create-signing-key.py
./gradlew :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug :app:lintRelease
```

The script refuses to overwrite existing signing material. With `.signing/release.properties` present, `assembleRelease` generates a signed APK at `app/build/outputs/apk/release/app-release.apk`. Without local signing material, Gradle produces an unsigned release APK, which must be signed before distribution. The debug APK is under `app/build/outputs/apk/debug/app-debug.apk`.

## Capture architecture

`CaptureModel.kt` holds capture intent, capabilities, orientation math, and Bloom rendering coefficients. `CameraEngine.kt` confines Camera2 lifecycle and frame matching to a camera thread. Capture results, RAW images, and JPEGs must have matching, non-null sensor timestamps before saving. Capture settings are frozen for each shot; only one save is in flight. Failed writes remove their MediaStore entries. Reader leases keep in-flight RAW images valid while the camera shuts down; readers close after the save releases its image.

`PhotoStore.kt` writes DNG/JPEG pairs through pending MediaStore entries. DNG encoding and Bloom rendering run on a separate worker. DNGs write directly into MediaStore without a temporary DNG file and second copy. Bloom reads source EXIF from memory, releases its source bitmap before encoding, and skips rendering and recompression entirely at 0%. The UI gets immutable capability and metering snapshots. Capture and composition state are separate from Android storage and presentation, providing a starting point for a later native iOS implementation.

## Current UI and responsiveness improvements

Alpha.2 introduces a quieter, image-first layout: compact Pure/Bloom tabs, a smaller header, neutral shutter, and Pro controls below the viewfinder. Tap a Pro control to reveal its slider, or tap Feeling in Bloom to adjust the grade. The preview has no decorative gradient or captions over the photograph. Fun resets manual controls and EV; the last photo is remembered across launches.

Bloom intensity changes update the preview without resubmitting sensor controls. Sensor updates are coalesced over 16 ms, identical UI snapshots are skipped, and completed capture timeouts are cancelled immediately. These changes remove avoidable work; real-device speed and memory benchmarks remain to be collected.

## Honest limits of this first build

- RAW is hardware-dependent. A DNG is sensor data exposed by the manufacturer, not a guarantee that every internal correction is absent. Pure’s companion JPEG and viewfinder still go through the phone’s rendering pipeline. See Android’s [RAW/DNG API](https://developer.android.com/reference/android/hardware/camera2/DngCreator) and [processing controls](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest).
- Bloom currently grades an 8-bit camera JPEG. It does not yet develop the DNG, merge exposures, recover clipped highlights, apply local tone mapping, or synthesize scene detail. Preview and capture can differ because the device renders those streams separately.
- JPEG capture prefers 4:3 at up to 12.5 MP to bound memory use; if every available size exceeds that, the smallest supported size is used. RAW uses the largest exposed sensor size. Shutter time is capped at one second in this build.
- This first UI is designed for portrait phones. Tablet, foldable and landscape layouts, accessibility at very large text scales, and persistent preferences need further work.
- No tap-to-meter, lens zoom selector, burst, flash controls, histogram, focus peaking, manual white balance, in-app library, or background capture yet.
- Camera characteristics are capability claims; real phone testing must verify output, focus, framing, color, latency, thermal behavior, and repeated capture stability. Emulator tests cannot establish real sensor quality.
- iOS is not implemented. A later AVFoundation capture engine should preserve the same Pure/Bloom intent and Fun/Pro control split while checking capabilities independently.

## Next imaging milestone

Start with one reference Android phone and a fixed collection of daylight, skin, foliage, neon, backlit, and low-light scenes. Measure capture latency and inspect its DNGs before tuning Pure. Evolve Bloom toward a deliberate scene-referred rendering: controlled highlight roll-off, natural skin color, selective local contrast, and subtle bloom, always keeping the original. Benchmark each step on the actual device before adding it to the shutter path.

## Validation

The debug build, eight unit tests (color, orientation, timestamp matching, sensor-control changes, and concurrent reader ownership), and Android lint are runnable with the command above. Five Android instrumentation tests cover original-byte preservation, zero-strength Bloom, graded JPEG metadata, storage-failure rollback, and saving while the camera closes followed by reopening for another capture. Run them on a connected emulator or phone with `./gradlew :app:connectedDebugAndroidTest`. Lint currently emits non-blocking warnings, including the portrait layout and platform EXIF API. Hardware acceptance testing remains required before a production release.

In alpha.1, on an API 36.1 emulator, the permission flow, live preview, camera switching, background/resume recovery, Pure DNG + JPEG capture, and Bloom JPEG capture were exercised. Both JPEGs decoded successfully with orientation preserved. The DNG contained a CFA photometric tag and DNG version metadata. A Pro capture requested ISO 469 and the saved JPEG reported ISO 469. No runtime crashes were logged during these checks. Synthetic emulator scenes were used; these checks do not establish photographic quality.

Alpha.1 interface captures (the current layout has changed): [Pure / Fun](https://github.com/wckdboy/eidolon/blob/main/artifacts/screenshots/pure.png) and [Bloom / Pro](https://github.com/wckdboy/eidolon/blob/main/artifacts/screenshots/bloom-pro.png).

Alpha.2 passed all eight unit tests and five instrumentation tests on a fresh API 36.1 emulator. The capture integration test saved a DNG/JPEG pair while closing the camera, decoded the JPEG, checked DNG dimensions, reopened the camera, and saved a Bloom JPEG. Both build variants and lint checks completed successfully.

Alpha.2 visual inspection remains pending: the computer-control tool could not access the emulator window. Real-phone latency, color, and memory benchmarks remain unmeasured.
