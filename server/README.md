# server: WhatsApp bridge (Node.js + TypeScript)

Optional server that receives WhatsApp voice notes from Evolution API, prepares
them for the Android app, and sends the recorded replies back as WhatsApp voice
notes. The Android app can also use the embedded Go engine instead
(`embedded-core/`); this server is the alternative.

## Install

```bash
npm install
cp .env.example .env   # set ALLOWED_NUMBERS, PUBLIC_HOST and EVOLUTION_*
npm run dev            # tsx watch
npm run build && npm start
```

Variables are read from `process.env`. Use `node --env-file=.env dist/index.js`
on Node 20+, or export them in your shell.

## Requirements

- `ffmpeg` with `libopus` and `pcm_s16le` (check with `ffmpeg -encoders | grep opus`).
- Evolution API v1 or v2. On v2, point the global webhook to
  `http://<BRIDGE_IP>:8097/webhook/whatsapp`. Evolution appends the event name
  to the path (`/webhook/whatsapp/messages-upsert`).

## Routes

### `POST /webhook/whatsapp`

Accepts the `messages.upsert` payload from Evolution API. The normalizer
(`normalize()` in `src/index.ts`) tolerates format variations: the message may be
in `data.message.audio`, `message.audio`, `audioMessage`, or `document` (PTT).
Group messages (`@g.us`), outgoing messages (`fromMe`), and senders outside the
allowlist are ignored.

Processing steps:

1. Download the audio. On **v2** the whole message goes to
   `POST {EVOLUTION}/chat/getBase64FromMediaMessage/{instance}` (v2 does not
   expose a public link). On **v1**, `chat/downloadMedia` is used with the link.
2. `ffmpeg` converts it to 16 kHz mono PCM WAV (required for Android Bluetooth SCO).
3. Publish `NEW_VOICE_NOTE` on the WebSocket.
4. The cache expires after `MEDIA_TTL_MS`.

### `GET /media/:audioId`

Serves the cached WAV. `audioId` is the sanitized message id.

### `POST /reply/audio`

`multipart/form-data`:

| Field | Required | Description |
|---|---|---|
| `file` | yes | Recording from the watch (`.m4a` / `.wav`) |
| `audioId` | no | Id of the original audio (resolves the recipient) |
| `to` | no | Recipient number, digits only |

Converts the file to `ogg`/Opus and sends it as a voice note (`ptt: true`). On
**v2** it is sent as base64 to `POST {EVOLUTION}/message/sendWhatsAppAudio/{instance}`.
On v1, `chat/sendAudio` is used, with `chat/sendMedia` as fallback.

### `GET /qrcode` and `GET /qrcode.png`

The latest pairing QR code sent by Evolution (v2). The QR expires in about 30 s.
The Evolution manager (`/manager`) generates a new one at any time.

## Test without a real WhatsApp account

`test/` contains a fake Evolution API to exercise the full cycle
(webhook → conversion → WebSocket → upload → voice note):

```bash
bash test/make-sample.sh
npm run test:mock &                       # fake Evolution on :8081
PORT=8097 EVOLUTION_API_URL=http://127.0.0.1:8081 npm start &
npm run test:e2e
```

Expected output:

```
webhook  -> 200 {"ok":true}
ws push  -> {"type":"NEW_VOICE_NOTE",...}
media    -> 200 96078 bytes
reply    -> 200 {"ok":true,...}
```

The received audio (Opus) is saved to `test/sample.ogg`, and the reply sent by the
app to `test/recebido.ogg`.

## Unit tests

```bash
npm test           # node --test over test/unit/*.test.ts
npm run typecheck
```

## WebSocket (`/ws`)

```json
{ "type": "NEW_VOICE_NOTE",
  "audioId": "MSG_ABC",
  "senderName": "John",
  "senderNumber": "5511999999999",
  "audioUrl": "http://192.168.0.10:8097/media/MSG_ABC.wav" }
```

The app replies `ping` (plain text) and receives `pong`.
