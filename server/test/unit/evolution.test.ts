import { strict as assert } from 'node:assert';
import { test, describe, beforeEach, afterEach } from 'node:test';
import http from 'node:http';
import { EvolutionClient } from '../../src/evolution';

interface Call {
  path: string;
  body: any;
  apiKey?: string;
}

/** Evolution falsa: responde conforme o mapa `routes` (caminho -> resposta). */
function fakeEvolution(routes: Record<string, { status: number; json?: any }>) {
  const calls: Call[] = [];
  const server = http.createServer((req, res) => {
    let raw = '';
    req.on('data', (c) => (raw += c));
    req.on('end', () => {
      let body: any = {};
      try {
        body = raw ? JSON.parse(raw) : {};
      } catch {
        body = raw;
      }
      calls.push({ path: req.url ?? '', body, apiKey: req.headers.apikey as string });

      const key = Object.keys(routes).find((k) => (req.url ?? '').startsWith(k));
      const reply = key ? routes[key] : { status: 404, json: { status: 404 } };
      res.setHeader('Content-Type', 'application/json');
      res.statusCode = reply.status;
      res.end(JSON.stringify(reply.json ?? {}));
    });
  });

  return new Promise<{ url: string; calls: Call[]; close: () => Promise<void> }>((resolve) => {
    server.listen(0, '127.0.0.1', () => {
      const port = (server.address() as any).port;
      resolve({
        url: `http://127.0.0.1:${port}`,
        calls,
        close: () => new Promise<void>((done) => server.close(() => done())),
      });
    });
  });
}

const AUDIO_B64 = Buffer.from('som-opus-de-teste').toString('base64');

describe('EvolutionClient — Evolution API v2', () => {
  let fake: Awaited<ReturnType<typeof fakeEvolution>>;
  let client: EvolutionClient;

  beforeEach(async () => {
    fake = await fakeEvolution({});
    client = new EvolutionClient(fake.url, 'wabridge', 'chave-teste');
  });

  afterEach(async () => {
    await fake.close();
  });

  test('baixa a mídia pela mensagem inteira (getBase64FromMediaMessage)', async () => {
    fake.calls.length = 0;
    const srv = await fakeEvolution({
      '/chat/getBase64FromMediaMessage': {
        status: 201,
        json: {
          status: 201,
          response: {
            mediaType: 'audio',
            fileName: 'nota.ogg',
            mimetype: 'audio/ogg; codecs=opus',
            base64: AUDIO_B64,
          },
        },
      },
    });

    try {
      const c = new EvolutionClient(srv.url, 'wabridge', 'chave-teste');
      const media = await c.downloadMedia({
        message: { key: { id: 'M1' }, message: { audioMessage: { mimetype: 'audio/ogg' } } },
        mimetype: 'audio/ogg',
      });

      assert.equal(srv.calls[0].path, '/chat/getBase64FromMediaMessage/wabridge');
      assert.equal(srv.calls[0].body.message.key.id, 'M1');
      assert.equal(srv.calls[0].apiKey, 'chave-teste');
      assert.equal(media.data.toString(), 'som-opus-de-teste');
      assert.equal(media.extension, 'ogg');
      assert.equal(media.mimetype, 'audio/ogg; codecs=opus');
    } finally {
      await srv.close();
    }
  });

  test('cai para a rota v1 quando a v2 não existe (404)', async () => {
    const srv = await fakeEvolution({
      '/chat/downloadMedia': { status: 200, json: { status: 200, mimetype: 'audio/ogg', base64: AUDIO_B64 } },
    });

    try {
      const c = new EvolutionClient(srv.url, 'wabridge', 'chave-teste');
      const media = await c.downloadMedia({
        message: { key: { id: 'M1' }, message: { audio: { url: 'https://x/y' } } },
        audioUrl: 'https://x/y',
        mimetype: 'audio/ogg',
      });

      assert.equal(srv.calls[0].path, '/chat/getBase64FromMediaMessage/wabridge');
      assert.equal(srv.calls[1].path, '/chat/downloadMedia/wabridge');
      assert.equal(srv.calls[1].body.mediaLink, 'https://x/y');
      assert.equal(media.data.toString(), 'som-opus-de-teste');
    } finally {
      await srv.close();
    }
  });

  test('envia nota de voz em base64 pela rota v2', async () => {
    const srv = await fakeEvolution({
      '/message/sendWhatsAppAudio': { status: 201, json: { status: 201, response: { key: { id: 'X' } } } },
    });

    try {
      const c = new EvolutionClient(srv.url, 'wabridge', 'chave-teste');
      await c.sendVoiceNote('5511999999999', Buffer.from('nota'));

      assert.equal(srv.calls[0].path, '/message/sendWhatsAppAudio/wabridge');
      assert.equal(srv.calls[0].body.number, '5511999999999');
      assert.equal(srv.calls[0].body.audio, Buffer.from('nota').toString('base64'));
      assert.equal(srv.calls[0].body.ptt, true);
      assert.equal(srv.calls[0].apiKey, 'chave-teste');
    } finally {
      await srv.close();
    }
  });

  test('cai para /chat/sendAudio quando a v2 não existe', async () => {
    const srv = await fakeEvolution({
      '/chat/sendAudio': { status: 200, json: { status: 200 } },
    });

    try {
      const c = new EvolutionClient(srv.url, 'wabridge', 'chave-teste');
      await c.sendVoiceNote('5511999999999', Buffer.from('nota'));

      assert.equal(srv.calls[0].path, '/message/sendWhatsAppAudio/wabridge');
      assert.equal(srv.calls[1].path, '/chat/sendAudio/wabridge');
      assert.ok(String(srv.calls[1].body.audio).startsWith('data:audio/ogg;base64,'));
    } finally {
      await srv.close();
    }
  });

  test('erro claro quando não há mídia nem mensagem', async () => {
    await assert.rejects(
      () => client.downloadMedia({ mimetype: 'audio/ogg' }),
      /sem base64 nem link/,
    );
  });
});
