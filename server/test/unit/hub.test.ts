import { strict as assert } from 'node:assert';
import { test, describe, beforeEach, afterEach } from 'node:test';
import http from 'node:http';
import WebSocket from 'ws';
import { Hub } from '../../src/hub';

describe('Hub de WebSocket', () => {
  let server: http.Server;
  let hub: Hub;
  let url: string;

  beforeEach(async () => {
    server = http.createServer();
    hub = new Hub(server, '/ws');
    await new Promise<void>((r) => server.listen(0, '127.0.0.1', r));
    const port = (server.address() as { port: number }).port;
    url = `ws://127.0.0.1:${port}/ws`;
  });

  afterEach(async () => {
    hub.close();
    server.closeAllConnections?.();
    await new Promise<void>((r) => server.close(() => r()));
  });

  /** Abre um cliente e devolve as mensagens recebidas. */
  async function client(): Promise<{ ws: WebSocket; messages: any[]; raw: string[] }> {
    const ws = new WebSocket(url);
    const messages: any[] = [];
    const raw: string[] = [];
    ws.on('message', (data) => {
      const text = data.toString();
      raw.push(text);
      try {
        messages.push(JSON.parse(text));
      } catch {
        /* resposta em texto puro (pong) */
      }
    });
    await new Promise<void>((r) => ws.on('open', r));
    return { ws, messages, raw };
  }

  const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));

  test('cliente conectado recebe HELLO', async () => {
    const { messages } = await client();
    await wait(150);
    assert.equal(messages.length, 1);
    assert.equal(messages[0].type, 'HELLO');
    assert.equal(messages[0].clients, 1);
  });

  test('broadcast chega em todos os clientes conectados', async () => {
    const a = await client();
    const b = await client();
    await wait(150);

    hub.broadcast({ type: 'NEW_VOICE_NOTE', audioId: 'A1' });
    await wait(200);

    assert.equal(a.messages.filter((m) => m.type === 'NEW_VOICE_NOTE').length, 1);
    assert.equal(b.messages.filter((m) => m.type === 'NEW_VOICE_NOTE').length, 1);
    assert.equal(hub.size, 2);

    a.ws.close();
    b.ws.close();
  });

  test('cliente que sai é removido do hub', async () => {
    const { ws } = await client();
    await wait(150);
    assert.equal(hub.size, 1);

    ws.close();
    await wait(300);
    assert.equal(hub.size, 0);
  });

  test('ping do app vira pong', async () => {
    const { ws, raw } = await client();
    await wait(150);

    ws.send('ping');
    await wait(200);
    assert.equal(raw.filter((t) => t === 'pong').length, 1);

    ws.close();
  });
});
