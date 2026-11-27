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
| `playback.command` | both | Send `play`, `pause`, or `seek` command |

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

The protocol only transports metadata and control messages. It does not download, proxy, extract, or relay YouTube media streams. A supported playback surface and permitted playback integration must be verified on the actual watch before enabling playback controls.

## Implementation gate

The Android companion checks connected nodes while its screen is open and registers an XMS message listener for the selected node. The watch relies on Vela-managed interconnect lifecycle callbacks and an explicit `diagnosis()` check. Both sides use the same JSON envelope. Actual on-device interoperability still needs a paired-device test with matching signing certificates.
