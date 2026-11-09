export interface CallPayload {
  type: 'NEW_VOICE_NOTE';
  audioId: string;
  senderName: string;
  senderNumber: string;
  audioUrl: string;
  /** Duração da nota de voz em segundos (0 = desconhecido). */
  durationSec: number;
}

export interface PendingCall {
  audioId: string;
  senderNumber: string;
  senderName: string;
  createdAt: number;
}

/** Evento de WhatsApp normalizado (independente da versão do Evolution API). */
export interface IncomingAudio {
  messageId: string;
  fromMe: boolean;
  number: string;
  pushName: string;
  audioUrl: string;
  mimetype: string;
  /** jid original (usado para detectar grupo). */
  remoteJid: string;
  /**
   * Mensagem crua como a Evolution entregou (formato v2).
   * A v2 não expõe link público do áudio: ele é baixado passando a mensagem
   * inteira para `getBase64FromMediaMessage`.
   */
  message?: any;
}
