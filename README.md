# YouTube Mini 0.1

A Xiaomi Watch S4 (Vela) + Android companion prototype.

## Current implementation

- Vela watch app shell with a connection indicator and explicit **Нет соединения** state.
- Watch-to-phone interconnect client using Xiaomi's `@system.interconnect`.
- Lightweight on-watch QWERTY input for sending search queries to the phone.
- Android companion project under `phone/`.
- Phone-side YouTube Data API v3 search (the user supplies an API key; it is not stored).
- Video metadata selection and playback-control message scaffolding in both directions.

## Platform communication

The transport is Xiaomi XMS Wearable / Vela Interconnect, not a hand-written BLE socket. The Vela runtime automatically manages the paired-app connection. The watch app registers `onopen`, `onclose`, `onerror`, and `onmessage` handlers and uses `diagnosis()` for a user-requested connection check.

The official Xiaomi sample is:

https://github.com/open-vela/packages_apps/tree/dev/wearable/interconnect_image_demo

The Android side uses the XMS Wearable AAR from that demo. See [phone setup](phone/README.md).

The Android `applicationId` and Vela manifest `package` must match exactly. Both packages must be signed with the same certificate. Never commit private keys, certificates, signing passwords, or API keys.

## Important playback limitation

The current code transfers video metadata and playback commands only. It does **not** yet play video on the watch. A supported Vela video surface/decoder and a permitted YouTube playback integration have not been verified on the actual Xiaomi Watch S4. Do not treat the current play/pause buttons as a working media player until that real-device milestone passes.

The phone search uses the official YouTube Data API v3 search endpoint. API quota and YouTube API policies apply. No media streams are extracted, proxied, or relayed.

## Build status

Source files have been committed, but no APK or RPK has been built or tested in this environment. The Android build requires the Xiaomi AAR and a locally configured Android SDK. Vela packaging requires AIoT-IDE / aiot-toolkit and a matching local signing setup.

## Next milestones

1. Add the Xiaomi AAR locally and build the Android companion.
2. Package the Vela RPK with the same application ID and matching signing certificate.
3. Test bidirectional search and command exchange on a paired Watch S4.
4. Verify whether direct video rendering is supported on the watch.
5. Only then implement and test actual playback, buffering, and 240p quality selection.
