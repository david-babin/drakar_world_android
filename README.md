# drakar · Android live wallpaper

The [drakar.tech](https://drakar.tech) world as a live wallpaper: the shared globe drawn natively (OpenGL ES 3),
connected to the same server as the page, and — if you allow it — computing work units with the server's own
engine. The protocol is the contract: [`drakar_tech/docs/protocol.md`](https://github.com/david-babin/drakar_tech/blob/main/docs/protocol.md).

## Install

Every push to `main` builds the APK and publishes it as the **latest** release
(`Releases → latest → drakar.apk`). Open it on the phone, allow the install, then in the app tap
**Set as wallpaper**. Every build is signed with the same sideload key (`signing/`), so a new build installs over
the previous one; a store release would need a private key kept out of the repository.

## What it does

- **Watches.** While a wallpaper surface is visible (home screen, lock screen, either screen of a foldable), it
  connects, receives the world (`hello`, then deltas) and draws it: terrain and relief, vector land, coasts and
  contours, routes, settlements, groups and cities, the regions being computed, the brand and the year. The
  camera glides to the region being computed and stops there. Hidden for 10 s: disconnected, nothing runs.
- **Draws only when needed.** A frame is drawn only when the camera moves (≤ 12 fps) or the world changed
  (≤ 1 per second); otherwise the GPU is idle and the last frame stays on screen.
- **Computes, a little.** While visible, one unit a minute (every ten seconds on the charger). On the charger and
  on Wi-Fi, screen off, a background job computes for up to eight minutes every quarter of an hour. Both can be
  turned off in the app.
- **Never reimplements the rules.** Units are computed by the server's `engine.js`, downloaded at the version the
  server announces and run in Android's JavaScript sandbox (the same V8 as Chrome), so results match the server's
  verification bit for bit. When the server moves to a new engine, the next result is refused with the new
  version, which the app loads by itself.

## Layout

```
app/src/main/java/tech/drakar/wallpaper/
  Config.kt            timings, rendering caps
  Hub.kt               shared state: Earth data, world, link, compute loop
  DrakarWallpaperService.kt   one engine per surface → GlThread + GlobeRenderer
  Link.kt              WebSocket (OkHttp) with recovery: 45 s silence = dead, backoff 1 → 15 s
  WorldModel.kt        snapshot + deltas + live witnesses (thread-safe)
  Assets.kt            Earth data (ETag, weekly) and engine.js (per version) cached in app storage
  EarthData.kt         varint parsers of land.bin / coasts.bin / contours.bin
  EngineHost.kt        engine.js in androidx.javascriptengine
  Contributor.kt       lease → stepped engine (animated, progress relayed) → result
  ChargingWorker.kt    WorkManager job: charger + Wi-Fi
  SettingsActivity.kt  the only screen
  render/              EGL thread, shaders (ported from the page), globe renderer, settlement areas
```

## Build

`./gradlew assembleRelease` (JDK 17, Android SDK 36). CI (`.github/workflows/build.yml`):

- **apk** — unit tests (world model against fixtures produced by the server's library, Earth data parsers), the
  APK, and the `latest` release on `main`.
- **device** — on an emulator: `EngineDeviceTest` runs `engine.js` in the JavaScript sandbox on units produced by
  the server's library and checks the hashes; `RenderDeviceTest` connects to drakar.tech, renders a live frame
  offscreen with the wallpaper's renderer and the frame is pushed to the `ci-screens` branch.

Test fixtures come from `drakar_tech` (`src/lib`, `public/engine.js`): regenerate them when the rules change.
