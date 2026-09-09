# BabyCam Overlay

A native Android app that shows one or more live RTSP camera feeds as a
floating window always on top of whatever else is playing on an Android TV
box — a baby monitor overlay for the TV.

## How it works

- **MainActivity** is the control panel: manage a list of saved cameras (add,
  edit, delete, enable/disable, each with an ONVIF discovery scan and a
  connection test), choose single-feed vs. grid layout, pick where the
  overlay sits and how big/opaque it is, grant the required permissions, and
  start/stop the overlay.
- **OverlayService** is a foreground service that draws the camera feed(s) in
  a system-level window (`TYPE_APPLICATION_OVERLAY`) on top of every other
  app, using AndroidX Media3/ExoPlayer's RTSP support to decode each stream.
  Every camera slot auto-reconnects independently with backoff if it drops
  off Wi-Fi.
- **BootReceiver** restarts the overlay after the box reboots, if you enabled
  "Start overlay automatically on boot".
- **OnvifDiscovery** is a small hand-rolled WS-Discovery + ONVIF SOAP client
  (no extra library) used from the "Add camera" dialog to find cameras on the
  LAN and, best-effort, resolve their actual RTSP stream URL.
- **MqttDoorbellClient** subscribes (only) to an MQTT topic so a Home
  Assistant automation can trigger a temporary doorbell-camera overlay.

### Multiple cameras: single-feed rotation or a grid

Every saved camera has an **enabled** flag; the enabled ones participate in
the overlay according to the **Multiple cameras** setting:

- **Single feed (rotate through cameras)** — one camera at a time, advancing
  to the next enabled one every N seconds (15/30/60s). With only one enabled
  camera it just stays on it, no rotation timer.
- **Grid** — up to 4 enabled cameras shown at once, each in its own tile
  (1 → full size, 2 → side by side, 3 → two-plus-one, 4 → 2×2). Each tile
  reconnects independently, so one camera dropping off doesn't affect the
  others.

Grid mode runs one ExoPlayer/RTSP decode per tile, so on very low-end boxes
prefer single-feed rotation if 3-4 simultaneous decodes turns out to be more
than the hardware can handle smoothly.

### Design decision: the overlay is remote-only, not touch-drag

Most Android TV boxes are driven by a D-pad remote with no pointer. A
floating window that intercepted touch/focus could break navigation in
whatever app is running underneath it (Netflix, the launcher, etc.), so the
overlay window is **always** `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE` — it's
a pure passthrough video surface. All positioning (5 screen corners/center),
sizing (4 presets), opacity (3 levels), mute, and the camera list itself are
instead controlled from the app's own screen, which you reach by pressing
Home/back and reopening the app from the launcher — cosmetic changes
(position/size/opacity/mute) apply live without restarting the stream;
camera-list/layout-mode/rotation changes restart playback.

If you specifically have a box with a mouse-like remote or USB mouse and
want drag-to-reposition later, that's a reasonable follow-up but isn't
implemented here by design (see `OverlayService.kt` for where to add it).

### ONVIF discovery is best-effort

The "Discover cameras (ONVIF)" button in the add/edit dialog sends a
WS-Discovery UDP probe and lists cameras that respond — this part is
standardized and fairly reliable. Tapping a result then tries to
automatically resolve the actual RTSP URL via ONVIF's
GetCapabilities → GetProfiles → GetStreamUri SOAP calls, using whatever
username/password you've typed in. Vendor ONVIF implementations are
notoriously inconsistent, so this step can legitimately fail on some
cameras — when it does, the dialog fills in the camera's IP with a common
default RTSP port so you can finish the path by hand instead.

### Doorbell trigger (Home Assistant via MQTT)

Under **Doorbell trigger**, enable it, point it at your MQTT broker (host,
port, optional username/password/TLS), and pick a topic — the app only ever
*subscribes* to that topic, it never publishes anything and never runs a
broker of its own. Have a Home Assistant automation publish to that same
topic when the doorbell fires (the message content itself is ignored — any
message on the topic counts as a trigger):

