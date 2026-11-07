import { strict as assert } from 'node:assert';
import { test, describe } from 'node:test';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { toWav16k, toOggOpus } from '../../src/media';

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'wabridge-test-'));

/** Lê o cabeçalho de um WAV canônico. */
function readWav(file: string) {
  const b = fs.readFileSync(file);
  assert.equal(b.toString('ascii', 0, 4), 'RIFF');
  assert.equal(b.toString('ascii', 8, 12), 'WAVE');
  return {
    channels: b.readUInt16LE(22),
    sampleRate: b.readUInt32LE(24),
    bitsPerSample: b.readUInt16LE(34),
    dataSize: b.readUInt32LE(40),
  };
}

/** Procura o codec de um container Ogg (vorbis/opus identification header). */
function readOggCodec(file: string): string {
  const b = fs.readFileSync(file);
  const head = b.toString('latin1', 0, 64);
  if (head.includes('OpusHead')) return 'opus';
  if (head.includes('vorbis')) return 'vorbis';
  return 'desconhecido';
}

describe('conversão de mídia (ffmpeg)', () => {
  test('opus -> wav PCM 16k mono', async () => {
    const src = path.join(__dirname, '..', 'sample.ogg');
    const out = path.join(tmp, 'out.wav');
    await toWav16k(src, out);

    const wav = readWav(out);
    assert.equal(wav.channels, 1, 'mono');
    assert.equal(wav.sampleRate, 16000, '16 kHz');
    assert.equal(wav.bitsPerSample, 16, 'PCM 16 bits');
    assert.ok(wav.dataSize > 0, 'tem dados de áudio');
  });

  test('opus -> ogg opus (nota de voz)', async () => {
    const src = path.join(__dirname, '..', 'sample.ogg');
    const out = path.join(tmp, 'out.ogg');
    await toOggOpus(src, out);

    assert.equal(readOggCodec(out), 'opus');
    assert.ok(fs.statSync(out).size > 0);
  });

  test('wav -> ogg opus', async () => {
    const src = path.join(__dirname, '..', 'sample.ogg');
    const wav = path.join(tmp, 'mid.wav');
    const out = path.join(tmp, 'from-wav.ogg');
    await toWav16k(src, wav);
    await toOggOpus(wav, out);
    assert.equal(readOggCodec(out), 'opus');
  });

  test('entrada inválida rejeita com erro', async () => {
    const bogus = path.join(tmp, 'bogus.ogg');
    fs.writeFileSync(bogus, 'não é áudio');
    await assert.rejects(() => toWav16k(bogus, path.join(tmp, 'nope.wav')));
  });
});
