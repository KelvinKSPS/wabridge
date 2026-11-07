import ffmpeg from 'fluent-ffmpeg';
import fs from 'node:fs';
import path from 'node:path';
import { config } from './config';

ffmpeg.setFfmpegPath(config.ffmpegPath);

/**
 * Converte para PCM 16kHz mono — formato aceito pelo Bluetooth SCO do Android.
 */
export function toWav16k(input: string, output: string): Promise<string> {
  return new Promise((resolve, reject) => {
    ffmpeg(input)
      .audioCodec('pcm_s16le')
      .audioFrequency(16000)
      .audioChannels(1)
      .format('wav')
      .on('end', () => resolve(output))
      .on('error', (err) => reject(new Error(`ffmpeg->wav: ${err.message}`)))
      .save(output);
  });
}

/**
 * Converte a gravação do relógio (m4a/wav) em Opus (.ogg) para envio como nota de voz.
 */
export function toOggOpus(input: string, output: string): Promise<string> {
  return new Promise((resolve, reject) => {
    ffmpeg(input)
      .audioCodec('libopus')
      .audioBitrate(32)
      .audioFrequency(16000)
      .audioChannels(1)
      .format('ogg')
      .on('end', () => resolve(output))
      .on('error', (err) => reject(new Error(`ffmpeg->ogg: ${err.message}`)))
      .save(output);
  });
}

export function ensureMediaDir(): string {
  fs.mkdirSync(config.mediaDir, { recursive: true });
  return config.mediaDir;
}

export function mediaPath(fileName: string): string {
  return path.join(config.mediaDir, fileName);
}

/** Remove o arquivo depois do TTL (não segura o event loop). */
export function scheduleCleanup(fileName: string, ttlMs = config.mediaTtlMs): void {
  const t = setTimeout(() => {
    fs.promises.unlink(mediaPath(fileName)).catch(() => undefined);
  }, ttlMs);
  t.unref?.();
}
