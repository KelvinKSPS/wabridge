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

	// mattn/go-sqlite3 (CGO): the pure-Go "modernc.org/sqlite" uses
	// syscalls blocked by Android's seccomp (SIGSYS during fstatat),
	// which caused the daemon to crash when opening the session.
	_ "github.com/mattn/go-sqlite3"
)

// whatsmeowClient is the WhatsApp engine (implements the [WhatsApp] interface).
type whatsmeowClient struct {
	// Background context for calls made by event handlers
	ctx       context.Context
	mu        sync.Mutex
	container *sqlstore.Container
	client    *whatsmeow.Client
	events    chan Event
	qr        string
	connected chan struct{}
	recent    *RecentStore

	// Media retry: when the CDN returns a wrong hash, ask the sender
	// to resend; whatsmeow delivers an events.MediaRetry with a new DirectPath.
	pendingRetry map[string]chan *events.MediaRetry // keyed by message ID
	sentAudio    map[types.MessageID]time.Time
}

// NewWhatsmeow opens (or creates) the local session and returns the engine.
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
		recent:       NewRecentStore(dir),
		pendingRetry: make(map[string]chan *events.MediaRetry),
	}
	cli.AddEventHandler(w.onEvent)
	return w, nil
}

// Connect opens the session and keeps reconnecting automatically.
func (w *whatsmeowClient) Connect(ctx context.Context) error {
	w.client.EnableAutoReconnect = true
	w.connectOnce(ctx)
	// whatsmeow auto-reconnect only works after login. A disconnect during
	// pairing (before the device is logged in) would leave the daemon offline,
	// so reconnect manually.
	go w.watch(ctx)
	// Ticker to save recent activity history every 15 seconds
	go w.watchRecentSave(ctx)
	return nil
}

// connectOnce creates the QR channel and opens the connection.
//
// The QR channel must be created BEFORE connect (library requirement).
func (w *whatsmeowClient) connectOnce(ctx context.Context) {
	ch, err := w.client.GetQRChannel(ctx)
	if err != nil {
		log.Printf("wabridge: canal de QR: %v", err)
	} else {
		// QR: the channel provides the reference; render it as a PNG.
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

// watch reconnects if the connection drops before pairing.
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

// watchRecentSave saves recent activity history every 15 seconds.
func (w *whatsmeowClient) watchRecentSave(ctx context.Context) {
	t := time.NewTicker(15 * time.Second)
	defer t.Stop()
	for range t.C {
		if ctx.Err() != nil {
			return
		}
		if err := w.recent.SaveIfDirty(); err != nil {
			log.Printf("wabridge: erro salvando histórico recente: %v", err)
		}
	}
}

// markReady enables pairing-code requests (the client has connected).
func (w *whatsmeowClient) markReady() {
	select {
	case <-w.connected:
	default:
		close(w.connected)
	}
}

// Status returns the pairing status.
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

// PairCode generates an 8-digit code (pairs without a QR code).
//
// whatsmeow returns the code immediately; confirmation arrives later in a
// notification, so this call does not block.
func (w *whatsmeowClient) PairCode(ctx context.Context, phone string) (string, error) {
	// The client must be connected (and have received the QR) before the request.
	select {
	case <-w.connected:
	case <-time.After(20 * time.Second):
		return "", fmt.Errorf("o cliente ainda não conectou ao WhatsApp")
	}

	// The client name must use the "Browser (OS)" format; WhatsApp validates it
	// and returns 400 if it is not a known browser/OS.
	code, err := w.client.PairPhone(ctx, phone, true, whatsmeow.PairClientChrome, "Chrome (Android)")
	if err != nil {
		return "", err
	}
	return code, nil
}

// Logout signs out and clears the session.
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

// Events returns received events.
func (w *whatsmeowClient) Events() <-chan Event { return w.events }

// DownloadAudio downloads the voice note audio.
// If the CDN returns a wrong hash, send SendMediaRetryReceipt and wait
// for WhatsApp to resend the media with a new DirectPath (up to 30 seconds).
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

	// Retry attempt: ask the sender to resend the media.
	log.Printf("wabridge: download falhou (%v) — tentando media retry…", err)
	chatJID, parseErr := types.ParseJID(audio.ChatJID)
	if parseErr != nil || audio.ChatJID == "" {
		return nil, err // Not enough information to retry.
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

func (w *whatsmeowClient) SendAudio(ctx context.Context, number string, data []byte, mimetype string, seconds uint32) error {
	resp, err := w.client.Upload(ctx, data, whatsmeow.MediaAudio)
	if err != nil {
		log.Print("wabridge: PTT falha na etapa upload")
		return fmt.Errorf("upload: %w", err)
	}
	msg := buildAudioMessage(resp, mimetype, seconds)
	id := w.client.GenerateMessageID()
	// The receipt may arrive before SendMessage returns; timeout does not authorize resending.
	w.trackAudio(id, time.Now())
	log.Printf("wabridge: PTT envio msgID=%s mime=%s bytes=%d seconds=%d sidecar=%d",
		id, mimetype, resp.FileLength, seconds, len(resp.StreamingSidecar))

	jid := types.JID{User: number, Server: types.DefaultUserServer}
	sendResp, err := w.client.SendMessage(ctx, jid, &waE2E.Message{AudioMessage: msg}, whatsmeow.SendRequestExtra{ID: id})
	if err != nil {
		log.Printf("wabridge: PTT falha na etapa envio msgID=%s; entrega indeterminada, sem reenvio automático", id)
		return fmt.Errorf("send: %w", err)
	}
	log.Printf("wabridge: PTT msgID=%s ts=%d aceito pelo servidor; entrega não confirmada por este aceite",
		sendResp.ID, sendResp.Timestamp.Unix())
	return nil
}

func (w *whatsmeowClient) trackAudio(id types.MessageID, sentAt time.Time) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.sentAudio == nil {
		w.sentAudio = make(map[types.MessageID]time.Time)
	}
	if _, exists := w.sentAudio[id]; !exists && len(w.sentAudio) >= 32 {
		var oldest types.MessageID
		var oldestTime time.Time
		for candidate, timestamp := range w.sentAudio {
			if oldest == "" || timestamp.Before(oldestTime) || timestamp.Equal(oldestTime) && candidate < oldest {
				oldest, oldestTime = candidate, timestamp
			}
		}
		delete(w.sentAudio, oldest)
	}
	w.sentAudio[id] = sentAt
}

// This whatsmeow version calculates sidecars only for video; an empty audio sidecar is expected.
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
	}
}

