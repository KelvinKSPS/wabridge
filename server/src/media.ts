import ffmpeg from 'fluent-ffmpeg';
import fs from 'node:fs';
import path from 'node:path';
import { config } from './config';

ffmpeg.setFfmpegPath(config.ffmpegPath);

/**
 * Converts to PCM 16kHz mono, the format accepted by Android's Bluetooth SCO.
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
 * Audio duration in seconds (rounded). 0 if it cannot be measured.
 */
export function probeDuration(input: string): Promise<number> {
  return new Promise((resolve) => {
    ffmpeg.ffprobe(input, (err, data) => {
      if (err) {
        resolve(0);
        return;
      }
      const seconds = Number(data?.format?.duration);
      resolve(Number.isFinite(seconds) ? Math.round(seconds) : 0);
    });
  });
}

/**
 * Converts the watch recording (m4a/wav) to Opus (.ogg) to send as a voice note.
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
 * Generates a test wav (tone) in the format required by SCO.
 * Written directly in Node, without depending on ffmpeg, for the call's test
 * tone on the watch.
 */
export function generateToneWav(output: string, frequency = 440, seconds = 2): string {
  const sampleRate = 16000;
  const samples = Math.floor(sampleRate * seconds);
  const data = Buffer.alloc(samples * 2);

  for (let i = 0; i < samples; i++) {
    // Fade from 0.8 to 0 over the final 10% to prevent a click at the end of the tone
    const fade = i > samples * 0.9 ? (samples - i) / (samples * 0.1) : 1;
    const value = Math.sin((2 * Math.PI * frequency * i) / sampleRate) * 0.8 * fade;
    data.writeInt16LE(Math.round(value * 32767), i * 2);
  }

  const header = Buffer.alloc(44);
  header.write('RIFF', 0, 'ascii');
  header.writeUInt32LE(36 + data.length, 4);
  header.write('WAVE', 8, 'ascii');
  header.write('fmt ', 12, 'ascii');
  header.writeUInt32LE(16, 16); // fmt block size
  header.writeUInt16LE(1, 20); // PCM
  header.writeUInt16LE(1, 22); // mono
  header.writeUInt32LE(sampleRate, 24);
  header.writeUInt32LE(sampleRate * 2, 28); // byte rate
  header.writeUInt16LE(2, 32); // block align
  header.writeUInt16LE(16, 34); // bits per sample
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

/** Removes the file after the TTL (does not hold the event loop). */
export function scheduleCleanup(fileName: string, ttlMs = config.mediaTtlMs): void {
  const t = setTimeout(() => {
    fs.promises.unlink(mediaPath(fileName)).catch(() => undefined);
  }, ttlMs);
  t.unref?.();
}