```yaml
automation:
  - alias: Doorbell -> BabyCam overlay
    trigger:
      - platform: state
        entity_id: binary_sensor.front_door_doorbell
        to: "on"
    action:
      - service: mqtt.publish
        data:
          topic: babycam/doorbell
          payload: "ring"
```

Pick which saved camera(s) should show — checkboxes, not a single choice,
and they don't need to be cameras already enabled in the regular
rotation/grid (you can keep a doorbell camera out of normal view and only
have it pop up on a ring) — and how long the trigger stays up (10-60s):

- **Pick one** and it's shown statically for the whole duration.
- **Pick several** and the popup rotates through them, splitting the
  duration evenly (e.g. two cameras and a 20s duration means 10s each) —
  useful if you've got a front and back door and want either ring to cycle
  through both.

**How it shows up** is a setting, since which choice actually works well
depends entirely on the TV box's own hardware:

- **Take over the main overlay** (default) — swaps the existing overlay's
  content to the doorbell camera(s) rather than opening a second window:
  in **single feed** mode it swaps the one slot, then resumes whatever was
  playing (and rotating) before; in **grid** mode it's added as an extra
  tile alongside what's already showing (up to the 4-tile cap), or takes
  over the last tile if the grid is already full — either way, that
  reverts afterward. Only ever needs **one** concurrent video decode, so
  it's the safe choice on weaker/older boxes.
- **Separate window** — opens a genuinely independent second floating
  window (its own position/size, set below) that shows the doorbell
  camera(s) *alongside* the main overlay without touching it at all.
  Needs the box to decode and render **two** RTSP streams at once, which
  not all hardware can actually do.

A re-trigger while one is already active just resets the countdown rather
than stacking. Use **Test broker connection** to confirm the app can reach
your broker before saving.

**Why takeover is the default:** confirmed on real (weak/older) TV box
hardware during development — the separate-window mode's video decoder
succeeded, but the second window's renderer stalled badly enough (an
800ms+ single frame, logged as `Davey!` under `OpenGLRenderer`) that it
never produced a visible picture, while the main overlay and the takeover
mode both kept working fine on the same device. If your box turns out to
handle two concurrent video surfaces without trouble, switching to
**Separate window** is just the one setting — no rebuild needed.

**Doorbell listening works independently of the main camera view.** You
don't need any main camera enabled at all — if the doorbell trigger is
fully set up (enabled, a broker host, at least one camera picked),
**Save & start overlay** will start the service just for that, with no
main overlay window shown at all until (if ever) you enable a main
camera. The reverse holds too: disabling every main camera doesn't kill
MQTT listening as long as the doorbell trigger stays configured. The one
exception is **Take over the main overlay** mode specifically — by
definition there's nothing to "take over" if no main camera view is
running, so a trigger silently does nothing in that combination; use
**Separate window** mode instead for a doorbell-only setup, since that
opens its own window regardless of whether the main overlay exists.

If the doorbell trigger *isn't* fully configured and no main camera is
enabled either, **Save & start overlay** won't do anything — and if
neither was ever configured, an MQTT message that arrives while the
overlay was never started (or was stopped) is simply never seen: there's
no persistent queue, nothing gets delivered late once you do start it.

## Requirements

- Android Studio (already installed) or just the command line — this repo
  has a Gradle wrapper, so `./gradlew` is enough, no separate Gradle install
  needed.
- A camera that exposes an **RTSP** stream (most dedicated IP cameras, NVRs,
  and many baby monitors do — check the manufacturer's app/manual for the
  RTSP URL, typically `rtsp://<ip>:554/...`, or use the in-app ONVIF
  discovery to try to find it automatically).
- A TV box running Android 5.0 (API 21) or newer, with a way to sideload an
  APK (see below) since this isn't published to the Play Store.

## Building

```bash
cd babycam-tv-overlay
./gradlew assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`. This project
builds clean with AGP 8.5.2 / Gradle 8.13 / JDK 17, and `lintDebug` passes
with no errors (only informational warnings — dependency-update notices,
accessibility/autofill hints, etc.).

