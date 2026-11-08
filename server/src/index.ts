import express from 'express';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import multer from 'multer';
import { config, isAllowed } from './config';
import { Hub } from './hub';
import { EvolutionClient } from './evolution';
import { ensureMediaDir, mediaPath, scheduleCleanup, toOggOpus, toWav16k, generateToneWav } from './media';
import { normalize, isGroup } from './whatsapp';
import type { CallPayload, PendingCall } from './types';

export interface BridgeDeps {
  evolution?: EvolutionClient;
  /** URL pública usada para montar o audioUrl (padrão: PUBLIC_HOST ou IP local). */
  publicBaseUrl?: () => string;
}

export interface Bridge {
  app: express.Express;
  server: http.Server;
  hub: Hub;
  pending: Map<string, PendingCall>;
}

export function createBridge(deps: BridgeDeps = {}): Bridge {
  const evolution = deps.evolution ?? new EvolutionClient();
  const pending = new Map<string, PendingCall>();
  /** Último QR code de pareamento recebido da Evolution (v2 manda por webhook). */
  let lastQr: { base64?: string; ref?: string; pairingCode?: string; at: number } | null = null;
  /** Estado da conexão da instância ('connecting', 'open', 'close'...). */
  let instanceState = '';
  /** Última vez que buscamos o QR direto na Evolution (reinicia a instância). */
  let lastQrFetchAt = 0;

  const app = express();
  app.use(express.json({ limit: '4mb' }));
  app.use(express.urlencoded({ extended: true }));

  const server = http.createServer((req, res) => app(req, res));
  const hub = new Hub(server, '/ws');

  const publicBaseUrl = deps.publicBaseUrl ?? (() => {
    if (config.publicHost) return config.publicHost;
    return `http://${localAddress()}:${config.port}`;
  });

  /* ------------------------------------------------------------------ *
   * Webhook do WhatsApp
   * ------------------------------------------------------------------ */

  // A Evolution v2 anexa o evento ao caminho do webhook
  // (ex.: /webhook/whatsapp/messages-upsert), então as duas formas valem.
  app.post(['/webhook/whatsapp', '/webhook/whatsapp/:event'], (req, res) => {
    // ack imediato; o processamento é assíncrono para não travar o Evolution.
    res.status(200).json({ ok: true });
    const event = String(req.params.event ?? '');
    handleWebhook(req.body, event).catch((err) => console.error('[webhook] erro:', err.message));
  });

  async function handleWebhook(body: any, event = ''): Promise<void> {
    if (event.startsWith('qrcode')) {
      const qr = pickQrcode(body);
      if (qr) {
        lastQr = { ...qr, at: Date.now() };
        console.log(`[webhook] QRcode recebido (${qr.ref ?? 'sem ref'})`);
      }
      return;
    }

    if (event.startsWith('connection')) {
      const state = String(body?.data?.state ?? body?.data?.connectionStatus ?? '');
      if (state) {
        instanceState = state;
        console.log(`[webhook] conexão: ${state}`);
      }
      return;
    }

    const evt = normalize(body);
    if (!evt) return;
    if (evt.fromMe) return;

    if (isGroup(evt.remoteJid)) {
      console.log('[webhook] grupo ignorado');
      return;
    }

    if (!isAllowed(evt.number)) {
      console.log(`[webhook] remetente bloqueado: ${evt.number}`);
      return;
    }

    const audioId = evt.messageId.replace(/[^\w.-]/g, '_');
    console.log(`[webhook] áudio de ${evt.number} (${evt.messageId})`);

    const media = await evolution.downloadMedia(evt);
    const rawName = `${audioId}.${media.extension}`;
    const wavName = `${audioId}.wav`;

    fs.writeFileSync(mediaPath(rawName), media.data);
    await toWav16k(mediaPath(rawName), mediaPath(wavName));
    scheduleCleanup(rawName);
    scheduleCleanup(wavName);

    pending.set(audioId, {
      audioId,
      senderNumber: evt.number,
      senderName: evt.pushName || evt.number,
      createdAt: Date.now(),
    });

    const payload: CallPayload = {
      type: 'NEW_VOICE_NOTE',
      audioId,
      senderName: evt.pushName || evt.number,
      senderNumber: evt.number,
      audioUrl: `${publicBaseUrl()}/media/${wavName}`,
    };

    console.log(`[push] ${payload.senderName} -> relógio: ${payload.audioUrl}`);
    hub.broadcast(payload);
  }

  /**
   * QR atualizado.
   *
   * A Evolution renova o QR sozinha (~30 s) e avisa por webhook — é esse
   * caminho que usamos. Só buscamos o QR direto nela se o webhook ficar
   * mudo por mais de 2 minutos, porque pedir o QR **reinicia a instância** e
   * um QR invalidado faz o WhatsApp responder "não foi possível conectar".
   */
  async function freshQr(): Promise<typeof lastQr> {
    const age = lastQr ? Date.now() - lastQr.at : Infinity;
    if (age < 120_000) return lastQr;
    if (instanceState === 'open') return lastQr;
    if (Date.now() - lastQrFetchAt < 120_000) return lastQr;

    lastQrFetchAt = Date.now();
    const qr = await evolution.getQrCode();
    if (qr?.base64) {
      lastQr = { ...qr, at: Date.now() };
      console.log('[qrcode] QR buscado direto na Evolution');
    }
    return lastQr;
  }

  /* ------------------------------------------------------------------ *
   * Mídia em cache
   * ------------------------------------------------------------------ */

  /** Tom sintético em PCM 16 kHz mono: teste de chamada sem WhatsApp.
   *  Fica antes de /media/:audioId para não ser capturado pelo parâmetro. */
  app.get('/media/test.wav', async (_req, res) => {
    const file = mediaPath('test.wav');
    try {
      if (!fs.existsSync(file)) {
        await generateToneWav(file);
      }
      res.setHeader('Content-Type', 'audio/wav');
      res.setHeader('Cache-Control', 'public, max-age=60');
      fs.createReadStream(file).pipe(res);
    } catch (err: any) {
      res.status(500).json({ error: err.message });
    }
  });

  app.get('/media/:audioId', (req, res) => {
    const id = path.basename(req.params.audioId).replace(/[^\w.-]/g, '_');
    const file = mediaPath(id.endsWith('.wav') ? id : `${id}.wav`);
    if (!fs.existsSync(file)) {
      res.status(404).json({ error: 'áudio não encontrado ou expirado' });
      return;
    }
    res.setHeader('Content-Type', 'audio/wav');
    res.setHeader('Cache-Control', 'public, max-age=300');
    fs.createReadStream(file).pipe(res);
  });

  /* ------------------------------------------------------------------ *
   * Resposta de áudio (multipart) -> WhatsApp
   * ------------------------------------------------------------------ */

  const upload = multer({
    storage: multer.diskStorage({
      destination: (_req, _file, cb) => cb(null, ensureMediaDir()),
      filename: (_req, file, cb) => {
        const ext = path.extname(file.originalname || '') || '.m4a';
        cb(null, `upload_${Date.now()}_${Math.random().toString(36).slice(2)}${ext}`);
      },
    }),
    limits: { fileSize: config.maxUploadBytes },
  });

  app.post('/reply/audio', upload.single('file'), async (req, res) => {
    const file = req.file;
    const audioId = String(req.body.audioId ?? '');
    const toRaw = String(req.body.to ?? '');

    const call = audioId ? pending.get(audioId) : undefined;
    const target = (toRaw || call?.senderNumber || '').replace(/\D/g, '');

    if (!file) {
      console.error(`[reply] sem arquivo (campos: ${Object.keys(req.body).join(',')})`);
      res.status(400).json({ error: 'arquivo de áudio ausente (campo "file")' });
      return;
    }
    if (!target) {
      console.error(`[reply] sem destinatário (audioId=${audioId}, to=${toRaw})`);
      res.status(400).json({ error: 'destinatário ausente (campo "to" ou "audioId" válido)' });
      return;
    }

    const oggName = `reply_${Date.now()}_${Math.random().toString(36).slice(2)}.ogg`;
    try {
      await toOggOpus(file.path, mediaPath(oggName));
      const ogg = fs.readFileSync(mediaPath(oggName));
      const result = await evolution.sendVoiceNote(target, ogg);
      if (audioId) pending.delete(audioId);
      console.log(`[reply] resposta enviada para ${target} (${ogg.length} bytes)`);
      res.json({ ok: true, to: target, sent: result?.data ?? result ?? null });
    } catch (err: any) {
      console.error('[reply] erro:', err.message);
      res.status(502).json({ error: err.message });
    } finally {
      fs.promises.unlink(file.path).catch(() => undefined);
      fs.promises.unlink(mediaPath(oggName)).catch(() => undefined);
    }
  });

  /* ------------------------------------------------------------------ *
   * Infra
   * ------------------------------------------------------------------ */

  app.get('/health', (_req, res) => {
    res.json({ ok: true, clients: hub.size, pending: pending.size });
  });

  app.get('/pending', (_req, res) => {
    res.json([...pending.values()]);
  });

  /* ------------------------------------------------------------------ *
   * Pareamento (QR) — a Evolution manda o QR por webhook
   * ------------------------------------------------------------------ */

  app.get('/qrcode', async (_req, res) => {
    const qr = await freshQr();
    if (!qr?.base64) {
      res.status(404).send('<p>Sem QR code. Pareie pelo painel: http://192.168.15.100:8098/manager</p>');
      return;
    }
    const img = `data:image/png;base64,${qr.base64}`;
    const code = qr.pairingCode
      ? `<h2>Código de pareamento</h2><p style="font-size:44px;letter-spacing:6px">${qr.pairingCode.slice(0, 4)}-${qr.pairingCode.slice(4, 8)}</p>` +
        `<p>WhatsApp → Aparelhos conectados → Conectar com número de telefone</p>`
      : '';
    // o QR da Evolution gira a cada ~30s: a página se atualiza sozinha para
    // nunca mostrar um QR vencido (senão o WhatsApp responde "não foi possível
    // conectar o dispositivo").
    res.setHeader('Cache-Control', 'no-store');
    res.type('html').send(
      `<!doctype html><meta charset="utf-8"><meta http-equiv="refresh" content="3">` +
        `<title>wabridge — parear WhatsApp</title>` +
        `<body style="font-family:sans-serif;background:#111;color:#eee;text-align:center">` +
        `<h2>Escaneie no WhatsApp (Aparelhos conectados → Conectar)</h2>` +
        `<img src="${img}" style="background:#fff;padding:12px;border-radius:8px">` +
        code +
        `<p>atualiza sozinho a cada 3s · gerado em ${new Date(qr.at).toLocaleTimeString('pt-BR')}</p></body>`,
    );
  });

  app.get('/qrcode.json', async (_req, res) => {
    const qr = await freshQr();
    if (!qr) {
      res.status(404).json({ error: 'sem QR code' });
      return;
    }
    res.json(qr);
  });

  app.get('/qrcode.png', async (_req, res) => {
    const qr = await freshQr();
    if (!qr?.base64) {
      res.status(404).json({ error: 'sem QR code' });
      return;
    }
    res.setHeader('Content-Type', 'image/png');
    res.setHeader('Cache-Control', 'no-store');
    res.end(Buffer.from(qr.base64, 'base64'));
  });

  return { app, server, hub, pending };
}

