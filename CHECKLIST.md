# wabridge — quadro de progresso

Legenda: `[x]` pronto · `[ ]` em andamento / delegado / pendente

## Servidor (Node 24 · TypeScript · Express · ws)

- [x] Estrutura do projeto e build (tsconfig strict, scripts npm)
- [x] Configuração por ambiente (PORT, PUBLIC_HOST, ALLOWED_NUMBERS, EVOLUTION_*, FFMPEG_PATH)
- [x] Cliente da Evolution API (download de mídia e envio de nota de voz com `ptt`)
- [x] Normalizador do webhook (aceita `message.audio`, `audioMessage` e `document` de áudio)
- [x] Filtro de remetentes (lista branca, grupos e mensagens de saída ignorados)
- [x] Conversão opus → wav PCM 16 kHz mono (exigido pelo SCO)
- [x] Conversão m4a → ogg opus (resposta do relógio)
- [x] Cache de mídia (`/media/:id` com expiração por TTL)
- [x] Hub de WebSocket (evento `NEW_VOICE_NOTE`)
- [x] Upload da resposta (`POST /reply/audio`, multipart)
- [x] Endpoints de estado (`/health`, `/pending`)

## App Android (Kotlin · AGP 8.6.0 · minSdk 26)

- [x] Projeto Gradle KTS (wrapper 8.10.2, assinatura por `keystore.properties`)
- [x] Manifest e permissões (MANAGE_OWN_CALLS, RECORD_AUDIO, MODIFY_AUDIO_SETTINGS, BLUETOOTH_CONNECT, FGS)
- [x] Conta telefônica autogerenciada (self-managed)
- [x] Serviço em primeiro plano (`specialUse` para Android 14+) com WebSocket
- [x] WebSocket com reconexão e backoff (OkHttp)
- [x] Chamada entrante (`addNewIncomingCall` com o payload do servidor)
- [x] Nome e número do contato (`setCallerDisplayName` + `setAddress`)
- [x] Canal SCO com buffer (estado conectado + 800 ms)
- [x] Reprodução do áudio recebido (`USAGE_VOICE_COMMUNICATION`)
- [x] Gravação da resposta (tom de aviso + MediaRecorder VOICE_COMMUNICATION, AAC 16 kHz / 32 kbps)
- [x] Desligar envia o áudio (para SCO, restaura `MODE_NORMAL`, sobe o arquivo)
- [x] Timeout de segurança (90 s de gravação)
- [x] Tela de configuração (URL do servidor editável e persistida)
- [x] Compilar APK debug (2,5 MB, gerado aqui e no PC Windows)
- [x] APK release assinado (chave CN=WA Bridge gerada aqui, release v1.0.0 no GitHub)

## Testes

- [x] Normalização do webhook — 9 casos
- [x] Lista branca de números — 6 casos
- [x] Conversão de mídia (ffmpeg) — 4 casos
- [x] Rotas e fluxo completo (Evolution falsa) — 9 casos
- [x] Máquina de estados da chamada — 8 casos
- [x] URLs de WebSocket e HTTP — 7 casos
- [x] Payload da chamada (JSON) — 5 casos
- [x] Suíte Android executada por Gradle (20/20 aqui, 40/40 no PC Windows)
- [x] Teste ponta a ponta com Evolution falsa (`server/test/`)

## Infra e entrega

- [x] aapt2 em ARM (sysroot x86 + qemu-user; o SDK traz binário x86-64)
- [x] Repositório no GitHub (`github.com/KelvinKSPS/wabridge`)
- [x] Documentação (README do servidor, do app e da raiz) e `RELATORIO.md`
- [x] Unidade systemd para o servidor
- [x] Tarefa enviada ao outro agente (sessão "fale comigo")
- [x] Clone, testes e build no computador de 16 GB (40 testes verdes em 14 s)
- [x] Chave de assinatura (`scripts/make-keystore.sh`, cópia em `~/wabridge-keys/`)
- [x] Release no GitHub com o APK assinado (v1.0.0)
- [x] Instalação em emulador (instalou, abriu a tela principal, sem crash)
- [x] Servidor no ar com pm2 (reinicia sozinho, `http://192.168.15.100:8097`)
- [ ] Evolution API pareada por QR (precisa do celular do usuário)
- [ ] `ALLOWED_NUMBERS` definido no `server/.env` (hoje aceita qualquer remetente)
