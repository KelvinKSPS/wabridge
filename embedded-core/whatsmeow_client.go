package main

import (
	"context"
	"encoding/base64"
	"fmt"
	"log"
	"path/filepath"
	"sync"
	"time"

	"github.com/skip2/go-qrcode"
	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/store/sqlstore"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	waE2E "go.mau.fi/whatsmeow/proto/waE2E"
	waLog "go.mau.fi/whatsmeow/util/log"

	// mattn/go-sqlite3 (CGO): o "modernc.org/sqlite" puro em Go usa
	// syscalls que o seccomp do Android bloqueia (SIGSYS na hora do
	// fstatat) — por isso o daemon crashava ao abrir a sessão.
	_ "github.com/mattn/go-sqlite3"
)

// whatsmeowClient é o motor do WhatsApp (implementa a interface [WhatsApp]).
type whatsmeowClient struct {
	mu        sync.Mutex
	container *sqlstore.Container
	client    *whatsmeow.Client
	events    chan Event
	qr        string
	connected chan struct{}
}

// NewWhatsmeow abre (ou cria) a sessão local e devolve o motor.
func NewWhatsmeow(dir string, phone string) (*whatsmeowClient, error) {
	ctx := context.Background()
	dsn := "file:" + filepath.Join(dir, "session.db") + "?_foreign_keys=on&_busy_timeout=5000"

	container, err := sqlstore.New(ctx, "sqlite3", dsn, newLogger())
	if err != nil {
		return nil, fmt.Errorf("abrindo o store sqlite: %w", err)
	}

	device, err := container.GetFirstDevice(ctx)
	if err != nil {
		container.Close()
		return nil, fmt.Errorf("lendo a sessão: %w", err)
	}
	if device == nil {
		device = container.NewDevice()
	}
	device.Platform = "Android"
	device.PushName = "Wabridge"
	device.BusinessName = "Wabridge"

	cli := whatsmeow.NewClient(device, newLogger())

	w := &whatsmeowClient{
		container: container,
		client:    cli,
		events:    make(chan Event, 32),
		connected: make(chan struct{}),
	}
	cli.AddEventHandler(w.onEvent)
	return w, nil
}

// Connect abre a sessão (e fica reconectando sozinho).
func (w *whatsmeowClient) Connect(ctx context.Context) error {
	w.client.EnableAutoReconnect = true
	w.connectOnce(ctx)
	// O auto-reconnect do whatsmeow só age depois do login. Na janela do
	// pareamento (aparelho ainda não logado) uma queda deixaria o daemon
	// morto para sempre — então a gente reconecta na mão.
	go w.watch(ctx)
	return nil
}

// connectOnce cria o canal de QR e abre a conexão.
//
// O canal do QR precisa ser criado ANTES do connect (exigência da biblioteca).
func (w *whatsmeowClient) connectOnce(ctx context.Context) {
	ch, err := w.client.GetQRChannel(ctx)
	if err != nil {
		log.Printf("wabridge: canal de QR: %v", err)
	} else {
		// QR: o canal entrega o ref; a gente desenha o PNG.
		go func() {
			for item := range ch {
				if item.Error != nil {
					log.Printf("wabridge: QR: %v", item.Error)
					continue
				}
				if item.Event != "code" || item.Code == "" {
					continue
				}
				png, err := qrcode.Encode(item.Code, qrcode.Medium, 512)
				if err != nil {
					log.Printf("wabridge: QR png: %v", err)
					continue
				}
				w.mu.Lock()
				w.qr = "data:image/png;base64," + base64.StdEncoding.EncodeToString(png)
				w.mu.Unlock()
				w.markReady()
				log.Println("wabridge: QR disponível")
			}
		}()
	}

	go func() {
		if err := w.client.Connect(); err != nil {
			log.Printf("wabridge: connect: %v", err)
		}
	}()
}

// watch reconecta quando a conexão cai antes do pareamento.
func (w *whatsmeowClient) watch(ctx context.Context) {
	t := time.NewTicker(20 * time.Second)
	defer t.Stop()
	for range t.C {
		if ctx.Err() != nil || w.client.IsLoggedIn() {
			return
		}
		if w.client.IsConnected() {
			continue
		}
		log.Println("wabridge: conexão perdida antes do pareamento; reconectando")
		w.connectOnce(ctx)
	}
}

// markReady libera o pedido de código de pareamento (o cliente já conectou).
func (w *whatsmeowClient) markReady() {
	select {
	case <-w.connected:
	default:
		close(w.connected)
	}
}

// Status devolve o estado do pareamento.
func (w *whatsmeowClient) Status() Status {
	w.mu.Lock()
	qr := w.qr
	w.mu.Unlock()

	st := Status{Connected: w.client.IsConnected(), LoggedIn: w.client.IsLoggedIn(), QR: qr}
	if w.client.Store.ID != nil {
		st.JID = w.client.Store.ID.String()
		st.Phone = w.client.Store.ID.User
	}
	return st
}

