import express from 'express';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import multer from 'multer';
import { config, isAllowed } from './config';
import { Hub } from './hub';
import { EvolutionClient } from './evolution';
import { ensureMediaDir, mediaPath, scheduleCleanup, toOggOpus, toWav16k } from './media';
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

  app.post('/webhook/whatsapp', (req, res) => {
    // ack imediato; o processamento é assíncrono para não travar o Evolution.
    res.status(200).json({ ok: true });
    handleWebhook(req.body).catch((err) => console.error('[webhook] erro:', err.message));
  });

  async function handleWebhook(body: any): Promise<void> {
    const evt = normalize(body);
    if (!evt) return;
    if (evt.fromMe) return;

    const jid = String(body?.data?.key?.remoteJid ?? '');
    if (isGroup(jid)) {
      console.log('[webhook] grupo ignorado');
      return;
    }

    if (!isAllowed(evt.number)) {
      console.log(`[webhook] remetente bloqueado: ${evt.number}`);
      return;
    }

    const audioId = evt.messageId.replace(/[^\w.-]/g, '_');
    console.log(`[webhook] áudio de ${evt.number} (${evt.messageId})`);

    const media = await evolution.downloadMedia(evt.audioUrl, evt.mimetype);
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

  /* ------------------------------------------------------------------ *
   * Mídia em cache
   * ------------------------------------------------------------------ */

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
      res.status(400).json({ error: 'arquivo de áudio ausente (campo "file")' });
      return;
    }
    if (!target) {
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

  return { app, server, hub, pending };
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
