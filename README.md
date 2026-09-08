# BabyCam Overlay

A native Android app that shows a live RTSP camera feed as a floating window
always on top of whatever else is playing on an Android TV box — a baby
monitor overlay for the TV.

## How it works

- **MainActivity** is the control panel: enter the camera's RTSP URL and
  credentials, pick where the overlay sits and how big/opaque it is, grant
  the required permissions, and start/stop the overlay.
- **OverlayService** is a foreground service that draws the camera feed in a
  system-level window (`TYPE_APPLICATION_OVERLAY`) on top of every other app,
  using AndroidX Media3/ExoPlayer's RTSP support to decode the stream. It
  auto-reconnects with backoff if the camera drops off Wi-Fi.
- **BootReceiver** restarts the overlay after the box reboots, if you enabled
  "Start overlay automatically on boot".

### Design decision: the overlay is remote-only, not touch-drag

Most Android TV boxes are driven by a D-pad remote with no pointer. A
floating window that intercepted touch/focus could break navigation in
whatever app is running underneath it (Netflix, the launcher, etc.), so the
overlay window is **always** `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE` — it's
a pure passthrough video surface. All positioning (5 screen corners/center),
sizing (4 presets), opacity (3 levels) and mute are instead controlled from
the app's own screen, which you reach by pressing Home/back and reopening
the app from the launcher — updates apply live without restarting the stream.

If you specifically have a box with a mouse-like remote or USB mouse and
want drag-to-reposition later, that's a reasonable follow-up but isn't
implemented here by design (see `OverlayService.kt` for where to add it).

## Requirements

- Android Studio (already installed) or just the command line — this repo
  has a Gradle wrapper, so `./gradlew` is enough, no separate Gradle install
  needed.
- A camera that exposes an **RTSP** stream (most dedicated IP cameras, NVRs,
  and many baby monitors do — check the manufacturer's app/manual for the
  RTSP URL, typically `rtsp://<ip>:554/...`).
- A TV box running Android 5.0 (API 21) or newer, with a way to sideload an
  APK (see below) since this isn't published to the Play Store.

## Building

```bash
cd babycam-tv-overlay
./gradlew assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`. This was
already built and verified once during setup — `assembleDebug` succeeds
cleanly with AGP 8.5.2 / Gradle 8.13 / JDK 17.

For a release build to actually keep on a device long-term, sign it (Android
Studio → Build → Generate Signed Bundle/APK) rather than shipping the debug
build.

## Installing on a TV box

Most generic/Android TV boxes don't have the Play Store set up for
sideloading, so use `adb`:

```bash
adb connect <box-ip>:5555          # if connecting over network
adb install -r app-debug.apk
```

Or copy the APK to a USB drive / use a file manager app on the box and
install it locally (you'll need to allow "install unknown apps" for that
file manager once).

## First-run setup on the box

1. Open **BabyCam Overlay** from the launcher (it shows up as a normal app
   and, on certified Android TV/Google TV devices, also in the TV home
   screen's leanback row).
2. Under **Permissions**, tap **Grant overlay permission** — this opens the
   system "Display over other apps" screen; enable it for BabyCam Overlay
   and press Back to return.
3. On Android 13+ boxes, also tap **Grant notification permission** (the
   foreground service needs an ongoing notification to stay alive in the
   background reliably).
4. Under **Camera stream**, enter the RTSP URL (and username/password if
   your camera needs them — credentials get URL-encoded automatically), then
   tap **Test connection** to confirm the box can actually reach the camera
   before committing to it.
5. Pick a position/size/opacity you like, leave audio muted unless you want
   to hear the room, and optionally enable **Start overlay automatically on
   boot**.
6. Tap **Save & start overlay**. Press Home — the camera feed should now
   float on top of the TV home screen and stay there as you switch apps.

## Troubleshooting

- **"Display over other apps" doesn't seem to do anything / no system
  screen appears.** A few very generic/off-brand boxes ship a stripped-down
  Settings app that doesn't expose this screen properly. Try Settings → Apps
  → BabyCam Overlay → Permissions directly on the box, or check if the
  vendor has a "floating window"/"pop-up window" toggle elsewhere in
  Settings — some skinned boxes (certain Amlogic/Allwinner firmwares) rename
  it.
- **Stream connects in Test but the overlay stays black / keeps
  reconnecting.** Check that the box and camera are on the same subnet/VLAN
  (RTSP typically won't traverse routed networks without extra config), and
  that nothing else (an NVR, another viewer) is capping the camera's
  concurrent RTSP session count.
- **Overlay disappears after a while / box seems to kill the app.** Some TV
  boxes apply aggressive battery/RAM management to background apps despite
  the foreground service + notification. Look for a vendor-specific
  "autostart"/"protected apps"/battery optimization allowlist and add
  BabyCam Overlay to it.
- **No audio even with mute off.** Confirm the camera stream actually
  contains an audio track (many cheap cameras are video-only over RTSP, or
  need a specific codec Media3 doesn't support — check Logcat filtered on
  `ExoPlayer`/ `RtspMediaSource` for codec errors).

## Project layout

```
app/src/main/java/com/babycam/overlay/
  MainActivity.kt        control panel UI + permission flow + connection test
  OverlayService.kt       foreground service, WindowManager overlay, playback + reconnect
  BootReceiver.kt          restarts the overlay after reboot if auto-start is on
  SettingsStore.kt         SharedPreferences-backed settings (URL, creds, layout, flags)
  RtspPlayerFactory.kt      shared ExoPlayer/RTSP MediaSource construction
app/src/main/res/
  layout/activity_main.xml        control panel layout
  layout/overlay_camera.xml       the floating window's content
  values/                          strings, colors, TV-friendly dark theme/styles
  drawable/, mipmap*/              launcher icon, TV banner, notification icon, overlay chrome
```

## Possible follow-ups

- Touch-drag repositioning for boxes with a mouse-capable remote.
- Multi-camera support (cycle/grid of more than one feed).
- ONVIF/mDNS camera discovery instead of typing the RTSP URL by hand.
- A "snooze" timer that hides the overlay for N minutes.
