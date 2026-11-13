package main

import (
	"context"
	"encoding/base64"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/skip2/go-qrcode"
	"go.mau.fi/whatsmeow"
	waE2E "go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/store/sqlstore"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	waLog "go.mau.fi/whatsmeow/util/log"

	// mattn/go-sqlite3 (CGO): o "modernc.org/sqlite" puro em Go usa
	// syscalls que o seccomp do Android bloqueia (SIGSYS na hora do
	// fstatat) — por isso o daemon crashava ao abrir a sessão.
	_ "github.com/mattn/go-sqlite3"
)

// whatsmeowClient é o motor do WhatsApp (implementa a interface [WhatsApp]).
type whatsmeowClient struct {
	// ctx de fundo para chamadas feitas dos handlers de evento
	ctx       context.Context
	mu        sync.Mutex
	container *sqlstore.Container
	client    *whatsmeow.Client
	events    chan Event
	qr        string
	connected chan struct{}

	// retry de mídia: quando o CDN retorna hash errado, pedimos ao remetente
	// que reenvie — o whatsmeow entrega um events.MediaRetry com novo DirectPath
	pendingRetry map[string]chan *events.MediaRetry // keyed by message ID
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
		ctx:          context.Background(),
		container:    container,
		client:       cli,
		events:       make(chan Event, 32),
		connected:    make(chan struct{}),
		pendingRetry: make(map[string]chan *events.MediaRetry),
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
// Se o CDN devolver hash errado, envia SendMediaRetryReceipt e aguarda
// o WhatsApp reenviar a mídia com novo DirectPath (até 30 s).
func (w *whatsmeowClient) DownloadAudio(ctx context.Context, audio AudioMessage) ([]byte, error) {
	mime := audio.Mimetype
	seconds := uint32(audio.Seconds)
	ptt := audio.IsPTT
	fileLen := audio.FileLength
	msg := &waE2E.Message{
		AudioMessage: &waE2E.AudioMessage{
			URL:           &audio.MediaURL,
			DirectPath:    &audio.DirectPath,
			MediaKey:      audio.MediaKey,
			Mimetype:      &mime,
			Seconds:       &seconds,
			PTT:           &ptt,
			FileSHA256:    audio.FileSHA256,
			FileEncSHA256: audio.FileEncSHA256,
			FileLength:    &fileLen,
		},
	}

	data, err := w.client.DownloadAny(ctx, msg)
	if err == nil {
		return data, nil
	}

	// tentativa de retry: pede ao remetente que reenvie a mídia
	log.Printf("wabridge: download falhou (%v) — tentando media retry…", err)
	chatJID, parseErr := types.ParseJID(audio.ChatJID)
	if parseErr != nil || audio.ChatJID == "" {
		return nil, err // sem info suficiente para retry
	}

	info := &types.MessageInfo{
		MessageSource: types.MessageSource{
			Chat:     chatJID,
			IsFromMe: false,
			IsGroup:  chatJID.Server == types.GroupServer,
		},
		ID: audio.ID,
	}

	retryCh := make(chan *events.MediaRetry, 1)
	w.mu.Lock()
	w.pendingRetry[audio.ID] = retryCh
	w.mu.Unlock()
	defer func() {
		w.mu.Lock()
		delete(w.pendingRetry, audio.ID)
		w.mu.Unlock()
	}()

	if sendErr := w.client.SendMediaRetryReceipt(ctx, info, audio.MediaKey); sendErr != nil {
		log.Printf("wabridge: SendMediaRetryReceipt: %v", sendErr)
		return nil, err
	}

	select {
	case retry := <-retryCh:
		notif, decErr := whatsmeow.DecryptMediaRetryNotification(retry, audio.MediaKey)
		if decErr != nil {
			return nil, fmt.Errorf("retry decrypt: %w (original: %v)", decErr, err)
		}
		newPath := notif.GetDirectPath()
		if newPath == "" {
			return nil, fmt.Errorf("retry sem DirectPath (original: %v)", err)
		}
		log.Printf("wabridge: retry ok, novo path=%s", newPath)
		return w.client.DownloadMediaWithPath(ctx, newPath,
			audio.FileEncSHA256, audio.FileSHA256, audio.MediaKey,
			whatsmeow.MediaAudio, "", true)
	case <-time.After(30 * time.Second):
		return nil, fmt.Errorf("timeout aguardando media retry (original: %v)", err)
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}

// SendAudio envia uma nota de voz (PTT), com retry e backoff.
func (w *whatsmeowClient) SendAudio(ctx context.Context, number string, data []byte, mimetype string, seconds uint32) error {
	// valida OGG: os 4 primeiros bytes devem ser "OggS"
	if len(data) >= 4 && string(data[:4]) != "OggS" {
		log.Printf("wabridge: AVISO: áudio não começa com OggS (magic=%q) — pode ser incompatível", data[:4])
	}

	var lastErr error
	for attempt := 1; attempt <= sendAudioMaxAttempts; attempt++ {
		err := w.sendAudioOnce(ctx, number, data, mimetype, seconds, attempt)
		if err == nil {
			return nil
		}
		lastErr = err
		if attempt < sendAudioMaxAttempts {
			d := sendAudioBackoff(attempt)
			log.Printf("wabridge: PTT tentativa=%d/%d falhou (%v); retry em %v", attempt, sendAudioMaxAttempts, err, d)
			select {
			case <-time.After(d):
			case <-ctx.Done():
				return ctx.Err()
			}
		}
	}
	return fmt.Errorf("PTT: %d tentativas esgotadas: %w", sendAudioMaxAttempts, lastErr)
}

// sendAudioOnce faz upload + envio de uma nota de voz.
func (w *whatsmeowClient) sendAudioOnce(ctx context.Context, number string, data []byte, mimetype string, seconds uint32, attempt int) error {
	resp, err := w.client.Upload(ctx, data, whatsmeow.MediaAudio)
	if err != nil {
		return fmt.Errorf("upload: %w", err)
	}
	msg := buildAudioMessage(resp, mimetype, seconds)
	log.Printf("wabridge: PTT tentativa=%d/%d jid=%s@%s mime=%s bytes=%d seconds=%d sidecar=%d waveform=%d",
		attempt, sendAudioMaxAttempts, number, types.DefaultUserServer, mimetype, resp.FileLength, seconds,
		len(resp.StreamingSidecar), len(msg.Waveform))

	jid := types.JID{User: number, Server: types.DefaultUserServer}
	sendResp, err := w.client.SendMessage(ctx, jid, &waE2E.Message{AudioMessage: msg})
	if err != nil {
		return fmt.Errorf("send: %w", err)
	}
	log.Printf("wabridge: PTT enviado para %s, msgID=%s, ts=%d", number, sendResp.ID, sendResp.Timestamp.Unix())
	return nil
}

// buildAudioMessage monta o AudioMessage (PTT) a partir do response do upload.
// O StreamingSidecar vem do CDN e é obrigatório: sem ele a mensagem é aceita
// (msgID retornado) mas não reproduz no aparelho — mesmo sintoma do wamux #18.
func buildAudioMessage(resp whatsmeow.UploadResponse, mimetype string, seconds uint32) *waE2E.AudioMessage {
	url := resp.URL
	directPath := resp.DirectPath
	ptt := true
	fileLen := resp.FileLength
	now := time.Now().Unix()
	return &waE2E.AudioMessage{
		URL:               &url,
		DirectPath:        &directPath,
		MediaKey:          resp.MediaKey,
		Mimetype:          &mimetype,
		PTT:               &ptt,
		FileEncSHA256:     resp.FileEncSHA256,
		FileSHA256:        resp.FileSHA256,
		FileLength:        &fileLen,
		Seconds:           &seconds,
		MediaKeyTimestamp: &now,
		StreamingSidecar:  resp.StreamingSidecar,
		Waveform:          makeWaveform(resp.StreamingSidecar, 64),
	}
}

// makeWaveform gera 64 valores de amplitude (0-100) para o diagrama da nota.
// O app oficial exige o campo para tratar a mensagem como PTT (iOS principalmente).
func makeWaveform(data []byte, n int) []byte {
	if n <= 0 {
		return nil
	}
	out := make([]byte, n)
	if len(data) == 0 {
		for i := range out {
			out[i] = 50
		}
		return out
	}
	step := len(data) / n
	if step < 1 {
		step = 1
	}
	for i := 0; i < n; i++ {
		start := i * step
		end := start + step
		if end > len(data) {
			end = len(data)
		}
		if start >= end {
			out[i] = 0
			continue
		}
		var sum int
		for _, b := range data[start:end] {
			sum += int(b)
		}
		v := (sum / (end - start)) % 101
		if v < 1 {
			v = 1
		}
		out[i] = byte(v)
	}
	return out
}

const sendAudioMaxAttempts = 3

// sendAudioBackoff: 250ms, 500ms, 1s (com jitter de ±25%).
func sendAudioBackoff(attempt int) time.Duration {
	base := time.Duration(250*(1<<(attempt-1))) * time.Millisecond
	jitter := time.Duration(float64(base) * (0.75 + 0.5*float64(time.Now().UnixNano()%1000)/1000.0))
	return jitter
}

// ContactEntry é um contato da agenda (lista interativa do app).
type ContactEntry struct {
	Nome   string `json:"name"`
	Numero string `json:"number"`
}

// GroupEntry é um grupo do WhatsApp (lista interativa do app).
type GroupEntry struct {
	JID  string `json:"jid"`
	Nome string `json:"name"`
}

// Contacts lista os contatos e os grupos da conta.
//
// O número vem sempre resolvido: o WhatsApp manda LID (@lid) para quem
// não está na agenda, então o mapa LID→PN é consultado antes.
func (w *whatsmeowClient) Contacts(ctx context.Context) ([]ContactEntry, []GroupEntry, error) {
	// Sem sessão pareada o store ainda não existe: devolve vazio em vez de
	// estourar um nil pointer no handler HTTP.
	if w.client.Store == nil || w.client.Store.Contacts == nil {
		return []ContactEntry{}, []GroupEntry{}, nil
	}

	cs, err := w.client.Store.Contacts.GetAllContacts(ctx)
	if err != nil {
		return nil, nil, err
	}
	contatos := make([]ContactEntry, 0, len(cs))
	for jid, info := range cs {
		if jid.Server == types.GroupServer || jid.Server == types.BotServer {
			continue
		}
		numero := jid.User
		if jid.Server == types.HiddenUserServer {
			if pn, err := w.client.Store.LIDs.GetPNForLID(ctx, jid); err == nil {
				numero = pn.User
			}
		}
		nome := info.FullName
		if nome == "" {
			nome = info.PushName
		}
		if nome == "" {
			nome = info.FirstName
		}
		contatos = append(contatos, ContactEntry{Nome: nome, Numero: numero})
	}
	sort.Slice(contatos, func(i, j int) bool {
		return strings.ToLower(contatos[i].Nome) < strings.ToLower(contatos[j].Nome)
	})

	// fora da conexão o IQ de grupos falha; devolve vazio em vez de erro
	var gs []*types.GroupInfo
	if w.client.IsConnected() {
		var err error
		gs, err = w.client.GetJoinedGroups(ctx)
		if err != nil {
			return nil, nil, err
		}
	}
	grupos := make([]GroupEntry, 0, len(gs))
	for _, g := range gs {
		if g == nil {
			continue
		}
		grupos = append(grupos, GroupEntry{JID: g.JID.String(), Nome: g.Name})
	}
	sort.Slice(grupos, func(i, j int) bool {
		return strings.ToLower(grupos[i].Nome) < strings.ToLower(grupos[j].Nome)
	})
	return contatos, grupos, nil
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

		// WhatsApp manda o interlocutor como LID (@lid) quando o contato
		// não está na agenda; o número real só existe no mapa LID→PN.
		// Sem resolver, a nota de voz caiia no filtro de servidor.
		if jid.Server == types.HiddenUserServer {
			ctx, cancel := context.WithTimeout(w.ctx, 5*time.Second)
			if pn, err := w.client.Store.LIDs.GetPNForLID(ctx, jid); err == nil {
				jid = pn
			}
			cancel()
		}

		// ignora mensagens enviadas pelo próprio usuário
		// (WABRIDGE_ACCEPT_OWN=1 desativa o filtro para testes)
		if e.Info.IsFromMe && os.Getenv("WABRIDGE_ACCEPT_OWN") == "" {
			return
		}
		// só conversa privada (grupos e newsletters ficam de fora)
		if jid.Server != types.DefaultUserServer {
			return
		}
		mime := audio.GetMimetype()
		w.events <- Event{
			Kind: "audio",
			Audio: &AudioMessage{
				ID:            e.Info.ID,
				From:          jid.String(),
				FromNumber:    jid.User,
				FromName:      e.Info.PushName,
				Seconds:       int(audio.GetSeconds()),
				Mimetype:      mime,
				MediaURL:      audio.GetURL(),
				DirectPath:    audio.GetDirectPath(),
				MediaKey:      audio.GetMediaKey(),
				FileSHA256:    audio.GetFileSHA256(),
				FileEncSHA256: audio.GetFileEncSHA256(),
				FileLength:    audio.GetFileLength(),
				ChatJID:       jid.String(),
				IsPTT:         audio.GetPTT(),
			},
		}
	case *events.MediaRetry:
		w.mu.Lock()
		ch, ok := w.pendingRetry[string(e.MessageID)]
		w.mu.Unlock()
		if ok {
			ch <- e
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
