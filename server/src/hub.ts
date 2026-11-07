import { WebSocketServer, WebSocket } from 'ws';
import type { Server } from 'node:http';
import type { CallPayload } from './types';

/**
 * Hub de WebSocket: mantém os relógios (apps Android) conectados e
 * distribui os eventos de áudio recebido.
 */
export class Hub {
  private wss: WebSocketServer;
  private clients = new Set<WebSocket>();

  constructor(server: Server, path = '/ws') {
    this.wss = new WebSocketServer({ server, path });
    this.wss.on('connection', (ws) => {
      this.clients.add(ws);
      ws.send(JSON.stringify({ type: 'HELLO', clients: this.clients.size }));
      ws.on('close', () => this.clients.delete(ws));
      ws.on('error', () => this.clients.delete(ws));
      ws.on('message', (raw) => {
        // PING/PONG manual do app
        if (raw.toString().trim() === 'ping') ws.send('pong');
      });
    });
  }

  get size(): number {
    return this.clients.size;
  }

  broadcast(payload: CallPayload | Record<string, unknown>): void {
    const data = JSON.stringify(payload);
    for (const ws of this.clients) {
      if (ws.readyState === WebSocket.OPEN) ws.send(data);
    }
  }

  /** Encerra o hub (usado no shutdown e nos testes). */
  close(): void {
    for (const ws of this.clients) {
      try {
        ws.close(1001, 'bye');
      } catch {
        /* ignora */
      }
    }
    this.clients.clear();
    this.wss.close();
  }
}
