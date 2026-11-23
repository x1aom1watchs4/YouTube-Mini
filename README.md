# YouTube Mini 0.1

A research-stage companion project for Xiaomi Watch S4 (Vela) and Android.

## Current status

This repository was empty when work began. The first milestone is to verify the supported Vela app installation path and the watch/phone communication channel before implementing playback.

## Target experience

- Search and select a YouTube video on the phone or watch.
- Send the selected video's metadata to the watch.
- Show playback controls on both devices.
- Display an explicit **No connection** state when the companion link closes.
- Use 240p as the initial quality target where a supported playback path exists.
- Prefer stability and bounded resource use over aggressive feature removal.

## Confirmed Vela communication API

The official Vela API for communication with the paired Android companion app is `@system.interconnect`:

- `interconnect.instance()` returns the app connection singleton.
- `getReadyState()` reports connected/disconnected.
- `onopen`, `onclose`, and `onerror` report connection changes.
- `send()` and `onmessage` provide bidirectional messages.

The official documentation requires the Vela Quick App package name to match the Android companion package name, and the signing identity to match as well. This must be configured deliberately; do not ship placeholder signing material.

Reference: https://iot.mi.com/vela/quickapp/en/features/network/interconnect.html

## Important platform limitation

Xiaomi's Watch S4 FAQ says third-party apps are not officially supported for ordinary users. Vela documentation does describe a developer/debug installation route, but access and installation on the target retail watch must be verified.

The Vela sample catalogue documents an audio/music player, not a general video player. Therefore direct YouTube video rendering on the watch is **not yet confirmed**. Do not claim playback works until a real-device test proves that a supported video surface/decoder exists.

## Planned milestones

1. Verify Vela Quick App installation and launch on the actual Watch S4.
2. Port the existing proven interconnect implementation from WristBook / Vers AI after those sources are available.
3. Build and test a minimal phone-watch handshake and connection-state UI.
4. Verify an allowed video playback path on the watch before implementing YouTube search/playback.
5. Add search, selection, metadata transfer, and synchronized playback controls.

## Source needed

The WristBook and/or Vers AI repository URL is needed to reuse the exact existing companion communication implementation instead of guessing its message protocol or Android setup.
