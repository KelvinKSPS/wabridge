export interface CallPayload {
  type: 'NEW_VOICE_NOTE';
  audioId: string;
  senderName: string;
  senderNumber: string;
  audioUrl: string;
  /** Duration of the voice note in seconds (0 = unknown). */
  durationSec: number;
}

export interface PendingCall {
  audioId: string;
  senderNumber: string;
  senderName: string;
  createdAt: number;
}

/** Normalized WhatsApp event (independent of the Evolution API version). */
export interface IncomingAudio {
  messageId: string;
  fromMe: boolean;
  number: string;
  pushName: string;
  audioUrl: string;
  mimetype: string;
  /** original jid (used to detect groups). */
  remoteJid: string;
  /**
   * Raw message as Evolution delivered it (v2 format).
   * v2 does not expose a public link for the audio: it is downloaded by passing the
   * whole message to `getBase64FromMediaMessage`.
   */
  message?: any;
}
