import type { IncomingAudio } from './types';

function asObject(v: unknown): any {
  return v && typeof v === 'object' ? v : null;
}

/** Procura a chave `key` do formato messages.upsert em qualquer nível do payload. */
export function findKey(obj: any): any {
  if (!obj) return null;
  if (obj.key && typeof obj.key === 'object') return obj.key;
  if (obj.data?.key) return obj.data.key;
  for (const v of Object.values(obj)) {
    const o = asObject(v);
    if (!o) continue;
    const r = findKey(o);
    if (r) return r;
  }
  return null;
}

/**
 * Procura o áudio em qualquer formato aceito pela Evolution API:
 * `message.audio`, `message.audioMessage` ou `document` (PTT com mime de áudio).
 *
 * Na v2 não existe link público: o áudio vem dentro da mensagem e é baixado
 * pela Evolution a partir dela. Nesse caso `url` volta vazio.
 */
export function findAudio(obj: any): { url: string; mimetype: string } | null {
  if (!obj || typeof obj !== 'object') return null;
  if (Array.isArray(obj)) {
    for (const item of obj) {
      const r = findAudio(item);
      if (r) return r;
    }
    return null;
  }
  for (const key of ['audio', 'audioMessage', 'document']) {
    const v = obj[key];
    if (!v || typeof v !== 'object') continue;
    const mimetype = String(v.mimetype ?? v.mimeType ?? '');
    if (mimetype && !mimetype.startsWith('audio/')) continue;
    if (key === 'document' && !mimetype.startsWith('audio/')) continue;
    const url = v.url ?? v.link ?? v.mediaUrl ?? v.downloadUrl;
    if (typeof url === 'string' && url) return { url, mimetype: mimetype || 'audio/ogg' };
    // v2: áudio sem link — a mensagem inteira é usada para baixar.
    if (key !== 'document') return { url: '', mimetype: mimetype || 'audio/ogg' };
  }
  for (const v of Object.values(obj)) {
    const r = findAudio(asObject(v));
    if (r) return r;
  }
  return null;
}

/**
 * Procura o elemento de mensagem da Evolution (v2): objeto com `key` e
 * `message`. É ele que a API v2 exige para devolver o áudio em base64.
 */
export function findMessageElement(obj: any): any | null {
  if (!obj || typeof obj !== 'object') return null;
  if (Array.isArray(obj)) {
    for (const item of obj) {
      const r = findMessageElement(item);
      if (r) return r;
    }
    return null;
  }
  if (obj.key && typeof obj.key === 'object' && obj.message && typeof obj.message === 'object') {
    return obj;
  }
  for (const v of Object.values(obj)) {
    const r = findMessageElement(v);
    if (r) return r;
  }
  return null;
}

/** Normaliza o payload do webhook em um evento de áudio. */
export function normalize(body: any): IncomingAudio | null {
  const data = body?.data ?? body ?? {};
  const key = findKey(body) ?? {};
  const element = findMessageElement(body);
  const audio = (element && findAudio(element.message)) ?? findAudio(data);
  if (!audio) return null;

  const jid = String(key.remoteJid ?? data.remoteJid ?? element?.key?.remoteJid ?? '');
  const number = jid.split('@')[0].replace(/\D/g, '');
  if (!number) return null;

  const fromMe = Boolean(key.fromMe ?? data.fromMe ?? body?.fromMe ?? false);
  const messageId = String(key.id ?? data.id ?? `msg_${Date.now()}`);
  const pushName = String(element?.pushName ?? data.pushName ?? body?.pushName ?? data.notifyName ?? '');

  return {
    messageId,
    fromMe,
    number,
    pushName,
    audioUrl: audio.url,
    mimetype: audio.mimetype,
    remoteJid: jid,
    message: element ?? undefined,
  };
}

/** true se o jid pertence a um grupo. */
export function isGroup(jid: string): boolean {
  return jid.endsWith('@g.us');
}