// PairCode gera o código de 8 dígitos (pareia sem QR).
//
// O whatsmeow devolve o código na hora e a confirmação chega depois, por
// notificação — então esta chamada não bloqueia.
func (w *whatsmeowClient) PairCode(ctx context.Context, phone string) (string, error) {
	// o cliente precisa estar conectado (e ter recebido o QR) antes do pedido
	select {
	case <-w.connected:
	case <-time.After(20 * time.Second):
		return "", fmt.Errorf("o cliente ainda não conectou ao WhatsApp")
	}

	// o nome do cliente precisa estar no formato "Browser (OS)": o
	// WhatsApp valida e devolve 400 se não for um browser/SO conhecido
	code, err := w.client.PairPhone(ctx, phone, true, whatsmeow.PairClientChrome, "Chrome (Android)")
	if err != nil {
		return "", err
	}
	return code, nil
}

// Logout desloga e apaga a sessão.
func (w *whatsmeowClient) Logout(ctx context.Context) error {
	if w.client.IsLoggedIn() {
		if err := w.client.Logout(ctx); err != nil {
			return err
		}
	}
	w.mu.Lock()
	w.qr = ""
	w.mu.Unlock()
	return nil
}

// Events entrega os eventos recebidos.
func (w *whatsmeowClient) Events() <-chan Event { return w.events }

// DownloadAudio baixa o áudio da nota de voz.
func (w *whatsmeowClient) DownloadAudio(ctx context.Context, audio AudioMessage) ([]byte, error) {
	mime := audio.Mimetype
	seconds := uint32(audio.Seconds)
	ptt := audio.IsPTT
	msg := &waE2E.Message{
		AudioMessage: &waE2E.AudioMessage{
			URL:        &audio.MediaURL,
			DirectPath: &audio.DirectPath,
			MediaKey:   audio.MediaKey,
			Mimetype:   &mime,
			Seconds:    &seconds,
			PTT:        &ptt,
		},
	}
	return w.client.DownloadAny(ctx, msg)
}

// SendAudio envia uma nota de voz (PTT).
func (w *whatsmeowClient) SendAudio(ctx context.Context, number string, data []byte, mimetype string) error {
	resp, err := w.client.Upload(ctx, data, whatsmeow.MediaAudio)
	if err != nil {
		return err
	}
	url := resp.URL
	directPath := resp.DirectPath
	ptt := true
	jid := types.JID{User: number, Server: types.DefaultUserServer}
	_, err = w.client.SendMessage(ctx, jid, &waE2E.Message{
		AudioMessage: &waE2E.AudioMessage{
			URL:        &url,
			DirectPath: &directPath,
			MediaKey:   resp.MediaKey,
			Mimetype:   &mimetype,
			PTT:        &ptt,
		},
	})
	return err
}

// SendText envia uma mensagem de texto.
func (w *whatsmeowClient) SendText(ctx context.Context, number string, text string) error {
	jid := types.JID{User: number, Server: types.DefaultUserServer}
	_, err := w.client.SendMessage(ctx, jid, &waE2E.Message{Conversation: &text})
	return err
}

// Close fecha a sessão.
func (w *whatsmeowClient) Close() {
	if w.client != nil {
		w.client.Disconnect()
	}
	if w.container != nil {
		w.container.Close()
	}
}

// onEvent traduz os eventos do whatsmeow para os da ponte.
func (w *whatsmeowClient) onEvent(evt any) {
	switch e := evt.(type) {
	case *events.QR:
		w.mu.Lock()
		w.qr = ""
		w.mu.Unlock()
	case *events.Connected:
		w.markReady()
	case *events.PairSuccess:
		log.Println("wabridge: pareado!")
		w.mu.Lock()
		w.qr = ""
		w.mu.Unlock()
	case *events.LoggedOut:
		log.Println("wabridge: desconectado")
	case *events.Message:
		msg := e.Message
		if msg == nil {
			return
		}
		audio := msg.GetAudioMessage()
		if audio == nil {
			return
		}
		jid := e.Info.Chat
		// só conversa privada (grupos e newsletters ficam de fora)
		if jid.Server != types.DefaultUserServer {
			return
		}
		mime := audio.GetMimetype()
		w.events <- Event{
			Kind: "audio",
			Audio: &AudioMessage{
				ID:         e.Info.ID,
				From:       jid.String(),
				FromNumber: jid.User,
				FromName:   e.Info.PushName,
				Seconds:    int(audio.GetSeconds()),
				Mimetype:   mime,
				MediaURL:   audio.GetURL(),
				DirectPath: audio.GetDirectPath(),
				MediaKey:   audio.GetMediaKey(),
				IsPTT:      audio.GetPTT(),
			},
		}
	}
}

// logger é um adaptador simples para o logger do whatsmeow.
type logger struct{}

func newLogger() waLog.Logger { return &logger{} }

func (l *logger) Warnf(msg string, args ...any)  { log.Printf("WARN  "+msg, args...) }
func (l *logger) Errorf(msg string, args ...any) { log.Printf("ERROR "+msg, args...) }
func (l *logger) Infof(msg string, args ...any)  { log.Printf("INFO  "+msg, args...) }
func (l *logger) Debugf(msg string, args ...any) {}
func (l *logger) Sub(module string) waLog.Logger { return l }
