# VectorCraft for Android

This is an independent Android port of [storytold/vectorcraft](https://github.com/storytold/vectorcraft), maintained on the `android` branch. It runs the original Rust/egui UI and document engine through GameActivity. Menus, panels, commands, file formats and codecs remain those of the upstream app. This is not an official ArtCraft build. Restricted ArtCraft marks are replaced on Android with the permissively licensed application icon/name.

## Install and build

Distribution APKs contain **arm64-v8a only**, require Android 8.0/API 26 or newer and target API 36. A tablet-sized display is recommended for the original desktop panels. Release APKs are non-debuggable; the debug sandbox control-argument mechanism is compiled out.

Build tools: PowerShell 7, Rust with `aarch64-linux-android` (and `x86_64-linux-android` for emulator testing), JDK 17, Android SDK 36 and NDK 28.2.13676358. The checked-in Gradle wrapper downloads Gradle 8.13. Optional `CRAFT_FONTS_DIR` points to a checkout of `storytold/craft-fonts`; fonts are not committed to this fork.

```powershell
rustup target add aarch64-linux-android x86_64-linux-android
$env:ANDROID_HOME = 'C:\Android\Sdk'
$env:CRAFT_FONTS_DIR = (Resolve-Path '../craft-fonts').Path
# A debug build for an emulator:
./packaging/android/build.ps1 -Abi x86_64
```

For distribution, provide `CRAFT_ANDROID_KEYSTORE`, `CRAFT_ANDROID_KEY_ALIAS`, `CRAFT_ANDROID_STORE_PASSWORD` and, if different, `CRAFT_ANDROID_KEY_PASSWORD` in the build process environment. Never commit a keystore/password or pass a password as a Gradle command-line property. For example, read the password using `Read-Host -AsSecureString`, convert it only in the local process and remove the environment variables in a `finally` block.

```powershell
./packaging/android/build.ps1 -Release
# packaging/android/build/outputs/apk/release/CraftAndroid-release.apk
```

`-Release` rejects any ABI other than arm64-v8a and requires signing credentials. It uses Cargo's optimized release profile, strips symbols and remaps build-machine source paths. Native libraries have 16 KiB ELF alignment; AGP aligns uncompressed APK libraries. Debug and release JNI directories are separate. `-PackageOnly` packages an already built matching profile; `-Check` performs the Rust target check. License/attribution files for the application, assets, fonts, Cargo packages and Android libraries are included under APK `assets/licenses`.

## Android integration

The small `platform/android` crate and `packaging/android/src` Java adapter supply the platform features; the app is not a WebView. JNI unsafe operations are isolated to two documented borrowed-pointer conversions. Exported entry-point attributes are the only additional unsafe declarations in the app shell. The rest of the engines retain their original safety and dependency rules.

- MotionEvent tool types, pressure, tilt/orientation, hover, side buttons, eraser and mouse wheel enter egui. Pressure/tilt affect tools whose original engine supports them; Android orientation is not misrepresented as barrel rotation.
- Palm rejection suppresses finger sequences while a pen is near/in contact, cancels an earlier touch when the pen arrives, and keeps a rejected palm suppressed until it lifts. Fresh touch works after the pen leaves. Lifecycle cancellation clears the state.
- Android's document picker handles open/save/folder access. Each app exposes its **Workspace** in Android Files. Default working documents live there; private settings, control credentials and caches do not.
- External provider files use private working copies and explicit writes back to the granted URI. Save errors retain the local copy and are reported before a document is marked saved. A picker is modal: editing pauses, continuations match a random request ID, and incoming shared files wait until it closes.
- Open linked-media projects as an entire granted project folder or copy the complete folder into the app's Workspace. Granting one file cannot grant its siblings. Provider capabilities determine cloud move/rename/delete support; arbitrary cloud providers cannot supply POSIX filesystem semantics.
- Android text/image clipboard, system open/share intents, external editor intents, back/unsaved-close handling, system-bar/IME insets and keyboard shortcuts bridge to the existing UI. Original app icons have adaptive/themed launcher variants.
- On emulator hardware only, OpenGL is selected to avoid a reproduced SwiftShader Vulkan crash. Real devices retain the native GPU backend selection with Android-compatible device limits.

Pressure feeds the original drawing tools; the eraser tip temporarily selects the existing eraser. Verified: rectangle creation/fill, native project save/reopen, PNG export.

## Validation and limits

All seven apps were built for Android, launched and visually inspected on a Pixel 9 AVD resized to 2560×1600 at density 240. Each repository's Android library clippy check, layer check and WebAssembly gate was exercised. The common Activity instrumentation validates pressure, tilt/orientation, eraser, side buttons, palm rejection, canonical URI lookup and safe Unicode filenames. All seven signed arm64 builds were also installed, launched and visually inspected on an explicitly authorized OPD2415 running Android 16/API 36, without rebooting the device. Its display settings were preserved. Its hardware exposes pressure, orientation and tilt. Batched hover initially reproduced a false contact on that device; preserving ACTION_HOVER_MOVE in historical samples fixes it, with the Activity regression test failing before and passing after the fix. Synthetic input alone is not a physical digitizer latency/accuracy test.

The migration does not claim exhaustive parity across every upstream command or device driver. GPU/digitizer behavior on other devices, pen latency, external cloud providers and physical printing still need device-specific acceptance testing. Existing upstream limitations remain. Android may kill background processes; save work before switching away for a long time. Returning from a normally closed GUI starts a new process because winit allows one event loop per process; document providers run separately.

To run the platform checks (substitute only an explicitly authorized emulator serial):

```powershell
cargo test -p craft-android --lib
javac -d packaging/android/build/palm-tests packaging/android/src/ai/craft/android/PalmRejection.java packaging/android/tests/PalmRejectionTest.java
java -ea -cp packaging/android/build/palm-tests ai.craft.android.PalmRejectionTest
./packaging/android/gradlew.bat -p packaging/android -PcraftApp=vectorcraft -PcraftLabel=VectorCraft -PcraftAbis=x86_64 assembleDebugAndroidTest
adb -s emulator-5554 install -r packaging/android/build/outputs/apk/debug/CraftAndroid-debug.apk
adb -s emulator-5554 install -r packaging/android/build/outputs/apk/androidTest/debug/CraftAndroid-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w io.github.tqmane.vectorcraft.test/ai.craft.android.InputInstrumentation
```

Do not use an unqualified `adb`, `connectedAndroidTest`, `adb -d`, or an unauthorized physical device for validation. Signed release instrumentation is available with `-PcraftTestBuildType=release -PcraftAbis=arm64-v8a assembleReleaseAndroidTest` and the same signing environment; install it only on an explicitly authorized device. Never reboot the device as part of these checks. The debug-only `android-test-args.json` in the app's internal files directory can opt into the original documented control protocol via `adb run-as`; delete it after testing. Release builds ignore it.
