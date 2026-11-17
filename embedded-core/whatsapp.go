package main

import "context"

// AudioMessage is a voice note received from WhatsApp.
type AudioMessage struct {
	ID            string
	From          string // Sender JID (e.g., 5511999999999@s.whatsapp.net)
	FromNumber    string // Digits only
	FromName      string // Display name
	Seconds       int    // Voice note duration
	Mimetype      string // audio/ogg; codecs=opus
	MediaURL      string // CDN link (mmg.whatsapp.net)
	DirectPath    string // Direct path on the CDN
	MediaKey      []byte // Decryption key
	FileSHA256    []byte // Hash of decrypted content (for retry)
	FileEncSHA256 []byte // Hash of encrypted content (for retry)
	FileLength    uint64 // Length in bytes (for retry)
	ChatJID       string // Conversation JID (for SendMediaRetryReceipt)
	IsPTT         bool
}

// Event is an event delivered by the WhatsApp engine to the bridge.
type Event struct {
	Kind  string        // "audio"
	Audio *AudioMessage // Set when Kind == "audio"
}

// Status represents the pairing status.
type Status struct {
	Connected bool   `json:"connected"`
	LoggedIn  bool   `json:"loggedIn"`
	JID       string `json:"jid"`
	Phone     string `json:"phone"`
	QR        string `json:"qr,omitempty"` // QR code base64 (PNG)
}

// WhatsApp is the interface for the WhatsApp engine.
//
// The HTTP daemon only knows this interface, so tests can run without a real
// WhatsApp connection (using a fake) while the real engine (whatsmeow) stays isolated.
type WhatsApp interface {
	// Connect opens the session (QR code or 8-digit code).
	Connect(ctx context.Context) error
	// Status returns the current state.
	Status() Status
	// PairCode generates an 8-digit code to pair without a QR code.
	PairCode(ctx context.Context, phone string) (string, error)
	// Logout signs out and clears credentials.
	Logout(ctx context.Context) error
	// Events returns received events (audio).
	Events() <-chan Event
	// DownloadAudio downloads audio (raw bytes, e.g., OGG/Opus).
	DownloadAudio(ctx context.Context, audio AudioMessage) ([]byte, error)
	// SendAudio sends a voice note.
	SendAudio(ctx context.Context, number string, data []byte, mimetype string, seconds uint32) error
	// SendText sends a text message.
	SendText(ctx context.Context, number string, text string) error
	// Contacts lists contacts and groups for the app's interactive list.
	Contacts(ctx context.Context) ([]ContactEntry, []GroupEntry, error)
}
