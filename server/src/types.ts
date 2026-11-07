export interface CallPayload {
  type: 'NEW_VOICE_NOTE';
  audioId: string;
  senderName: string;
  senderNumber: string;
  audioUrl: string;
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
}
