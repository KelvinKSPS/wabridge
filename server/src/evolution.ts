import axios, { AxiosInstance } from 'axios';
import { config } from './config';

export interface DownloadedMedia {
  data: Buffer;
  extension: string;
  mimetype: string;
}

const EXT_BY_MIME: Record<string, string> = {
  'audio/ogg': 'ogg',
  'audio/ogg; codecs=opus': 'ogg',
  'audio/opus': 'opus',
  'audio/mpeg': 'mp3',
  'audio/mp4': 'm4a',
  'audio/aac': 'aac',
  'audio/wav': 'wav',
  'audio/x-wav': 'wav',
  'audio/mpeg; codecs=opus': 'ogg',
};

/**
 * Cliente da Evolution API. Só o que a ponte precisa: baixar a mídia
 * recebida e enviar nota de voz.
 *
 * - v2 (Evolution API 2.x): a mídia é baixada passando a mensagem
 * inteira para `getBase64FromMediaMessage`, e a nota de voz vai como base64
 * em `message/sendWhatsAppAudio`. A v1 continua suportada como reserva
 * (link público + `/chat/sendAudio`).
 */
export class EvolutionClient {
  private readonly http: AxiosInstance;

  constructor(
    private readonly baseUrl = config.evolution.baseUrl,
    private readonly instance = config.evolution.instance,
    private readonly apiKey = config.evolution.apiKey,
  ) {
    this.http = axios.create({
      baseURL: baseUrl,
      timeout: 60_000,
      headers: { 'Content-Type': 'application/json', apikey: apiKey },
      maxBodyLength: Infinity,
      maxContentLength: Infinity,
    });
  }

  private get headers() {
    return { 'Content-Type': 'application/json', apikey: this.apiKey };
  }

  /** Extrai o base64 de qualquer formato de resposta da Evolution. */
  private static pickBase64(body: any): string | undefined {
    const candidates = [
      body?.base64,
      body?.response?.base64,
      body?.data?.base64,
      body?.response?.data?.base64,
      body?.response?.buffer,
    ];
    for (const c of candidates) {
      if (typeof c === 'string' && c.length > 0) return c.includes(',') ? c.slice(c.indexOf(',') + 1) : c;
      // Buffer serializado em JSON: { type: 'Buffer', data: [...] }
      if (c && typeof c === 'object' && Array.isArray(c.data)) {
        return Buffer.from(c.data).toString('base64');
      }
    }
    return undefined;
  }

  /** Baixa a mídia (opus/ogg) do WhatsApp. */
  async downloadMedia(evt: {
    message?: any;
    audioUrl?: string;
    mimetype?: string;
  }): Promise<DownloadedMedia> {
    const mimetype = evt.mimetype ?? 'audio/ogg';

    // v2: manda a mensagem inteira e recebe base64.
    if (evt.message && typeof evt.message === 'object') {
      try {
        const res = await this.http.post(
          `/chat/getBase64FromMediaMessage/${this.instance}`,
          { message: evt.message },
          { headers: this.headers, responseType: 'json' },
        );
        const body: any = res.data ?? {};
        const payload = body?.response ?? body;
        const b64 = EvolutionClient.pickBase64(body);
        if (b64) {
          const mime = payload?.mimetype ?? mimetype;
          return {
            data: Buffer.from(b64, 'base64'),
            extension: EXT_BY_MIME[mime] ?? 'ogg',
            mimetype: mime,
          };
        }
      } catch (err: any) {
        const status = err?.response?.status;
        // 404/405: rota inexistente nesta versão. 400: mensagem não reconhecida.
        if (status !== 404 && status !== 405 && status !== 400) throw err;
      }
    }

    // v1: link público para o arquivo.
    if (evt.audioUrl) {
      const res = await this.http.post(
        `/chat/downloadMedia/${this.instance}`,
        { mediaLink: evt.audioUrl, mimetype },
        { headers: this.headers, responseType: 'json' },
      );

      const body: any = res.data ?? {};

      const b64 = EvolutionClient.pickBase64(body);
      if (b64) {
        return {
          data: Buffer.from(b64, 'base64'),
          extension: EXT_BY_MIME[body.mimetype ?? mimetype] ?? 'ogg',
          mimetype: body.mimetype ?? mimetype,
        };
      }

      // Versões antigas devolvem um link temporário.
      const link: string | undefined = body.link ?? body.url ?? body.mediaUrl;
      if (typeof link === 'string' && link.length > 0) {
        const bin = await this.http.get(link, { responseType: 'arraybuffer' });
        return {
          data: Buffer.from(bin.data),
          extension: EXT_BY_MIME[body.mimetype ?? mimetype] ?? 'ogg',
          mimetype: body.mimetype ?? mimetype,
        };
      }

      // Alguns deployments devolvem o binário direto.
      if (Buffer.isBuffer(res.data)) {
        return { data: res.data, extension: EXT_BY_MIME[mimetype] ?? 'ogg', mimetype };
      }
    }

    throw new Error('Evolution downloadMedia: resposta sem base64 nem link');
  }

