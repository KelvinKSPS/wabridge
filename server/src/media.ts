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

/**
 * Gera um wav de teste (tom) no formato exigido pelo SCO.
 * Escrito direto em Node — sem depender do ffmpeg — para o tom de teste
 * da chamada no relógio.
 */
export function generateToneWav(output: string, frequency = 440, seconds = 2): string {
  const sampleRate = 16000;
  const samples = Math.floor(sampleRate * seconds);
  const data = Buffer.alloc(samples * 2);

  for (let i = 0; i < samples; i++) {
    // desce de 0.8 para 0 nos últimos 10%: evita estalo no fim do tom
    const fade = i > samples * 0.9 ? (samples - i) / (samples * 0.1) : 1;
    const value = Math.sin((2 * Math.PI * frequency * i) / sampleRate) * 0.8 * fade;
    data.writeInt16LE(Math.round(value * 32767), i * 2);
  }

  const header = Buffer.alloc(44);
  header.write('RIFF', 0, 'ascii');
  header.writeUInt32LE(36 + data.length, 4);
  header.write('WAVE', 8, 'ascii');
  header.write('fmt ', 12, 'ascii');
  header.writeUInt32LE(16, 16); // tamanho do bloco fmt
  header.writeUInt16LE(1, 20); // PCM
  header.writeUInt16LE(1, 22); // mono
  header.writeUInt32LE(sampleRate, 24);
  header.writeUInt32LE(sampleRate * 2, 28); // byte rate
  header.writeUInt16LE(2, 32); // block align
  header.writeUInt16LE(16, 34); // bits por amostra
  header.write('data', 36, 'ascii');
  header.writeUInt32LE(data.length, 40);

  fs.writeFileSync(output, Buffer.concat([header, data]));
  return output;
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