// recentKey returns the conversation key (contact number, group JID), or "".
func (w *whatsmeowClient) recentKey(ctx context.Context, jid types.JID) string {
	switch jid.Server {
	case types.GroupServer:
		return jid.ToNonAD().String()
	case types.HiddenUserServer:
		if pn, err := w.client.Store.LIDs.GetPNForLID(ctx, jid); err == nil {
			return pn.User
		}
		return ""
	case types.DefaultUserServer:
		return jid.User
	default:
		// Broadcast, status, newsletter, bot — ignore.
		return ""
	}
}

// ContactEntry is a contact from the address book (app interactive list).
type ContactEntry struct {
	Nome         string `json:"name"`
	Numero       string `json:"number"`
	LastActivity int64  `json:"lastActivity"`
}

// GroupEntry is a WhatsApp group (app interactive list).
type GroupEntry struct {
	JID          string `json:"jid"`
	Nome         string `json:"name"`
	LastActivity int64  `json:"lastActivity"`
}

// Contacts lists the account's contacts and groups.
//
// The number is always resolved: WhatsApp sends a LID (@lid) for contacts
// not in the address book, so the LID→PN map is checked first.
func (w *whatsmeowClient) Contacts(ctx context.Context) ([]ContactEntry, []GroupEntry, error) {
	// Without a paired session, the store does not exist yet; return empty
	// results instead of triggering a nil-pointer panic in the HTTP handler.
	if w.client.Store == nil || w.client.Store.Contacts == nil {
		return []ContactEntry{}, []GroupEntry{}, nil
	}

	cs, err := w.client.Store.Contacts.GetAllContacts(ctx)
	if err != nil {
		return nil, nil, err
	}

	// Map for deduplication: number → index of the retained contact.
	seenNumbers := make(map[string]int)

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

		// Deduplication: if the number has been seen, keep the entry with a non-empty name.
		if idx, exists := seenNumbers[numero]; exists {
			if nome != "" && contatos[idx].Nome == "" {
				contatos[idx].Nome = nome
			}
			continue
		}

		lastActivity := w.recent.Get(numero)
		contatos = append(contatos, ContactEntry{
			Nome:         nome,
			Numero:       numero,
			LastActivity: lastActivity,
		})
		seenNumbers[numero] = len(contatos) - 1
	}

	// Sort by LastActivity descending; ties by lowercase name ascending.
	sortRecentContacts(contatos)

	// Group IQ requests fail when disconnected; return empty results instead of an error.
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
		lastActivity := w.recent.Get(g.JID.String())
		grupos = append(grupos, GroupEntry{
			JID:          g.JID.String(),
			Nome:         g.Name,
			LastActivity: lastActivity,
		})
	}
	sortRecentGroups(grupos)
	return contatos, grupos, nil
}

