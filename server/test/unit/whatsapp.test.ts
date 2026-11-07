import { strict as assert } from 'node:assert';
import { test, describe } from 'node:test';
import { normalize, findAudio, findKey, isGroup } from '../../src/whatsapp';

describe('normalize — payload messages.upsert da Evolution API', () => {
  test('formato padrão (data.key + data.message.audio)', () => {
    const evt = normalize({
      instance: 'wabridge',
      data: {
        key: { remoteJid: '5511999999999@s.whatsapp.net', fromMe: false, id: 'MSG1' },
        pushName: 'João',
        message: { audio: { mimetype: 'audio/ogg; codecs=opus', url: 'https://x/y' } },
      },
    });
    assert.ok(evt);
    assert.equal(evt!.number, '5511999999999');
    assert.equal(evt!.messageId, 'MSG1');
    assert.equal(evt!.pushName, 'João');
    assert.equal(evt!.fromMe, false);
    assert.equal(evt!.audioUrl, 'https://x/y');
  });

  test('formato antigo (message.audio na raiz)', () => {
    const evt = normalize({
      key: { remoteJid: '5511888888888@s.whatsapp.net', fromMe: false, id: 'MSG2' },
      message: { audio: { mimetype: 'audio/ogg', url: 'https://x/z' } },
    });
    assert.equal(evt!.number, '5511888888888');
    assert.equal(evt!.messageId, 'MSG2');
  });

  test('formato audioMessage', () => {
    const evt = normalize({
      data: {
        key: { remoteJid: '5511777777777@s.whatsapp.net', id: 'MSG3' },
        message: { audioMessage: { url: 'https://x/w' } },
      },
    });
    assert.equal(evt!.number, '5511777777777');
    assert.equal(evt!.audioUrl, 'https://x/w');
  });

  test('formato document com mime de áudio (PTT)', () => {
    const evt = normalize({
      data: {
        key: { remoteJid: '5511666666666@s.whatsapp.net', id: 'MSG4' },
        message: { document: { mimetype: 'audio/ogg; codecs=opus', url: 'https://x/d' } },
      },
    });
    assert.equal(evt!.audioUrl, 'https://x/d');
    assert.equal(evt!.mimetype, 'audio/ogg; codecs=opus');
  });

  test('document que não é áudio é ignorado', () => {
    const evt = normalize({
      data: {
        key: { remoteJid: '5511555555555@s.whatsapp.net', id: 'MSG5' },
        message: { document: { mimetype: 'application/pdf', url: 'https://x/p' } },
      },
    });
    assert.equal(evt, null);
  });

  test('mensagem sem áudio devolve null', () => {
    assert.equal(normalize({ data: { key: { id: 'M6' }, message: { text: { body: 'oi' } } } }), null);
  });

  test('fromMe é respeitado', () => {
    const evt = normalize({
      data: {
        key: { remoteJid: '5511444444444@s.whatsapp.net', fromMe: true, id: 'M7' },
        message: { audio: { url: 'https://x/a' } },
      },
    });
    assert.equal(evt!.fromMe, true);
  });

  test('jid sem número devolve null', () => {
    assert.equal(normalize({ data: { key: { remoteJid: '@s.whatsapp.net' }, message: { audio: { url: 'u' } } } }), null);
  });
});

describe('findAudio', () => {
  test('acha audio em lista de mensagens', () => {
    const r = findAudio({ messages: [{ audio: { url: 'https://x/1' } }] });
    assert.equal(r?.url, 'https://x/1');
  });

  test('null quando não há mídia', () => {
    assert.equal(findAudio({ text: 'oi' }), null);
    assert.equal(findAudio(null), null);
  });
});

describe('findKey', () => {
  test('acha a key em qualquer nível', () => {
    assert.equal(findKey({ deep: { key: { id: 'K' } } })?.id, 'K');
    assert.equal(findKey({ nope: 1 }), null);
  });
});

describe('isGroup', () => {
  test('g.us é grupo', () => {
    assert.equal(isGroup('123-456@g.us'), true);
    assert.equal(isGroup('5511999999999@s.whatsapp.net'), false);
  });
});
