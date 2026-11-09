import { strict as assert } from 'node:assert';
import { test, describe, before, after } from 'node:test';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import WebSocket from 'ws';
import { createBridge } from '../../src/index';
import type { Bridge } from '../../src/index';
import { config } from '../../src/config';
import type { EvolutionClient } from '../../src/evolution';

const SAMPLE = path.join(__dirname, '..', 'sample.ogg');

/** Evolution API falsa: devolve o sample e registra o que foi enviado. */
class FakeEvolution {
  sent: { number: string; ptt: unknown; bytes: number }[] = [];
  texts: { number: string; text: string }[] = [];
  downloadCalls = 0;

  async downloadMedia() {
    this.downloadCalls++;
    return {
      data: fs.readFileSync(SAMPLE),
      extension: 'ogg',
      mimetype: 'audio/ogg; codecs=opus',
    };
  }

  async sendVoiceNote(number: string, ogg: Buffer) {
    this.sent.push({ number, ptt: true, bytes: ogg.length });
    return { key: { id: 'FAKE' } };
  }

  async sendText(number: string, text: string) {
    this.texts.push({ number, text });
    return { key: { id: 'FAKE_TEXT' } };
  }
}

let bridge: Bridge;
let fake: FakeEvolution;
let baseUrl: string;
let wsUrl: string;
let tmpDir: string;

before(async () => {
  tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'wabridge-routes-'));
  config.mediaDir = tmpDir;
  config.allowedNumbers = ['5511999999999'];

  fake = new FakeEvolution();
  bridge = createBridge({
    evolution: fake as unknown as EvolutionClient,
    publicBaseUrl: () => 'http://test.local',
  });
  await new Promise<void>((r) => bridge.server.listen(0, '127.0.0.1', r));
  const addr = bridge.server.address() as { port: number };
  baseUrl = `http://127.0.0.1:${addr.port}`;
  wsUrl = `ws://127.0.0.1:${addr.port}/ws`;
});

after(async () => {
  bridge.hub.close();
  bridge.server.closeAllConnections?.();
  await new Promise<void>((r) => bridge.server.close(() => r()));
  fs.rmSync(tmpDir, { recursive: true, force: true });
});

class WsClient {
  events: any[] = [];
  private ws: WebSocket;

  constructor(private url: string) {
    this.ws = new WebSocket(url);
    this.ws.on('message', (raw) => this.events.push(JSON.parse(raw.toString())));
  }

  ready(): Promise<void> {
    return new Promise((resolve) => this.ws.on('open', () => resolve()));
  }

  /** Espera um evento do tipo pedido (ou rejects). */
  waitFor(type: string, timeoutMs = 30000): Promise<any> {
    const found = this.events.find((e) => e.type === type);
    if (found) return Promise.resolve(found);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(
        () => reject(new Error(`timeout esperando evento ${type}`)),
        timeoutMs,
      );
      const onMessage = (raw: any) => {
        const msg = JSON.parse(raw.toString());
        if (msg.type === type) {
          clearTimeout(timer);
          this.ws.off('message', onMessage);
          resolve(msg);
        }
      };
      this.ws.on('message', onMessage);
    });
  }

  close() {
    this.ws.close();
  }
}

const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));

async function postWebhook(body: any): Promise<Response> {
  return fetch(`${baseUrl}/webhook/whatsapp`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
}

/** Espera a conversa aparecer na lista de pendências (processamento concluído). */
async function waitForPending(audioId: string, timeoutMs = 30000): Promise<any> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const list = await (await fetch(`${baseUrl}/pending`)).json();
    const found = list.find((p: any) => p.audioId === audioId);
    if (found) return found;
    await wait(100);
  }
  throw new Error(`timeout esperando pendência ${audioId}`);
}

const WEBHOOK = {
  instance: 'wabridge',
  data: {
    key: { remoteJid: '5511999999999@s.whatsapp.net', fromMe: false, id: 'MSG_TEST_1' },
    pushName: 'João da Silva',
    message: { audio: { mimetype: 'audio/ogg; codecs=opus', url: 'https://mmg/x' } },
  },
};