// sortRecentContacts sorts contacts by LastActivity descending; ties by lowercase name ascending.
func sortRecentContacts(cs []ContactEntry) {
	sort.Slice(cs, func(i, j int) bool {
		if cs[i].LastActivity != cs[j].LastActivity {
			return cs[i].LastActivity > cs[j].LastActivity
		}
		return strings.ToLower(cs[i].Nome) < strings.ToLower(cs[j].Nome)
	})
}

// sortRecentGroups sorts groups by LastActivity descending; ties by lowercase name ascending.
func sortRecentGroups(gs []GroupEntry) {
	sort.Slice(gs, func(i, j int) bool {
		if gs[i].LastActivity != gs[j].LastActivity {
			return gs[i].LastActivity > gs[j].LastActivity
		}
		return strings.ToLower(gs[i].Nome) < strings.ToLower(gs[j].Nome)
	})
}

// SendText sends a text message.
func (w *whatsmeowClient) SendText(ctx context.Context, number string, text string) error {
	jid := types.JID{User: number, Server: types.DefaultUserServer}
	_, err := w.client.SendMessage(ctx, jid, &waE2E.Message{Conversation: &text})
	return err
}

// Close closes the session.
func (w *whatsmeowClient) Close() {
	// Save activity history before closing.
	if w.recent != nil {
		if err := w.recent.SaveIfDirty(); err != nil {
			log.Printf("wabridge: erro salvando histórico ao fechar: %v", err)
		}
	}
	if w.client != nil {
		w.client.Disconnect()
	}
	if w.container != nil {
		w.container.Close()
	}
}

// onEvent translates whatsmeow events into bridge events.
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
	case *events.HistorySync:
		// Process conversation history (arrives shortly after pairing).
		ctx, cancel := context.WithTimeout(w.ctx, 30*time.Second)
		conversations := e.Data.GetConversations()
		for _, c := range conversations {
			if c == nil {
				continue
			}
			jid, err := types.ParseJID(c.GetID())
			if err != nil {
				continue
			}
			ts := max(c.GetConversationTimestamp(), c.GetLastMsgTimestamp())
			w.recent.Touch(w.recentKey(ctx, jid), int64(ts))
		}
		cancel()
		if len(conversations) > 0 {
			log.Printf("wabridge: history sync %d conversations", len(conversations))
		}
	case *events.Receipt:
		for _, id := range e.MessageIDs {
			w.mu.Lock()
			_, tracked := w.sentAudio[id]
			w.mu.Unlock()
			if tracked {
				log.Printf("wabridge: PTT recibo msgID=%s tipo=%s ts=%d", id, e.Type.GoString(), e.Timestamp.Unix())
			}
		}
	case *events.Message:
		msg := e.Message
		if msg == nil {
			return
		}

		// Record activity before processing audio.
		ctx, cancel := context.WithTimeout(w.ctx, 5*time.Second)
		w.recent.Touch(w.recentKey(ctx, e.Info.Chat), e.Info.Timestamp.Unix())
		cancel()

		audio := msg.GetAudioMessage()
		if audio == nil {
			return
		}
		jid := e.Info.Chat

		// WhatsApp sends the correspondent as a LID (@lid) when the contact
		// is not in the address book; the real number exists only in the LID→PN map.
		// Without resolving it, the voice note would be dropped by the server filter.
		if jid.Server == types.HiddenUserServer {
			ctx, cancel := context.WithTimeout(w.ctx, 5*time.Second)
			if pn, err := w.client.Store.LIDs.GetPNForLID(ctx, jid); err == nil {
				jid = pn
			}
			cancel()
		}

		// Ignore messages sent by the user
		// (WABRIDGE_ACCEPT_OWN=1 disables this filter for testing).
		if e.Info.IsFromMe && os.Getenv("WABRIDGE_ACCEPT_OWN") == "" {
			return
		}
		// Private conversations only (groups and newsletters are excluded).
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

// logger is a simple adapter for the whatsmeow logger.
type logger struct{}

func newLogger() waLog.Logger { return &logger{} }

func (l *logger) Warnf(msg string, args ...any)  { log.Printf("WARN  "+msg, args...) }
func (l *logger) Errorf(msg string, args ...any) { log.Printf("ERROR "+msg, args...) }
func (l *logger) Infof(msg string, args ...any)  { log.Printf("INFO  "+msg, args...) }
func (l *logger) Debugf(msg string, args ...any) {}
func (l *logger) Sub(module string) waLog.Logger { return l }
