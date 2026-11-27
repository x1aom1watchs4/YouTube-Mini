# Phone ↔ Watch communication contract

Status: prototype. The watch transport uses Xiaomi Vela `@system.interconnect`; the Android companion uses Xiaomi XMS Wearable `NodeApi` and `MessageApi`, matching the message transport pattern in the existing WristBook / Vers AI companion.

## Connection states

- `disconnected`: show **Нет соединения**.
- `connecting`: show a neutral connecting indicator only while an explicit user-initiated connection attempt is in progress.
- `connected`: enable transfer and remote controls.

Vela manages establishment and lifecycle of the paired-app interconnect automatically. The app does not run its own reconnect loop. The **Проверить связь** button explicitly runs the documented connection diagnosis and refreshes the visible state.

## Message envelope

All messages are UTF-8 JSON objects with a version and type:

```json
{
  "v": 1,
  "type": "ping",
  "requestId": "local-unique-id",
  "payload": {}
}
```

Receivers must ignore unknown message types and unsupported protocol versions. Validate every field and cap message sizes before processing.

## Initial message types

| Type | Direction | Purpose |
|---|---|---|
| `video.search` | watch → phone | Search YouTube using the API key entered in the phone app |
| `video.results` | phone → watch | Return up to 10 result metadata records |
| `video.search.error` | phone → watch | Return a safe search error |
| `video.select` | both | Send selected video ID, title, channel and thumbnail URL; never send media bytes |
| `playback.command` | both | Send `play`, `pause`, `seek`, or `stop` command |
| `video.stream.start` | phone → watch | Start a local-file frame stream |
| `video.stream.frame` | phone → watch | Deliver one compressed JPEG frame |
| `video.stream.end` | phone → watch | End the current frame stream |
| `video.stream.error` | phone → watch | Report a frame-stream error |

## Search payload

A watch search request carries a short query string. The phone performs the official YouTube Data API search and returns a bounded list of metadata records (`videoId`, `title`, `channelTitle`, `thumbnailUrl`). API keys are entered on the phone and are not stored by this prototype.

## Video selection payload

```json
{
  "videoId": "YouTube video identifier",
  "title": "Display title",
  "channelTitle": "Channel",
  "thumbnailUrl": "HTTPS thumbnail URL"
}
```

Treat titles and metadata as untrusted display text. Do not execute or interpret them as markup.

The `hello`, `ping`/`pong`, playback-state, and generic error messages are reserved for later protocol versions; they are not implemented in this prototype.

## Playback constraints

For local video playback, Android decodes a user-selected file with MediaMetadataRetriever and sends bounded JPEG frames. The watch writes alternating frame files using `@system.file.writeArrayBuffer` and displays them with the Vela image component. This is frame streaming, not MP4 decoding on the watch. YouTube streams are never extracted, proxied, or relayed.

## Implementation gate

The Android companion checks connected nodes while its screen is open and registers an XMS message listener for the selected node. The watch relies on Vela-managed interconnect lifecycle callbacks and an explicit `diagnosis()` check. Both sides use the same JSON envelope. Actual on-device interoperability still needs a paired-device test with matching signing certificates.