/** Procura o QR code (base64) em qualquer nível do payload da Evolution. */
function pickQrcode(body: any): { base64?: string; ref?: string; pairingCode?: string } | null {
  const seen = new Set<any>();
  const walk = (obj: any): { base64?: string; ref?: string; pairingCode?: string } | null => {
    if (!obj || typeof obj !== 'object' || seen.has(obj)) return null;
    seen.add(obj);
    if (typeof obj.base64 === 'string' && obj.base64.length > 0) {
      // pode vir como data URI: data:image/png;base64,XXXX
      const comma = obj.base64.indexOf(',');
      const b64 = comma >= 0 ? obj.base64.slice(comma + 1) : obj.base64;
      const code = obj.pairingCode ?? obj.pairing_code;
      return {
        base64: b64,
        ref: obj.ref ? String(obj.ref) : undefined,
        pairingCode: typeof code === 'string' && code.length > 0 ? code : undefined,
      };
    }
    for (const v of Object.values(obj)) {
      const r = walk(v);
      if (r) return r;
    }
    return null;
  };
  return walk(body);
}

export function localAddress(): string {
  const nets = os.networkInterfaces();
  for (const list of Object.values(nets)) {
    for (const net of list ?? []) {
      if (net.family === 'IPv4' && !net.internal) return net.address;
    }
  }
  return '127.0.0.1';
}

function main(): void {
  ensureMediaDir();
  const { server } = createBridge();
  server.listen(config.port, config.bind, () => {
    console.log('──────────────────────────────────────────────');
    console.log('  wabridge server');
    console.log(`  ws://${localAddress()}:${config.port}/ws`);
    console.log(`  http://${localAddress()}:${config.port}/media/:id`);
    console.log(`  evolution: ${config.evolution.baseUrl} (${config.evolution.instance})`);
    console.log(`  permitidos: ${config.allowedNumbers.join(', ') || '(todos)'}`);
    console.log('──────────────────────────────────────────────');
  });
}

if (require.main === module) {
  main();
}
