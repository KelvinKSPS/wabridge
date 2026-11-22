import os from 'node:os';
import path from 'node:path';

const int = (v: string | undefined, dflt: number) => {
  const n = v === undefined ? NaN : Number(v);
  return Number.isFinite(n) ? n : dflt;
};

/**
 * PUBLIC_HOST is the address the watch can reach: "http://192.168.0.10:8090".
 * If left empty, the server tries to guess it from the interface's local IP.
 */
export const config = {
  port: int(process.env.PORT, 8090),
  bind: process.env.BIND ?? '0.0.0.0',
  publicHost: (process.env.PUBLIC_HOST ?? '').replace(/\/+$/, ''),

  /** Authorized numbers (digits only). E.g.: "5511999999999,5511888888888" */
  allowedNumbers: (process.env.ALLOWED_NUMBERS ?? '')
    .split(',')
    .map((s) => s.replace(/\D/g, ''))
    .filter(Boolean),

  evolution: {
    baseUrl: (process.env.EVOLUTION_API_URL ?? 'http://127.0.0.1:8080').replace(/\/+$/, ''),
    instance: process.env.EVOLUTION_INSTANCE ?? 'wabridge',
    apiKey: process.env.EVOLUTION_API_KEY ?? '',
  },

  mediaDir: process.env.MEDIA_DIR ?? path.join(os.tmpdir(), 'wabridge-media'),
  ffmpegPath: process.env.FFMPEG_PATH ?? 'ffmpeg',
  /** How long the incoming wav stays in cache (ms). */
  mediaTtlMs: int(process.env.MEDIA_TTL_MS, 30 * 60 * 1000),
  /** Maximum size of the reply upload (bytes). */
  maxUploadBytes: int(process.env.MAX_UPLOAD_BYTES, 25 * 1024 * 1024),
};

/** Matches the received number against the allowed list (ignores the 9th digit, country code, etc.). */
export function isAllowed(rawNumber: string): boolean {
  if (config.allowedNumbers.length === 0) {
    console.warn('[config] ALLOWED_NUMBERS vazio: TODOS os remetentes serão aceitos.');
    return true;
  }
  const digits = rawNumber.replace(/\D/g, '');
  if (!digits) return false;
  return config.allowedNumbers.some((allowed) => {
    if (allowed === digits) return true;
    // allows matching by the end of the number (e.g. 9988887777 matches 55119988887777)
    const min = Math.min(allowed.length, digits.length);
    if (min < 8) return false;
    return allowed.slice(-min) === digits.slice(-min);
  });
}
