# Phone ↔ Watch communication contract

Status: draft. This is an application-level message contract for the official Vela `@system.interconnect` channel. It does not replace the transport implementation from WristBook / Vers AI.

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
| `hello` | both | Exchange app/protocol capabilities |
| `ping` / `pong` | both | Check that the app-level channel is responsive |
| `video.search` | watch → phone | Search YouTube using the API key entered in the phone app |\n| `video.results` | phone → watch | Return up to 10 result metadata records |\n| `video.search.error` | phone → watch | Return a safe search error |\n| `video.select` | both | Send selected video ID, title, channel and thumbnail URL; never send media bytes |
| `playback.command` | both | Send `play`, `pause`, or `seek` command |
| `playback.state` | both | Report position, duration and play/pause state |
| `error` | both | Report a safe, user-readable error code |

## Search payload\n\nA watch search request carries a short query string. The phone performs the official YouTube Data API search and returns a bounded list of metadata records (`videoId`, `title`, `channelTitle`, `thumbnailUrl`). API keys are entered on the phone and are not stored by this prototype.\n\n## Video selection payload

```json
{
  "videoId": "YouTube video identifier",
  "title": "Display title",
  "channelTitle": "Channel",
  "thumbnailUrl": "HTTPS thumbnail URL"
}
```

Treat titles and metadata as untrusted display text. Do not execute or interpret them as markup.

## Playback constraints

The protocol only transports metadata and control messages. It does not download, proxy, extract, or relay YouTube media streams. A supported playback surface and permitted playback integration must be verified on the actual watch before enabling playback controls.

## Implementation gate

Before wiring this contract to the real transport, inspect the WristBook / Vers AI source and preserve its package identity, signing setup, connection lifecycle and message framing wherever compatible.