## Release signing

Play Store submissions need a signed Android App Bundle, not the debug APK.
Signing credentials live in a git-ignored `keystore.properties` (pointing at
a git-ignored `.jks` keystore file) at the project root — neither is
committed, since anyone with them could publish updates to the app under
your identity.

If you don't already have these two files (a fresh clone won't), generate
them once:

```bash
keytool -genkeypair -alias babycam-overlay -keyalg RSA -keysize 4096 \
  -validity 10000 -keystore babycam-overlay-release.jks

cat > keystore.properties <<EOF
storeFile=../babycam-overlay-release.jks
storePassword=<the password you just set>
keyAlias=babycam-overlay
keyPassword=<the same password - PKCS12 keystores require store and key passwords to match>
EOF
```

Then build the signed bundle:

```bash
./gradlew bundleRelease
```

The signed `.aab` lands at `app/build/outputs/bundle/release/app-release.aab`
— that's what gets uploaded to Play Console. `./gradlew assembleRelease`
also produces a signed `.apk` at `app/build/outputs/apk/release/` if you want
to sideload/test the release build directly.

**Back up `babycam-overlay-release.jks` and the passwords in
`keystore.properties` somewhere safe outside git** (a password manager, an
encrypted drive). If you lose them, there is no way to publish an update to
an app already live under this signature — Google can't reset this for you.

## Publishing to the Play Store

`STORE_LISTING.md` has the listing copy (short/full description, content
rating and data-safety questionnaire answers) ready to paste into Play
Console, and `store/` has the icon, feature graphic, TV banner, and
screenshots. `docs/privacy-policy.html` is hosted via GitHub Pages at
https://si27645.github.io/babycam-tv-overlay/privacy-policy.html — edit that
file and push to update it, the URL stays the same.

Publishing itself has to happen from your own Play Console account — Google
requires the account holder to personally accept the developer agreement and
complete the app's policy declarations, so there's no way to do this step
from outside the console.

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
4. Under **Cameras**, tap **Add camera**. Either tap **Discover cameras
   (ONVIF)** and pick one from the scan, or type the RTSP URL (and
   username/password if needed — credentials get URL-encoded automatically).
   Tap **Test connection** to confirm the box can actually reach the camera,
   then **Save**. Repeat for additional cameras. The checkbox next to each
   camera in the list controls whether it shows in the normal overlay —
   the first camera you add defaults to checked, every one after that
   defaults to unchecked, so adding more cameras never silently starts
   rotating/gridding all of them; check the ones you actually want shown
   day to day (a camera you leave unchecked here can still be picked
   further down as a doorbell-trigger camera without ever appearing
   normally).
5. If you checked more than one camera, pick **Single feed** or **Grid**
   under **Multiple cameras**, and a rotation interval if using single-feed
   with 2+ cameras checked.
6. Pick a position/size/opacity you like, leave audio muted unless you want
   to hear the room, and optionally enable **Start overlay automatically on
   boot**.
7. Tap **Save & start overlay**. Press Home — the camera feed(s) should now
   float on top of the TV home screen and stay there as you switch apps.

## Troubleshooting

- **Tapping "Grant overlay permission" used to crash / "Display over other
  apps" doesn't seem to do anything.** Confirmed on a stock Xiaomi Mi Box:
  some Android TV builds ship no settings screen at all for this permission
  (`pm resolve-activity` on the device returns nothing for
  `ACTION_MANAGE_OVERLAY_PERMISSION`). The app now falls back to the generic
  app-details screen instead of crashing, but that screen may *also* have no
  toggle for it on some boxes. If neither works, grant it directly over adb:
  `adb shell appops set com.babycam.overlay SYSTEM_ALERT_WINDOW allow` — this
  persists across app restarts (not across uninstall/reinstall) and needs no
  UI at all.
