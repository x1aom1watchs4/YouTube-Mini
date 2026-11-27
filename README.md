# YouTube Mini 0.1

A Xiaomi Watch S4 (Vela) + Android companion prototype.

## Current implementation

- Vela watch UI with a connection indicator, Shorts-style metadata viewer, previous/next navigation, and swipe navigation.
- Watch-to-phone communication through Xiaomi Vela `@system.interconnect` and the Android XMS Wearable `NodeApi` / `MessageApi`.
- Android companion search using YouTube Data API v3.
- Video metadata, thumbnails, public comment loading, like/comment statistics, and opening the selected YouTube Short on the phone.
- The API key is saved in the app's local Android SharedPreferences so it survives app restarts. It is not committed to this repository; SharedPreferences should not be treated as encrypted secret storage.

## Platform communication

The transport is Xiaomi XMS Wearable / Vela Interconnect, not a hand-written BLE socket. The Vela runtime manages the paired-app connection. The watch app registers `onopen`, `onclose`, `onerror`, and `onmessage` handlers and uses `diagnosis()` for a connection check.

The official Xiaomi sample is:

https://github.com/open-vela/packages_apps/tree/dev/wearable/interconnect_image_demo

The Android side uses the XMS Wearable AAR from that demo. See [phone setup](phone/README.md).

The Android `applicationId` and Vela manifest `package` must match exactly. Both packages must be signed with the same certificate. Never commit private keys, certificates, signing passwords, or API keys.

## Video playback status

**Actual YouTube video playback is not implemented on the watch.** The watch displays a thumbnail and metadata. No video playback or frame-animation test is presented as video. The `OPEN` action asks the Android companion to open the selected YouTube Short in the phone's YouTube app or browser.

The YouTube Data API provides search metadata and statistics, not the underlying video stream. This project does not extract, proxy, or relay YouTube media streams.

The reviewed Vela Quick App component catalogue does not provide a documented video-player component, and `@system.audio` is an audio API. The Vela file/network APIs can store an MP4 but do not decode or render it. OpenVela does contain a native C Media Framework with FFmpeg-based video processing and a video-output path; however, the reviewed Feature Framework configuration does not expose a `system.video` Quick App binding. Using that native framework would require firmware/native-app integration and a display output path, not just an RPK update. The research and source links are recorded in [the video playback investigation](docs/video-playback-research.ru.md).

Public comments are read-only through the current API-key flow. Posting comments or registering a like would require an appropriately authorized YouTube OAuth flow and is not implemented.

## Build status

GitHub Actions run [46042147802](https://github.com/x1aom1watchs4/YouTube-Mini/actions/runs/46042147802) completed successfully:
- Android companion APK build: success.
- Vela watch RPK build: success.

The workflow published debug and release artifacts for both apps. Successful packaging is not the same as a physical-device installation or playback test; those still need to be performed on the paired phone and Watch S4.

## Video playback conclusion

For standard Watch S4 M2425W1, no supported public video-playback API for Vela Quick Apps was found. The native OpenVela Media Framework is a possible firmware-level research route, but it is not callable from the current RPK without a vendor/system bridge. See [the full investigation](docs/video-playback-research.ru.md).

## Next milestones

1. Install the latest APK and RPK on the paired phone and Watch S4; verify search, selection, comments, and phone handoff.
2. Keep watch-side video marked as unsupported unless Xiaomi exposes a supported Vela video API or a working native bridge is built and tested on the physical M2425W1.
