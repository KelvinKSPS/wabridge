// Teste ponta a ponta da ponte (sem WhatsApp real).
//   1) node test/mock-evolution.js 8081
//   2) PORT=8097 EVOLUTION_API_URL=http://127.0.0.1:8081 node dist/index.js
//   3) node test/e2e.js
const fs = require('fs');
const http = require('http');
const path = require('path');
const WebSocket = require('../node_modules/ws');

const BRIDGE = process.env.BRIDGE || 'http://127.0.0.1:8097';

function postJson(p, body) {
  return new Promise((resolve, reject) => {
    const data = JSON.stringify(body);
    const req = http.request(
      BRIDGE + p,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(data) },
      },
      (res) => {
        let out = '';
        res.on('data', (c) => (out += c));
        res.on('end', () => resolve({ status: res.statusCode, body: out }));
      },
    );
    req.on('error', reject);
    req.write(data);
    req.end();
  });
}

function postFile(p, filePath, fields) {
  return new Promise((resolve, reject) => {
    const boundary = '----wabridge' + Date.now();
    const head = [];
    for (const [k, v] of Object.entries(fields)) {
      head.push(`--${boundary}\r\nContent-Disposition: form-data; name="${k}"\r\n\r\n${v}\r\n`);
    }
    head.push(
      `--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="reply.ogg"` +
        `\r\nContent-Type: audio/ogg\r\n\r\n`,
    );
    const file = fs.readFileSync(filePath);
    const body = Buffer.concat([Buffer.from(head.join('')), file, Buffer.from(`\r\n--${boundary}--\r\n`)]);
    const req = http.request(
      BRIDGE + p,
      {
        method: 'POST',
        headers: {
          'Content-Type': `multipart/form-data; boundary=${boundary}`,
          'Content-Length': body.length,
        },
      },
      (res) => {
        let out = '';
        res.on('data', (c) => (out += c));
        res.on('end', () => resolve({ status: res.statusCode, body: out }));
      },
    );
    req.on('error', reject);
    req.write(body);
    req.end();
  });
}

(async () => {
  const ws = new WebSocket(BRIDGE.replace(/^http/, 'ws') + '/ws');
  const push = new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error('timeout esperando push no WS')), 20000);
    ws.on('message', (raw) => {
      const msg = JSON.parse(raw.toString());
      if (msg.type === 'NEW_VOICE_NOTE') {
        clearTimeout(t);
        resolve(msg);
      }
    });
  });

  await new Promise((r) => ws.on('open', r));

  const wh = await postJson('/webhook/whatsapp', {
    instance: 'wabridge',
    data: {
      key: { remoteJid: '5511999999999@s.whatsapp.net', fromMe: false, id: 'MSG_ABC_123' },
      pushName: 'João da Silva',
      message: { audio: { mimetype: 'audio/ogg; codecs=opus', url: 'https://mmg.whatsapp.net/media/xyz' } },
    },
  });
  console.log('webhook  ->', wh.status, wh.body);

  const evt = await push;
  console.log('ws push  ->', JSON.stringify(evt));

  const media = await new Promise((resolve, reject) => {
    http
      .get(evt.audioUrl, (res) => {
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () => resolve({ status: res.statusCode, len: Buffer.concat(chunks).length }));
      })
      .on('error', reject);
  });
  console.log('media    ->', media.status, media.len, 'bytes');

  const rep = await postFile('/reply/audio', path.join(__dirname, 'sample.ogg'), {
    audioId: evt.audioId,
    to: evt.senderNumber,
  });
  console.log('reply    ->', rep.status, rep.body);

  const recebido = path.join(__dirname, 'recebido.ogg');
  console.log('mock recebeu:', fs.existsSync(recebido) ? fs.statSync(recebido).size + ' bytes' : 'NADA');

  ws.close();
  process.exit(0);
})().catch((e) => {
  console.error('FALHOU:', e.message);
  process.exit(1);
});
