# Wabridge

**Áudios do WhatsApp como chamadas no relógio.** O Wabridge encaminha áudios recebidos no WhatsApp para um celular ou relógio Huawei como chamadas telefônicas e envia a resposta gravada de volta como nota de voz.

[English](README.md) · [Português](README.pt-BR.md) · [Español](README.es.md) · [简体中文](README.zh-CN.md)

## Como funciona

```text
WhatsApp / Evolution API
        │ webhook de áudio recebido
        ▼
Servidor Node.js ou motor Go embutido
        │ prepara o áudio e envia evento WebSocket
        ▼
App Android ── Bluetooth HFP/SCO ── relógio
        ▲                                  │
        └──── resposta de voz gravada ─────┘
```

O app Android oferece o motor Go embutido ou um servidor Node.js externo. O áudio recebido aparece como uma chamada; ao atender, o relógio reproduz a nota e grava a resposta. O app envia a gravação original ao motor, que a transmite como nota de voz do WhatsApp.

## Estrutura do repositório

| Caminho | Finalidade |
|---|---|
| `android-bridge/` | App Android, código Kotlin, build Gradle e testes JVM |
| `embedded-core/` | Motor WhatsApp em Go, API HTTP/WebSocket e testes |
| `server/` | Servidor opcional Node.js/TypeScript integrado à Evolution API |
| `.github/workflows/ci.yml` | Testes e builds/releases do APK na CI |

## Baixar o app Android

Baixe o APK assinado em [GitHub Releases](https://github.com/KelvinKSPS/wabridge/releases). O workflow de release roda quando uma tag `v*` é enviada. A CI também cria artefatos APK de debug em pushes e pull requests.

## Build e testes

Os APKs de release são compilados pelo GitHub Actions. Para rodar os testes JVM localmente:

```bash
cd android-bridge
./gradlew testDebugUnitTest --console=plain
```

Para testar o motor Go embutido:

```bash
cd embedded-core
go test ./...
```

Para o servidor Node opcional:

```bash
cd server
npm ci
npm test
npm run build
```

O app Android requer JDK 17 e configuração do Android SDK. O workflow da CI compila bibliotecas Go para Android `arm64-v8a` e `x86_64`, empacota o APK e publica os artefatos. A release assinada requer os secrets de assinatura Android configurados no repositório; sem eles, o workflow informa que o keystore está indisponível.

## Configuração

1. Instale o APK no celular ou relógio Android.
2. Vincule a conta do WhatsApp pelo fluxo de pareamento do app.
3. Selecione o motor embutido ou informe a URL de um servidor externo.
4. Conceda as permissões de microfone, Bluetooth e notificações quando solicitadas.
5. Opcionalmente, selecione dispositivos Bluetooth e contatos/grupos permitidos; depois, inicie o serviço.

Para usar o servidor externo, configure o arquivo `.env` a partir de `server/.env.example`, incluindo credenciais da Evolution API e o host do webhook. Não versione secrets. Consulte [`server/README.md`](server/README.md) para rotas e configuração do servidor.

## Funcionalidades

- Serviço Android em primeiro plano com reconexão do WebSocket do WhatsApp.
- Reprodução e gravação de respostas por Bluetooth HFP/SCO.
- Velocidade de reprodução configurável, incluindo valores personalizados de 0,25× a 4×.
- Atraso configurável antes de reproduzir o áudio.
- Histórico de áudios recebidos, atendidos e rejeitados, com opção de limpar.
- Filtro opcional por dispositivos Bluetooth e lista permitida de contatos/grupos.
- Diagnóstico de áudio e preservação da gravação original para investigação.
- App localizado em português, inglês, espanhol e chinês simplificado.

## Segurança e privacidade

O app processa credenciais da conta do WhatsApp e áudio localmente ou pelo servidor configurado. Use uma rede confiável, proteja as credenciais da Evolution API e as chaves de assinatura, e não publique diagnósticos ou logs com dados pessoais. O microfone é necessário somente durante a gravação da resposta; o indicador de privacidade do Android permanece visível enquanto o microfone está em uso.

## Licença

Consulte o arquivo de licença do repositório, se houver.
