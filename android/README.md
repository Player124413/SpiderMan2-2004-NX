# Spider-Man 2 Android

An **arm64 Vulkan** target for the original Xbox release. The game's x86 code is
statically recompiled before the APK is built; this is not an emulator. The APK
contains no game code, disc image, movies, music, or other assets. Build and use
it only with files from your own legally acquired disc.

## What is included

- A real Android launcher that checks permissions and the game folder before launch.
- A Vulkan 1.1 renderer with a threaded D3D8 command bridge, shader cache,
  pipeline cache, render-target scaling, texture ageing, anisotropic filtering and
  a 16 KB page-size-safe native linker setting.
- Device-safe defaults: 67% render scale, 60 FPS cap, V-Sync and 4× AF. Change
  them from the launcher before starting the game.
- Native SDL controller input and rumble, merged with a low-latency multi-touch
  Xbox controller on port 0.
- **Original Xbox controls only:** two sticks, D-pad, A/B/X/Y, Black/White,
  left/right triggers, Start and Back. There are no gameplay macros or Android-only
  action buttons. Hold three fingers for 0.5 seconds to enter the editor, then drag
  a control or pinch it with a second finger to resize it; layouts persist across
  phone and tablet resolutions.
- Pause/resume-aware Vulkan surface handling and an Android audio output fed from
  the port's DirectSound mixer.

The Vulkan renderer and threaded D3D8 bridge were adapted under MIT licence from
[Black-Android](https://github.com/RanggaCasper/Black-Android), as documented in
[`THIRD_PARTY.md`](THIRD_PARTY.md). They are adapted to this repo's XDK 5849
configuration and use its own Spider-Man-specific hooks.

## Requirements

- Android Studio with SDK Platform 35, Android NDK **28.2.13676358**, CMake
  **3.22.1**, JDK 17 and Git.
- A 64-bit ARM device on Android 10+ with Vulkan 1.1, at least 4 GB RAM and
  adequate free storage for the extracted game.
- A PC to generate C++ from your own `default.xbe`. The generation can be done
  once, then Gradle compiles that generated code for `arm64-v8a`.

## CI mock APK

[`.github/workflows/android-mock-apk.yml`](../.github/workflows/android-mock-apk.yml)
packages an installable **workflow-test APK** on every Android change and can also
be run manually from the Actions tab. It deliberately runs:

```sh
./gradlew :app:assembleDebug -PmockBuild=true
```

This selects Java-only `GameActivity` and `NativeBridge` test doubles. The mock
APK validates the Android manifest, resources, launcher, editable Xbox touch
layout, Java 17 compilation, dexing, signing and artifact upload. It contains
**no** XBE, ISO, generated recompilation code, SDL checkout, NDK output, native
runtime or game asset. The workflow checks that condition before upload; the
artifact is therefore only a CI smoke-test and cannot play the game.

The real native build intentionally has no URL-based game download step. Keep an
owned disc image and its extracted files off GitHub Actions, Git history, logs and
artifacts. Generate code locally (or on a private owner-controlled runner) from
your own `default.xbe`, then use the production build below.

## Build

From the repository root, first generate code from your owned XBE:

```sh
cmake -S tools/xbe -B build-tools -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build build-tools
mkdir -p assets
action="/path/to/your/default.xbe"
cp "$action" assets/default.xbe
build-tools/xbe_recomp config/game.toml --xdk sdk/xdk
```

Then open `android/` in Android Studio or run:

```sh
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`fetchSDL` checks out the pinned SDL source at `android/third_party/SDL` on the
first Gradle build. CMake fetches the pinned glslang and Dear ImGui sources. Those
directories, Gradle builds and generated game code remain untracked.

To point at generated code outside this checkout, pass both properties:

```sh
./gradlew assembleRelease -PxbeGenerated=/absolute/generated/game -PxbeImage=/absolute/default.xbe
```

## Install game files

1. Install and open the APK; grant **All files access** when Android asks.
2. Copy the *extracted* contents of your own disc to
   `/storage/emulated/0/SpiderMan2Xbox/`, so it contains:

   ```text
   SpiderMan2Xbox/
     default.xbe
     data/
       ... game files ...
   ```

   You may enter another ordinary storage path in the launcher. Disc images are
   intentionally not imported by the launcher: extraction avoids needing a second
   large temporary copy and makes the missing-data checks unambiguous.
3. Select performance and touch defaults, confirm the green **Game files ready**
   check, then tap **PLAY**.

The native settings, saves, logs, pipeline cache and shader cache are stored in
Android's app-specific external files directory (`Android/data/.../files`).

## Controls and performance notes

- The overlay maps one-for-one to the original Xbox controller: two sticks,
  D-pad, A/B/X/Y, Black/White, LT/RT, Start and Back. A Bluetooth/USB controller
  always works alongside the touch state, with its stronger analogue input taking precedence.
- Use **Balanced** first. Increase render scale gradually after a stable play
  session. 90/120 FPS only help on displays and devices that can sustain them;
  unlimited FPS may increase heat and power use.
- When changing a graphics setting, restart the game from the launcher for a
  clean shader/pipeline cache and predictable thermal comparison.
