# YouTube Mini 0.1

A Xiaomi Watch S4 (Vela) + Android companion prototype.

## Current implementation

- Vela watch UI with a connection indicator, Shorts-style metadata viewer, previous/next navigation, and swipe navigation.
- A clearly labelled frame-animation test using four local PNG images. This demonstrates image animation only; it is not video playback.
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

**Actual YouTube video playback is not implemented on the watch.** The watch currently displays a thumbnail and metadata; the separate frame test animates local PNG images. The `OPEN` action asks the Android companion to open the selected YouTube Short in the phone's YouTube app or browser.

The YouTube Data API provides search metadata and statistics, not the underlying video stream. This project does not extract, proxy, or relay YouTube media streams.

The official Vela JS interface catalogue documents an audio feature, but the currently reviewed Vela documentation does not establish a supported Quick App video-player component for Watch S4. A video component documented for other Quick App environments is not evidence that it is available on Vela or on this watch. Therefore direct playback of an authorized local video on Watch S4 remains unverified and must be tested on-device before being advertised as supported.

Public comments are read-only through the current API-key flow. Posting comments or registering a like would require an appropriately authorized YouTube OAuth flow and is not implemented.

## Build status

GitHub Actions run [46042147802](https://github.com/x1aom1watchs4/YouTube-Mini/actions/runs/46042147802) completed successfully:
- Android companion APK build: success.
- Vela watch RPK build: success.

The workflow published debug and release artifacts for both apps. Successful packaging is not the same as a physical-device installation or playback test; those still need to be performed on the paired phone and Watch S4.

## Next milestones

1. Install the latest APK and RPK on the paired phone and Watch S4; verify search, selection, comments, and phone handoff.
2. Check the frame animation's responsiveness and resource use on the physical watch.
3. Continue investigating documented Vela media capabilities and device-specific native support for local video.
4. Only implement watch-side playback if a supported playback path is confirmed; then test decoding, buffering, controls, and battery impact on-device.
