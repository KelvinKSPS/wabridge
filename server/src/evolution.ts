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
 * Cliente mínimo da Evolution API (v2). Só o que a ponte precisa:
 * baixar mídia recebida e enviar nota de voz.
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

  /** Baixa a mídia (opus/ogg) do WhatsApp. */
  async downloadMedia(mediaUrl: string, mimetype = 'audio/ogg'): Promise<DownloadedMedia> {
    const res = await this.http.post(
      `/chat/downloadMedia/${this.instance}`,
      { mediaLink: mediaUrl, mimetype },
      { headers: this.headers, responseType: 'json' },
    );

    const body: any = res.data ?? {};

    // Evolution v2.1+: { status, base64, mimetype }
    const b64: string | undefined = body.base64 ?? body.data ?? body.response?.base64;
    if (typeof b64 === 'string' && b64.length > 0) {
      const clean = b64.includes(',') ? b64.slice(b64.indexOf(',') + 1) : b64;
      return {
        data: Buffer.from(clean, 'base64'),
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

    throw new Error('Evolution downloadMedia: resposta sem base64 nem link');
  }

  /** Envia nota de voz (PTT) para o número informado (só dígitos). */
  async sendVoiceNote(number: string, ogg: Buffer, fileName = 'reply.ogg'): Promise<any> {
    const dataUri = `data:audio/ogg;base64,${ogg.toString('base64')}`;
    const payloadBase = {
      number,
      mimetype: 'audio/ogg',
      fileName,
      ptt: true,
    };

    // API dedicada de áudio (presente na maioria das versões).
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
