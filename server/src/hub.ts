import { WebSocketServer, WebSocket } from 'ws';
import type { Server } from 'node:http';
import type { CallPayload } from './types';

/**
 * WebSocket hub: keeps the watches (Android apps) connected and
 * distributes the received audio events.
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
        // Manual PING/PONG from the app
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

  /** Shuts down the hub (used on shutdown and in tests). */
  close(): void {
    for (const ws of this.clients) {
      try {
        ws.close(1001, 'bye');
      } catch {
        /* ignored */
      }
    }
    this.clients.clear();
    this.wss.close();
  }
}
