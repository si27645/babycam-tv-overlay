# Play Store listing content

Reference material for filling in the Play Console listing. Nothing here needs
to be committed as app code — it's just the copy/answers ready to paste in.

## App name

BabyCam Overlay

## Short description (max 80 characters)

See your baby camera on top of any app on the TV — Netflix, YouTube & more.

(77 characters)

## Full description (max 4000 characters)

BabyCam Overlay puts your home IP camera's live feed in a small floating
window that stays on top of everything else on your Android TV — the home
screen, Netflix, YouTube, a game, anything. Glance at the crib without
leaving what you're watching.

**How it works**
Point the app at your camera's RTSP stream (most IP cameras and NVRs expose
one — check your camera's app or manual), and BabyCam Overlay draws it in a
small always-on-top window using Android's standard overlay permission. No
cloud account, no subscription, no third-party servers — the video goes
straight from your camera to your TV box over your own local network.

**Features**
• Floating overlay that stays on top of every other app
• Works with any RTSP camera (most dedicated IP cameras and NVRs)
• Optional ONVIF network scan to help find your camera automatically
• Multiple cameras: rotate through them one at a time, or view up to 4 at
  once in a grid
• Choose where the overlay sits, how big it is, and how transparent it is
• Auto-reconnect if the camera drops off Wi-Fi
• Auto-start on boot, so it comes back after a power cut
• Built for TV remotes: everything is controlled from the app's own screen,
  not by touching the floating window (most TV boxes have no touchscreen)

**Before you install**
• You need a camera that exposes an RTSP stream. This app does not work
  with cloud-only cameras that have no local RTSP/ONVIF access.
• The camera and your TV box need to be reachable on the same local network.
• This app requires the "display over other apps" permission to do its one
  job: showing the camera feed on top of everything else.

**Privacy**
BabyCam Overlay collects no data, shows no ads, and has no analytics. Your
camera's stream, and the URL/credentials you enter for it, never leave your
device except to talk directly to your camera on your own network. See the
full privacy policy linked on this page.

## Privacy policy URL

https://si27645.github.io/babycam-tv-overlay/privacy-policy.html

Hosted from `docs/privacy-policy.html` in this repo via GitHub Pages —
edit that file and push to update it; the URL stays the same.

## Store graphics

Ready to upload, in `store/`:
- `icon-512.png` — hi-res app icon (512×512)
- `feature-graphic-1024x500.png` — Play Store feature graphic
- `tv-banner-1280x720.png` — Android TV banner
- `screenshots/` — 3 screenshots (1920×1080): the control panel, the
  permissions screen, and a concept illustration of the overlay in use
  (the concept shot is an illustration, not a real camera feed — deliberately,
  to avoid publishing anyone's actual home footage in a public store listing)

## Category

Video Players & Editors, or Tools

## Content rating questionnaire — suggested answers

- Violence: None
- Sexual content: None
- Profanity: None
- Controlled substances: None
- Gambling: None
- User-generated content / user communication: None (no chat, sharing, or
  social features)
- Shares location: No
- Digital purchases: No
- Ads: No

Expected result: rating suitable for all ages (e.g. PEGI 3 / Everyone),
since the app has no objectionable content of any kind — it's a utility
that displays a video stream the user themselves points it at.

## Data safety section — suggested answers

- Does your app collect or share any of the required user data types? **No**
  (the app makes network requests directly to a camera the user configures,
  entirely on the local network — it does not transmit anything to the
  developer or any third party, and stores camera URLs/credentials only in
  the app's local, on-device storage).
- Is all user data encrypted in transit? N/A (no data leaves the device to
  any external service)
- Do you provide a way for users to request data deletion? N/A (nothing is
  collected)
- Data collected: none
- Data shared: none

## Target audience

General audience / not primarily directed at children. This is a parenting
utility used *by* an adult, not an app children interact with directly —
answer the "primarily child-directed" question **No**.

## Permissions declaration (Play Console will ask about sensitive permissions)

- **Display over other apps (SYSTEM_ALERT_WINDOW)**: core functionality —
  the app's entire purpose is showing a camera feed on top of other apps.
  Explain this plainly when Play Console's permissions declaration form
  asks for a justification.
- **Camera/network access**: the app does not use the device's own camera;
  it plays a video stream from a network camera the user configures. Make
  this distinction clear if asked, since Play's review sometimes flags
  camera-adjacent permissions for extra scrutiny.
