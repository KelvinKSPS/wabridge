import os from 'node:os';
import path from 'node:path';

const int = (v: string | undefined, dflt: number) => {
  const n = v === undefined ? NaN : Number(v);
  return Number.isFinite(n) ? n : dflt;
};

/**
 * PUBLIC_HOST é o endereço que o relógio alcança: "http://192.168.0.10:8090".
 * Se ficar vazio, o servidor tenta adivinhar pelo IP local da interface.
 */
export const config = {
  port: int(process.env.PORT, 8090),
  bind: process.env.BIND ?? '0.0.0.0',
  publicHost: (process.env.PUBLIC_HOST ?? '').replace(/\/+$/, ''),

  /** Números autorizados (só dígitos). Ex.: "5511999999999,5511888888888" */
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
  /** Tempo que o wav de entrada fica em cache (ms). */
  mediaTtlMs: int(process.env.MEDIA_TTL_MS, 30 * 60 * 1000),
  /** Tamanho máximo do upload de resposta (bytes). */
  maxUploadBytes: int(process.env.MAX_UPLOAD_BYTES, 25 * 1024 * 1024),
};

/** Casa o número recebido com a lista de permitidos (ignora 9o dígito, código de país, etc.). */
export function isAllowed(rawNumber: string): boolean {
  if (config.allowedNumbers.length === 0) {
    console.warn('[config] ALLOWED_NUMBERS vazio: TODOS os remetentes serão aceitos.');
    return true;
  }
  const digits = rawNumber.replace(/\D/g, '');
  if (!digits) return false;
  return config.allowedNumbers.some((allowed) => {
    if (allowed === digits) return true;
    // permite comparar pelo final do número (ex.: 9988887777 casa com 55119988887777)
    const min = Math.min(allowed.length, digits.length);
    if (min < 8) return false;
    return allowed.slice(-min) === digits.slice(-min);
  });
}
