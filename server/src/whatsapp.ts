import type { IncomingAudio } from './types';

function asObject(v: unknown): any {
  return v && typeof v === 'object' ? v : null;
}

/** Finds the `key` of the messages.upsert format at any level of the payload. */
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
 * Finds the audio in any format accepted by the Evolution API:
 * `message.audio`, `message.audioMessage` or `document` (PTT with an audio mime type).
 *
 * In v2 there is no public link: the audio comes inside the message and is downloaded
 * by Evolution from it. In that case `url` comes back empty.
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
    // v2: audio without a link; the whole message is used to download it.
    if (key !== 'document') return { url: '', mimetype: mimetype || 'audio/ogg' };
  }
  for (const v of Object.values(obj)) {
    const r = findAudio(asObject(v));
    if (r) return r;
  }
  return null;
}

/**
 * Finds the Evolution message element (v2): object with `key` and
 * `message`. The v2 API requires it to return the audio as base64.
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

/** Normalizes the webhook payload into an audio event. */
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

/** true if the jid belongs to a group. */
export function isGroup(jid: string): boolean {
  return jid.endsWith('@g.us');
}

/**
 * true if the jid is a private conversation (a person).
 * Anything else is ignored by the bridge:
 * - `@g.us` group
 * - `@newsletter` WhatsApp channel
 * - `@broadcast` broadcast
 * - `@lid` privacy identity (not a phone number)
 */
export function isPrivateChat(jid: string): boolean {
  return jid.endsWith('@s.whatsapp.net') || jid.endsWith('@c.us');
}
