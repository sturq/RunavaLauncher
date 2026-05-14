<h1 align="center">RuneLiteDroid</h1>

<p align="center"><em>The desktop RuneLite client, running on Android.</em></p>

[![Android CI](https://github.com/sturq/runelitedroid/actions/workflows/android.yml/badge.svg)](https://github.com/sturq/runelitedroid/actions/workflows/android.yml)

RuneLiteDroid is a single-APK port of the desktop [RuneLite](https://runelite.net/) client — the open-source third-party Old School RuneScape client — to Android. It launches the upstream RuneLite JAR inside a JRE 17 packaged with the app and renders it through a software AWT pipeline (Caciocavallo TTA), so you get the same RuneLite UI and the same plugin ecosystem you have on desktop, on your phone, without proot, without X11, without a separate runtime install.

Everything is bundled. Install one APK, tap the icon, log into your Jagex account, play.

## Status

**Playable, with caveats.** What works:

* Launches RuneLite from a fresh install with no extra setup
* Connects to OSRS — login, world hop, chat, walking, combat, etc.
* The full RuneLite plugin sidebar — every plugin desktop has, same versions
* Touch controls (see below) for camera, taps, right-click, zoom
* Fullscreen, immersive layout, system gestures excluded from the right-edge UI strip

Known limitations:

* **Software-rendered.** RuneLite's GPU plugin (`librlawt.so`) needs glibc + X11 + GLX symbols that Android doesn't have. The CPU renderer is what runs. Frame rate is fine on a modern phone, but you won't hit desktop GPU-plugin numbers.
* **No audio.** No `javax.sound.sampled` provider is bundled. Game and plugin sounds are silent.
* **Drag-from-background can be slow on the first frame** when Android has trimmed the process. The foreground service should keep this rare.

## Touch controls

| Gesture | Action |
| --- | --- |
| 1-finger tap | Left click |
| 1-finger long-press (≥ 200ms, no movement) | Right click (opens OSRS context menu) |
| 1-finger drag in game world (left ~75% of screen) | Camera rotate (arrow keys) |
| 1-finger drag on RuneLite UI (right sidebar) | Left button held — for inventory drag, minimap drag, etc. |
| 2-finger drag | Camera rotate (arrow keys) |
| 2-finger pinch | Zoom in / out (mouse wheel) |
| ☰ menu (top-left) | Open the drawer: keyboard, copy/paste, virtual mouse toggle, log viewer, force-close |

## Building

```bash
./gradlew :app_pojavlauncher:assembleMainDebug
# APK at: app_pojavlauncher/build/outputs/apk/main/debug/app_pojavlauncher-main-debug.apk
```

GitHub Actions builds a debug APK on every push and on a daily 04:00 UTC cron, available as a workflow artifact. No release builds are signed yet.

## Architecture

The launcher is a fork of [PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher) / Amethyst-Android, gutted down to the JVM-hosting path. The PojavLauncher pieces that remain:

* The Termux-built OpenJDK 17 (`aarch64`, embedded as a JRE tar.xz in `assets/components/jre-17/`)
* Caciocavallo TTA as the AWT toolkit (no X11)
* The `JREUtils` JNI glue that translates Cacio's AWT frame output into a software-rendered Bitmap on a `SurfaceView`

What's been added or rewritten for RuneLite:

* `RuneLiteLauncherActivity` — downloads the upstream RuneLite JAR on first launch, deduplicates entries, caches it, installs JRE 17, fires the game intent
* `RuneLiteGameActivity` — fullscreen game-style host, touch → AWT mouse/key mapping, drawer-based menu, foreground-service keepalive
* `runelite_window_agent/` — a Java agent loaded into the JVM via `-javaagent`. Force-maximizes the RuneLite JFrame to fill the Cacio screen, and runs a file-based IPC poller so the Android side can post mouse-wheel and right-click events that Cacio's input bridge doesn't handle directly.
* `app_pojavlauncher/src/main/jni/lib{GL,c,dl,pthread,m,rt,ld}shim/` — empty `.so` files built with the appropriate glibc SONAMEs (`libGL.so.1`, `libc.so.6`, etc.) so RuneLite's GPU-plugin natives don't fail their dynamic-linker NEEDED checks. Symbols come from bionic libc; the shims are just SONAME placeholders.

The `:runelitegame` Android process is separate from `:launcher` and is kept alive by a foreground service so Android doesn't reap the JVM mid-session.

## Caveats / unsupported

* **No GPU plugin.** Enabling it in RuneLite settings will fail to load `librlawt.so`.
* **Jagex accounts only.** RuneLite has dropped username/password login.

## License

This project inherits the GPL-3.0 license from upstream PojavLauncher. The Java agent and Android-specific glue under `runelite_window_agent/` and `app_pojavlauncher/src/main/jni/lib*shim/` are also GPL-3.0. RuneLite itself is BSD-2-Clause and is downloaded at runtime, not bundled.

## Credits

* [RuneLite](https://github.com/runelite/runelite) — the actual client
* [PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher) — the JVM-on-Android scaffolding
* [Amethyst-Android](https://github.com/AngelAuraMC/Amethyst-Android) — the PojavLauncher fork this codebase started from
* [Caciocavallo](https://github.com/CaciocavalloSilano/caciocavallo) — pure-Java AWT toolkit, no X11 required

Not affiliated with Jagex or the RuneLite project. Use of RuneLite is subject to Jagex's third-party client guidelines.
