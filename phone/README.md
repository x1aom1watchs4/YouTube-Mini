# YouTube Mini — Android companion

The Android application ID is intentionally identical to the Vela RPK package:
`com.x1aom1watchs4.youtubemini`.

## Xiaomi interconnect SDK

The phone module uses Xiaomi's XMS Wearable API (`Wearable`, `NodeApi`, and `MessageApi`). The AAR is not checked into this repository. Obtain the `xms-wearable-lib_1.4_release.aar` from the official Xiaomi Vela interconnect demo in:

https://github.com/open-vela/packages_apps/tree/dev/wearable/interconnect_image_demo

Place it at:

`phone/app/libs/xms-wearable-lib_1.4_release.aar`

The Android APK and Vela RPK must be signed with the same certificate. The RPK manifest package must match the Android application ID. Never commit private signing keys, certificates, passwords, or API keys.

## YouTube search

Search uses the official YouTube Data API v3 `search.list` endpoint. Enter your own API key in the app when testing. The key is not saved by this prototype. Quotas and API policies apply.

## Build

Open the `phone/` folder in Android Studio after placing the AAR in `app/libs/`. Configure the Android SDK and a local signing configuration, then build the debug APK.

This companion prototype sends video metadata and playback commands only. It does not download or relay YouTube media streams. Actual watch-side video rendering remains unverified.
