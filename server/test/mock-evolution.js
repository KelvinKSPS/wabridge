// Evolution API falsa para testar a ponte sem WhatsApp real.
//   node test/mock-evolution.js [porta]
// Requer um sample.ogg em test/sample.ogg (veja test/make-sample.sh).
const http = require('http');
const fs = require('fs');
const path = require('path');

const port = Number(process.argv[2] || 8081);
const sample = path.join(__dirname, 'sample.ogg');

if (!fs.existsSync(sample)) {
  console.error('[mock] faltou test/sample.ogg — rode: bash test/make-sample.sh');
  process.exit(1);
}
const opus = fs.readFileSync(sample);

http
  .createServer((req, res) => {
    let body = '';
    req.on('data', (c) => (body += c));
    req.on('end', () => {
      const json = body ? JSON.parse(body) : {};
      console.log(`[mock] ${req.method} ${req.url}`);

      if (req.url.startsWith('/chat/downloadMedia')) {
        res.setHeader('Content-Type', 'application/json');
        res.end(
          JSON.stringify({
            status: 200,
            mimetype: 'audio/ogg; codecs=opus',
            base64: opus.toString('base64'),
          }),
        );
        return;
      }

      if (req.url.startsWith('/chat/sendAudio') || req.url.startsWith('/chat/sendMedia')) {
        const raw = json.audio || json.file || '';
        const b64 = raw.includes(',') ? raw.slice(raw.indexOf(',') + 1) : raw;
        const buf = Buffer.from(b64, 'base64');
        fs.writeFileSync(path.join(__dirname, 'recebido.ogg'), buf);
        console.log(`[mock] nota de voz ptt=${json.ptt} para ${json.number} (${buf.length} bytes)`);
        res.setHeader('Content-Type', 'application/json');
        res.end(JSON.stringify({ status: 200, key: { id: 'MOCK123' } }));
        return;
      }

      res.statusCode = 404;
      res.end('{}');
    });
  })
  .listen(port, () => console.log(`[mock] Evolution API falsa em :${port}`));