- **The overlay shows fine over the home screen/launcher but disappears
  behind another app's video (a live-TV/IPTV app, in particular).**
  Confirmed root cause on real hardware: many TV chipsets composite a
  `SurfaceView`'s video on a dedicated hardware overlay plane that ignores
  normal Android window z-order, so another app's own video can visually
  win even though our window is logically on top. Fixed by rendering
  through a `TextureView` instead (see `overlay_player_cell.xml`) — if this
  regresses on some device, that's the first place to look.
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
- **ONVIF discovery finds nothing.** Many cameras don't implement ONVIF at
  all (check the manufacturer's spec sheet), or advertise on a different
  subnet/VLAN than the TV box. Type the RTSP URL manually in that case.
- **ONVIF discovery finds the camera but "Discover" can't fill in the
  stream URL.** Expected on some vendors' partial/buggy ONVIF stacks — the
  dialog fills in the IP with the default RTSP port; check the camera's
  manual/web UI for its actual stream path and finish the URL by hand.
- **Grid mode is choppy/stutters on some tiles.** Decoding 3-4 RTSP streams
  at once is real CPU/GPU load for a cheap box's hardware decoder. Switch to
  **Single feed (rotate through cameras)** instead.
- **Doorbell trigger never fires.** Confirm **Test broker connection**
  succeeds first. If it does but triggering still doesn't work, check the
  topic matches exactly on both sides (case-sensitive), and that the HA
  automation is actually publishing (Developer Tools → MQTT → Listen to a
  topic, in Home Assistant, is the fastest way to confirm the message is
  really being sent — or `mosquitto_sub -h <broker> -t <topic> -v` from a
  terminal, paired with `mosquitto_pub` on the same topic to fire a manual
  test).
- **A specific camera "shows then keeps reconnecting" (badge flickers
  repeatedly) but others are fine.** Confirmed root cause on real hardware:
  the camera's stream resolution/bitrate is too much for that box's
  hardware video decoder (Logcat will show a `MediaCodecVideoDecoderException`
  under `ExoPlayerImplInternal`, not an RTSP/network error). This is a
  per-camera stream setting, not an app bug — point that camera's URL at
  its lower-resolution **sub** stream instead of **main** (for Reolink
  cameras, swap `h264Preview_01_main` for `h264Preview_01_sub` in the URL)
  and it should connect cleanly.

## Project layout

```
app/src/main/java/com/babycam/overlay/
  MainActivity.kt        control panel UI: camera list CRUD, appearance, permissions
  OverlayService.kt       foreground service, WindowManager overlay, N camera slots + reconnect/rotation, doorbell trigger
  OnvifDiscovery.kt        WS-Discovery probe + best-effort ONVIF SOAP client (no extra library)
  MqttDoorbellClient.kt    thin Eclipse Paho wrapper - subscribes only, no broker/publishing
  CameraProfile.kt         one saved camera (name/url/credentials/enabled) + JSON (de)serialization
  BootReceiver.kt          restarts the overlay after reboot if auto-start is on
  SettingsStore.kt         SharedPreferences-backed settings (camera list, layout, flags, MQTT/doorbell)
  RtspPlayerFactory.kt     shared ExoPlayer/RTSP MediaSource construction
  Util.kt                  small shared helpers (dp conversion, pre-23-safe overlay-permission check)
app/src/main/res/
  layout/activity_main.xml         control panel layout
  layout/dialog_camera_editor.xml  add/edit camera dialog (fields, discovery results, test)
  layout/overlay_camera.xml        the floating window's root container (populated at runtime)
  values/                           strings, colors, TV-friendly dark theme/styles
  drawable/, mipmap*/               launcher icon, TV banner, notification icon, overlay chrome
```

## Possible follow-ups

- Touch-drag repositioning for boxes with a mouse-capable remote.
- Per-camera layout overrides (e.g. pin one camera to a corner regardless of mode).
- A "snooze" timer that hides the overlay for N minutes.
- Bump Media3 from 1.4.1 to the latest 1.x (lint flags 1.11.0 as available);
  held back at 1.4.1 here only because it's the version this was built and
  verified against.
