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
 * Evolution API client. Only what the bridge needs: download the media
 * received and send a voice note.
 *
 * - v2 (Evolution API 2.x): the media is downloaded by passing the whole message
 * to `getBase64FromMediaMessage`, and the voice note goes as base64
 * in `message/sendWhatsAppAudio`. v1 is still supported as a fallback
 * (public link + `/chat/sendAudio`).
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

  /** Extracts the base64 from any response format from Evolution. */
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
      // Buffer serialized as JSON: { type: 'Buffer', data: [...] }
      if (c && typeof c === 'object' && Array.isArray(c.data)) {
        return Buffer.from(c.data).toString('base64');
      }
    }
    return undefined;
  }

  /** Downloads the media (opus/ogg) from WhatsApp. */
  async downloadMedia(evt: {
    message?: any;
    audioUrl?: string;
    mimetype?: string;
  }): Promise<DownloadedMedia> {
    const mimetype = evt.mimetype ?? 'audio/ogg';

    // v2: sends the whole message and receives base64.
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
        // 404/405: route does not exist in this version. 400: message not recognized.
        if (status !== 404 && status !== 405 && status !== 400) throw err;
      }
    }

    // v1: public link to the file.
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

      // Older versions return a temporary link.
      const link: string | undefined = body.link ?? body.url ?? body.mediaUrl;
      if (typeof link === 'string' && link.length > 0) {
        const bin = await this.http.get(link, { responseType: 'arraybuffer' });
        return {
          data: Buffer.from(bin.data),
          extension: EXT_BY_MIME[body.mimetype ?? mimetype] ?? 'ogg',
          mimetype: body.mimetype ?? mimetype,
        };
      }

      // Some deployments return the binary directly.
      if (Buffer.isBuffer(res.data)) {
        return { data: res.data, extension: EXT_BY_MIME[mimetype] ?? 'ogg', mimetype };
      }
    }

    throw new Error('Evolution downloadMedia: resposta sem base64 nem link');
  }

  /** Pairing QR (v2). Calling this restarts the socket connection. */
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

  /** Sends a text message (quick reply). */
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

  /** Sends a voice note (PTT) to the given number (digits only). */
  async sendVoiceNote(number: string, ogg: Buffer, fileName = 'reply.ogg'): Promise<any> {
    const b64 = ogg.toString('base64');
    const payloadBase = {
      number,
      mimetype: 'audio/ogg',
      fileName,
      ptt: true,
    };

    // v2: plain base64 in the body.
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

    // v1: data URI in the dedicated audio API.
    const dataUri = `data:audio/ogg;base64,${b64}`;
    try {
      return await this.http.post(`/chat/sendAudio/${this.instance}`, {
        ...payloadBase,
        audio: dataUri,
      }, { headers: this.headers });
    } catch (err: any) {
      const status = err?.response?.status;
      if (status !== 404 && status !== 405) throw err;
      // Fallback: sendMedia with mediatype=audio.
      return this.http.post(`/chat/sendMedia/${this.instance}`, {
        ...payloadBase,
        mediatype: 'audio',
        file: dataUri,
      }, { headers: this.headers });
    }
  }
}