describe('rotas HTTP', () => {
  test('POST /webhook/whatsapp responde 200 e ignora payload sem áudio', async () => {
    const res = await postWebhook({ data: { key: { id: 'X' }, message: { text: { body: 'oi' } } } });
    assert.equal(res.status, 200);
  });

  test('GET /health responde ok', async () => {
    const res = await fetch(`${baseUrl}/health`);
    const body = await res.json();
    assert.equal(body.ok, true);
  });

  test('GET /media/:id inexistente responde 404', async () => {
    const res = await fetch(`${baseUrl}/media/nao_existe.wav`);
    assert.equal(res.status, 404);
  });

  test('POST /reply/audio sem arquivo responde 400', async () => {
    const res = await fetch(`${baseUrl}/reply/audio`, { method: 'POST' });
    assert.equal(res.status, 400);
  });

  test('GET /media/test.wav gera um tom PCM 16 kHz mono', async () => {
    const res = await fetch(`${baseUrl}/media/test.wav`);
    assert.equal(res.status, 200);
    assert.equal(res.headers.get('content-type'), 'audio/wav');

    const wav = Buffer.from(await res.arrayBuffer());
    assert.equal(wav.toString('ascii', 0, 4), 'RIFF');
    assert.equal(wav.readUInt16LE(22), 1, 'mono');
    assert.equal(wav.readUInt32LE(24), 16000, '16 kHz');
    assert.ok(wav.length > 1000, 'tem áudio');
  });

  test('QR code da Evolution v2 fica disponível para parear', async () => {
    const png = Buffer.from('fake-png');
    const res = await fetch(`${baseUrl}/webhook/whatsapp/qrcode-updated`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        event: 'qrcode-updated',
        instance: 'wabridge',
        data: { qrcode: { base64: png.toString('base64'), ref: 'REF_1' } },
      }),
    });
    assert.equal(res.status, 200);

    const img = await fetch(`${baseUrl}/qrcode.png`);
    assert.equal(img.status, 200);
    assert.equal(img.headers.get('content-type'), 'image/png');
    assert.deepEqual(Buffer.from(await img.arrayBuffer()), png);

    const page = await fetch(`${baseUrl}/qrcode`);
    assert.equal(page.status, 200);
    assert.ok((await page.text()).includes('data:image/png;base64,'));
  });
});

