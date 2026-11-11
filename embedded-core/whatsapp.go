package main

import "context"

// AudioMessage é uma nota de voz que chegou do WhatsApp.
type AudioMessage struct {
	ID            string
	From          string // jid do remetente (ex.: 5511999999999@s.whatsapp.net)
	FromNumber    string // só dígitos
	FromName      string // nome de exibição
	Seconds       int    // duração da nota
	Mimetype      string // audio/ogg; codecs=opus
	MediaURL      string // link do CDN (mmg.whatsapp.net)
	DirectPath    string // caminho direto no CDN
	MediaKey      []byte // chave para decriptar
	FileSHA256    []byte // hash do conteúdo decriptado (para retry)
	FileEncSHA256 []byte // hash do conteúdo encriptado (para retry)
	FileLength    uint64 // tamanho em bytes (para retry)
	ChatJID       string // JID da conversa (para SendMediaRetryReceipt)
	IsPTT         bool
}

// Evento que o motor do WhatsApp entrega para a ponte.
type Event struct {
	Kind  string        // "audio"
	Audio *AudioMessage // preenchido quando Kind == "audio"
}

// Status do pareamento.
type Status struct {
	Connected bool   `json:"connected"`
	LoggedIn  bool   `json:"loggedIn"`
	JID       string `json:"jid"`
	Phone     string `json:"phone"`
	QR        string `json:"qr,omitempty"` // base64 do QR (png)
}

// WhatsApp é a interface do motor do WhatsApp.
//
// O daemon HTTP só conhece essa interface: assim os testes rodam sem
// WhatsApp de verdade (fake) e o motor real (whatsmeow) fica isolado.
type WhatsApp interface {
	// Connect abre a sessão (QR ou código de 8 dígitos).
	Connect(ctx context.Context) error
	// Status devolve o estado atual.
	Status() Status
	// PairCode gera o código de 8 dígitos para parear sem QR.
	PairCode(ctx context.Context, phone string) (string, error)
	// Logout desloga e limpa as credenciais.
	Logout(ctx context.Context) error
	// Events entrega os eventos recebidos (áudios).
	Events() <-chan Event
	// DownloadAudio baixa o áudio (bytes crus, ex.: ogg/opus).
	DownloadAudio(ctx context.Context, audio AudioMessage) ([]byte, error)
	// SendAudio envia uma nota de voz.
	SendAudio(ctx context.Context, number string, data []byte, mimetype string) error
	// SendText envia uma mensagem de texto.
	SendText(ctx context.Context, number string, text string) error
	// Contacts lista contatos e grupos (para a lista interativa do app).
	Contacts(ctx context.Context) ([]ContactEntry, []GroupEntry, error)
}
