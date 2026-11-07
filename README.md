# wabridge — ponte WhatsApp ⇄ smartwatch Huawei

Áudio de voz do WhatsApp chega como **chamada telefônica** no relógio (via
Bluetooth HFP/SCO) e a resposta gravada no relógio volta ao WhatsApp como
nota de voz.

```
 Evolution API ──webhook──▶ server (Node/TS)
                               │  ffmpeg: opus → wav 16k mono
                               │  WebSocket NEW_VOICE_NOTE
                               ▼
                        app Android (Kotlin)
                    ConnectionService self-managed
                    ▶ toca no SCO do relógio
                    ▶ grava o microfone do relógio
                    ▶ POST /reply/audio (multipart)
                               │  ffmpeg: m4a → ogg opus (ptt)
                               ▼
                        Evolution API → WhatsApp
```

## Pastas

| Pasta | O quê |
|---|---|
| `server/` | Node.js + TypeScript (Express + ws). Webhook, cache de mídia, upload de resposta. |
| `android-bridge/` | App Kotlin (Gradle KTS) com `ConnectionService` autogerenciado. |

## Rodar

```bash
# 1. servidor
cd server
cp .env.example .env      # ajuste ALLOWED_NUMBERS e PUBLIC_HOST
npm install
npm run dev               # ou: npm run build && npm start

# 2. Evolution API
#    aponte o webhook para http://<IP_SERVIDOR>:8097/webhook/whatsapp

# 3. app
cd ../android-bridge
./gradlew assembleDebug   # APK em app/build/outputs/apk/debug/
```

No relógio: abrir o app, colocar a URL do servidor (`http://IP:8097`) e tocar
**Conectar**. A URL fica salva no aparelho.

## Variáveis do servidor

| Var | Padrão | Função |
|---|---|---|
| `PORT` | `8090` | porta HTTP/WS |
| `PUBLIC_HOST` | IP local detectado | URL usada pelo relógio para baixar o áudio |
| `ALLOWED_NUMBERS` | vazio (aceita todos) | lista branca de remetentes |
| `EVOLUTION_API_URL` | `http://127.0.0.1:8080` | Evolution API |
| `EVOLUTION_INSTANCE` | `wabridge` | nome da instância |
| `EVOLUTION_API_KEY` | vazio | chave da Evolution API |
| `FFMPEG_PATH` | `ffmpeg` | binário do ffmpeg |

## Endpoints

| Rota | Uso |
|---|---|
| `POST /webhook/whatsapp` | entrada dos eventos do WhatsApp |
| `GET /media/:audioId.wav` | áudio recebido em cache (PCM 16k mono) |
| `POST /reply/audio` | resposta do relógio (`multipart`: `file`, `audioId`, `to`) |
| `GET /health` | estado do servidor |
| `GET /pending` | conversas em aberto |
| `ws://host:8097/ws` | eventos para o app |

## Pendente: chave de assinatura (PK)

A assinatura de release do APK está isolada em `android-bridge/keystore.properties`
(veja `keystore.properties.template`). Sem esse arquivo o build usa a chave de
debug. Basta gerar o `.jks` e preencher o arquivo — o `build.gradle.kts` já
lê e aplica automaticamente.