describe('fluxo completo do webhook', () => {
  test('payload da Evolution v2 (data é lista, evento na URL) vira chamada', async () => {
    const client = new WsClient(wsUrl);
    await client.ready();

    const res = await fetch(`${baseUrl}/webhook/whatsapp/messages-upsert`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        event: 'messages.upsert',
        instance: 'wabridge',
        data: [
          {
            key: { remoteJid: '5511999999999@s.whatsapp.net', fromMe: false, id: 'MSG_V2' },
            pushName: 'Maria',
            messageType: 'audio',
            message: { audioMessage: { mimetype: 'audio/ogg; codecs=opus', ptt: true } },
          },
        ],
      }),
    });
    assert.equal(res.status, 200);

    const push = await client.waitFor('NEW_VOICE_NOTE');
    assert.equal(push.senderNumber, '5511999999999');
    assert.equal(push.senderName, 'Maria');
    assert.equal(push.audioId, 'MSG_V2');
    assert.equal(push.audioUrl, 'http://test.local/media/MSG_V2.wav');

    client.close();
  });

  test('áudio permitido vira chamada no WebSocket e serve o wav', async () => {
    const client = new WsClient(wsUrl);
    await client.ready();

    await postWebhook(WEBHOOK);

    const push = await client.waitFor('NEW_VOICE_NOTE');
    assert.equal(push.senderNumber, '5511999999999');
    assert.equal(push.senderName, 'João da Silva');
    assert.equal(push.audioId, 'MSG_TEST_1');
    assert.equal(push.audioUrl, 'http://test.local/media/MSG_TEST_1.wav');

    // o wav existe no cache e é 16k mono
    const wav = fs.readFileSync(path.join(tmpDir, 'MSG_TEST_1.wav'));
    assert.equal(wav.toString('ascii', 0, 4), 'RIFF');
    assert.equal(wav.readUInt16LE(22), 1, 'mono');
    assert.equal(wav.readUInt32LE(24), 16000, '16 kHz');

    // ...e a pendência fica registrada
    const pending = await waitForPending('MSG_TEST_1');
    assert.equal(pending.senderNumber, '5511999999999');

    client.close();
  });

  test('remetente fora da lista é ignorado', async () => {
    const client = new WsClient(wsUrl);
    await client.ready();
    const before = fake.downloadCalls;

    await postWebhook({
      data: {
        key: { remoteJid: '5511777777777@s.whatsapp.net', id: 'MSG_BLOCKED' },
        message: { audio: { url: 'https://mmg/x' } },
      },
    });

    await wait(1500);
    assert.equal(client.events.filter((e) => e.type === 'NEW_VOICE_NOTE').length, 0);
    assert.equal(fake.downloadCalls, before, 'não baixou mídia do remetente bloqueado');
    client.close();
  });

  test('grupo é ignorado', async () => {
    const client = new WsClient(wsUrl);
    await client.ready();
    const before = fake.downloadCalls;

    await postWebhook({
      data: {
        key: { remoteJid: '12345-678@g.us', id: 'MSG_GROUP' },
        message: { audio: { url: 'https://mmg/x' } },
      },
    });

    await wait(1500);
    assert.equal(client.events.filter((e) => e.type === 'NEW_VOICE_NOTE').length, 0);
    assert.equal(fake.downloadCalls, before);
    client.close();
  });

  test('resposta gravada vira nota de voz (ptt) para o remetente', async () => {
    const form = new FormData();
    form.append('audioId', 'MSG_TEST_1');
    form.append('to', '5511999999999');
    form.append('file', new Blob([fs.readFileSync(SAMPLE)], { type: 'audio/ogg' }), 'reply.ogg');

    const res = await fetch(`${baseUrl}/reply/audio`, { method: 'POST', body: form });
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.ok, true);
    assert.equal(body.to, '5511999999999');

    assert.equal(fake.sent.length, 1);
    assert.equal(fake.sent[0].number, '5511999999999');
    assert.ok(fake.sent[0].bytes > 0, 'enviou áudio');

    // a pendência da conversa respondida é removida depois do envio
    const pending = await (await fetch(`${baseUrl}/pending`)).json();
    assert.equal(pending.find((p: any) => p.audioId === 'MSG_TEST_1'), undefined);
  });

  test('resposta rápida em texto vai para o remetente', async () => {
    const res = await fetch(`${baseUrl}/reply/text`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        audioId: 'MSG_TEST_1',
        to: '5511999999999',
        text: 'Não posso atender o telefone no momento.',
      }),
    });
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.ok, true);
    assert.equal(body.to, '5511999999999');

    assert.equal(fake.texts.length, 1);
    assert.equal(fake.texts[0].number, '5511999999999');
    assert.match(fake.texts[0].text, /Não posso atender/);
  });

  test('resposta rápida sem texto é recusada', async () => {
    const res = await fetch(`${baseUrl}/reply/text`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ audioId: 'MSG_TEST_1', to: '5511999999999' }),
    });
    assert.equal(res.status, 400);
  });

  test('sem destino nem pendência, o reply é recusado', async () => {
    const form = new FormData();
    form.append('audioId', 'MSG_INEXISTENTE');
    form.append('file', new Blob([fs.readFileSync(SAMPLE)], { type: 'audio/ogg' }), 'reply.ogg');

    const res = await fetch(`${baseUrl}/reply/audio`, { method: 'POST', body: form });
    assert.equal(res.status, 400);
  });
});