  /** QR de pareamento (v2). Chamar isso reinicia a conexão do socket. */
  async getQrCode(): Promise<{ base64?: string; ref?: string; pairingCode?: string } | null> {
    try {
      const res = await this.http.get(`/instance/connect/${this.instance}`, {
        headers: { apikey: this.apiKey },
      });
      const body: any = res.data ?? {};
      const raw: string | undefined = body.base64 ?? body.response?.base64;
      if (typeof raw !== 'string' || raw.length === 0) return null;
      const comma = raw.indexOf(',');
      return {
        base64: comma >= 0 ? raw.slice(comma + 1) : raw,
        ref: body.ref ? String(body.ref) : body.code ? String(body.code) : undefined,
        pairingCode: body.pairingCode ?? undefined,
      };
    } catch {
      return null;
    }
  }

  /** Envia uma mensagem de texto (resposta rápida). */
  async sendText(number: string, text: string): Promise<any> {
    const payload = { number, text };
    try {
      return await this.http.post(`/message/sendText/${this.instance}`, payload, {
        headers: this.headers,
      });
    } catch (err: any) {
      const status = err?.response?.status;
      if (status !== 404 && status !== 405) throw err;
      // v1
      return this.http.post(`/chat/sendText/${this.instance}`, payload, {
        headers: this.headers,
      });
    }
  }

  /** Envia nota de voz (PTT) para o número informado (só dígitos). */
  async sendVoiceNote(number: string, ogg: Buffer, fileName = 'reply.ogg'): Promise<any> {
    const b64 = ogg.toString('base64');
    const payloadBase = {
      number,
      mimetype: 'audio/ogg',
      fileName,
      ptt: true,
    };

    // v2: base64 puro no corpo.
    try {
      return await this.http.post(
        `/message/sendWhatsAppAudio/${this.instance}`,
        { ...payloadBase, audio: b64 },
        { headers: this.headers },
      );
    } catch (err: any) {
      const status = err?.response?.status;
      if (status !== 404 && status !== 405) throw err;
    }

    // v1: data URI na API dedicada de áudio.
    const dataUri = `data:audio/ogg;base64,${b64}`;
    try {
      return await this.http.post(`/chat/sendAudio/${this.instance}`, {
        ...payloadBase,
        audio: dataUri,
      }, { headers: this.headers });
    } catch (err: any) {
      const status = err?.response?.status;
      if (status !== 404 && status !== 405) throw err;
      // Fallback: sendMedia com mediatype=audio.
      return this.http.post(`/chat/sendMedia/${this.instance}`, {
        ...payloadBase,
        mediatype: 'audio',
        file: dataUri,
      }, { headers: this.headers });
    }
  }
}
