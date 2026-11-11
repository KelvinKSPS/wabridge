package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// PendingCall é uma nota de voz que chegou e ainda não foi respondida.
type PendingCall struct {
	AudioID      string `json:"audioId"`
	SenderNumber string `json:"senderNumber"`
	SenderName   string `json:"senderName"`
	CreatedAt    int64  `json:"createdAt"`
}

// CallPayload é o evento NEW_VOICE_NOTE enviado ao app (mesmo formato do
// servidor Node).
type CallPayload struct {
	Type         string `json:"type"`
	AudioID      string `json:"audioId"`
	SenderName   string `json:"senderName"`
	SenderNumber string `json:"senderNumber"`
	AudioURL     string `json:"audioUrl"`
	DurationSec  int    `json:"durationSec"`
	MimeType     string `json:"mimeType"`
}

// Server é o daemon HTTP/WebSocket local.
type Server struct {
	wa      WhatsApp
	cfg     *ConfigStore
	dir     string
	port    int
	baseURL string

	mu      sync.Mutex
	pending map[string]PendingCall
	media   map[string][]byte
	clients map[*websocket.Conn]bool

	upgrader websocket.Upgrader
}

// NewServer cria o daemon.
func NewServer(wa WhatsApp, cfg *ConfigStore, dir string, port int) *Server {
	return &Server{
		wa:      wa,
		cfg:     cfg,
		dir:     dir,
		port:    port,
		baseURL: fmt.Sprintf("http://127.0.0.1:%d", port),
		pending: map[string]PendingCall{},
		media:   map[string][]byte{},
		clients: map[*websocket.Conn]bool{},
		upgrader: websocket.Upgrader{
			CheckOrigin: func(r *http.Request) bool { return true },
		},
	}
}

// Run sobe o servidor e processa os eventos do WhatsApp.
func (s *Server) Run() error {
	srv := &http.Server{Addr: fmt.Sprintf("127.0.0.1:%d", s.port), Handler: s.routes()}
	log.Printf("wabridge: escutando em %s", srv.Addr)
	return srv.ListenAndServe()
}

// routes monta o mux de rotas (mesmo contrato do servidor Node).
func (s *Server) routes() http.Handler {
	go s.consumeEvents()

	mux := http.NewServeMux()
	mux.HandleFunc("/health", s.handleHealth)
	mux.HandleFunc("/pending", s.handlePending)
	mux.HandleFunc("/media/", s.handleMedia)
	mux.HandleFunc("/reply/audio", s.handleReplyAudio)
	mux.HandleFunc("/reply/text", s.handleReplyText)
	mux.HandleFunc("/auth/pair-code", s.handlePairCode)
	mux.HandleFunc("/auth/status", s.handleAuthStatus)
	mux.HandleFunc("/auth/logout", s.handleAuthLogout)
	mux.HandleFunc("/qrcode.png", s.handleQRCodePNG)
	mux.HandleFunc("/qrcode.json", s.handleQRCodeJSON)
	mux.HandleFunc("/config/allowed-numbers", s.handleAllowedNumbers)
	mux.HandleFunc("/contacts", s.handleContacts)
	mux.HandleFunc("/ws", s.handleWS)
	return mux
}

/* ------------------------------------------------------------------ *
 * Eventos do WhatsApp -> WebSocket
 * ------------------------------------------------------------------ */

func (s *Server) consumeEvents() {
	for ev := range s.wa.Events() {
		if ev.Kind != "audio" || ev.Audio == nil {
			continue
		}
		audio := *ev.Audio
		if !IsAllowed(s.cfg.Allowed(), audio.FromNumber) {
			log.Printf("wabridge: remetente bloqueado (%s)", audio.FromNumber)
			continue
		}
		data, err := s.wa.DownloadAudio(contextTODO(), audio)
		if err != nil {
			log.Printf("wabridge: falha ao baixar o áudio: %v", err)
			continue
		}
		s.mu.Lock()
		s.media[audio.ID] = data
		s.pending[audio.ID] = PendingCall{
			AudioID:      audio.ID,
			SenderNumber: audio.FromNumber,
			SenderName:   audio.FromName,
			CreatedAt:    time.Now().UnixMilli(),
		}
		s.mu.Unlock()

		payload := CallPayload{
			Type:         "NEW_VOICE_NOTE",
			AudioID:      audio.ID,
			SenderName:   audio.FromName,
			SenderNumber: audio.FromNumber,
			AudioURL:     fmt.Sprintf("%s/media/%s.ogg", s.baseURL, audio.ID),
			DurationSec:  audio.Seconds,
			MimeType:     audio.Mimetype,
		}
		log.Printf("wabridge: áudio de %s (%ds)", audio.FromNumber, audio.Seconds)
		s.broadcast(payload)
	}
}

func (s *Server) broadcast(payload CallPayload) {
	data, _ := json.Marshal(payload)
	s.mu.Lock()
	defer s.mu.Unlock()
	for c := range s.clients {
		if err := c.WriteMessage(websocket.TextMessage, data); err != nil {
			delete(s.clients, c)
		}
	}
}

/* ------------------------------------------------------------------ *
 * Rotas
 * ------------------------------------------------------------------ */

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	clients := len(s.clients)
	pending := len(s.pending)
	s.mu.Unlock()
	writeJSON(w, 200, map[string]any{"ok": true, "clients": clients, "pending": pending})
}

func (s *Server) handlePending(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	list := make([]PendingCall, 0, len(s.pending))
	for _, p := range s.pending {
		list = append(list, p)
	}
	s.mu.Unlock()
	writeJSON(w, 200, list)
}

// handleMedia serve o áudio (e o tom sintético de teste).
func (s *Server) handleMedia(w http.ResponseWriter, r *http.Request) {
	id := strings.TrimPrefix(r.URL.Path, "/media/")
	id = strings.TrimSuffix(strings.TrimSuffix(id, ".ogg"), ".wav")
	if id == "" {
		http.NotFound(w, r)
		return
	}

	if id == "test" {
		wav := generateToneWav(440, 2)
		w.Header().Set("Content-Type", "audio/wav")
		w.Write(wav)
		return
	}

	s.mu.Lock()
	data, ok := s.media[id]
	s.mu.Unlock()
	if !ok {
		writeJSON(w, 404, map[string]string{"error": "áudio não encontrado ou expirado"})
		return
	}
	w.Header().Set("Content-Type", "audio/ogg")
	w.Write(data)
}

func (s *Server) handleReplyAudio(w http.ResponseWriter, r *http.Request) {
	if err := r.ParseMultipartForm(25 << 20); err != nil {
		writeJSON(w, 400, map[string]string{"error": "formato inválido"})
		return
	}
	audioID := r.FormValue("audioId")
	to := onlyDigits(r.FormValue("to"))
	if to == "" {
		s.mu.Lock()
		if p, ok := s.pending[audioID]; ok {
			to = p.SenderNumber
		}
		s.mu.Unlock()
	}
	if to == "" {
		writeJSON(w, 400, map[string]string{"error": "destinatário ausente"})
		return
	}
	file, _, err := r.FormFile("file")
	if err != nil {
		writeJSON(w, 400, map[string]string{"error": "arquivo de áudio ausente (campo \"file\")"})
		return
	}
	defer file.Close()

	buf := make([]byte, 0)
	tmp := make([]byte, 32*1024)
	for {
		n, err := file.Read(tmp)
		if n > 0 {
			buf = append(buf, tmp[:n]...)
		}
		if err != nil {
			break
		}
	}

	mime := r.FormValue("mimeType")
	if mime == "" {
		mime = "audio/mp4"
	}
	if err := s.wa.SendAudio(contextTODO(), to, buf, mime); err != nil {
		log.Printf("wabridge: erro ao enviar áudio: %v", err)
		writeJSON(w, 502, map[string]string{"error": err.Error()})
		return
	}
	if audioID != "" {
		s.mu.Lock()
		delete(s.pending, audioID)
		s.mu.Unlock()
	}
	log.Printf("wabridge: resposta de áudio enviada para %s (%d bytes)", to, len(buf))
	writeJSON(w, 200, map[string]any{"ok": true, "to": to})
}

func (s *Server) handleReplyText(w http.ResponseWriter, r *http.Request) {
	var body struct {
		AudioID string `json:"audioId"`
		To      string `json:"to"`
		Text    string `json:"text"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeJSON(w, 400, map[string]string{"error": "json inválido"})
		return
	}
	text := strings.TrimSpace(body.Text)
	if text == "" {
		writeJSON(w, 400, map[string]string{"error": "texto ausente (campo \"text\")"})
		return
	}
	to := onlyDigits(body.To)
	if to == "" {
		s.mu.Lock()
		if p, ok := s.pending[body.AudioID]; ok {
			to = p.SenderNumber
		}
		s.mu.Unlock()
	}
	if to == "" {
		writeJSON(w, 400, map[string]string{"error": "destinatário ausente"})
		return
	}
	if err := s.wa.SendText(contextTODO(), to, text); err != nil {
		writeJSON(w, 502, map[string]string{"error": err.Error()})
		return
	}
	if body.AudioID != "" {
		s.mu.Lock()
		delete(s.pending, body.AudioID)
		s.mu.Unlock()
	}
	log.Printf("wabridge: texto enviado para %s: %q", to, text)
	writeJSON(w, 200, map[string]any{"ok": true, "to": to})
}

func (s *Server) handlePairCode(w http.ResponseWriter, r *http.Request) {
	var body struct {
		PhoneNumber string `json:"phoneNumber"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeJSON(w, 400, map[string]string{"error": "json inválido"})
		return
	}
	phone := onlyDigits(body.PhoneNumber)
	if len(phone) < 8 {
		writeJSON(w, 400, map[string]string{"error": "telefone inválido"})
		return
	}
	code, err := s.wa.PairCode(contextTODO(), phone)
	if err != nil {
		writeJSON(w, 502, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, 200, map[string]string{"code": code})
}

func (s *Server) handleAuthStatus(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, s.wa.Status())
}

func (s *Server) handleAuthLogout(w http.ResponseWriter, r *http.Request) {
	if err := s.wa.Logout(contextTODO()); err != nil {
		writeJSON(w, 502, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}

func (s *Server) handleQRCodeJSON(w http.ResponseWriter, r *http.Request) {
	st := s.wa.Status()
	if st.QR == "" {
		writeJSON(w, 404, map[string]string{"error": "sem QR code"})
		return
	}
	writeJSON(w, 200, map[string]string{"base64": st.QR})
}

func (s *Server) handleQRCodePNG(w http.ResponseWriter, r *http.Request) {
	st := s.wa.Status()
	if st.QR == "" {
		writeJSON(w, 404, map[string]string{"error": "sem QR code"})
		return
	}
	data, err := decodeBase64(st.QR)
	if err != nil {
		writeJSON(w, 500, map[string]string{"error": err.Error()})
		return
	}
	w.Header().Set("Content-Type", "image/png")
	w.Write(data)
}

func (s *Server) handleAllowedNumbers(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodGet {
		writeJSON(w, 200, map[string]any{
			"allowedNumbers": s.cfg.Allowed(),
			"acceptAll":      len(s.cfg.Allowed()) == 0,
		})
		return
	}
	var body struct {
		AllowedNumbers []string `json:"allowedNumbers"`
		AcceptAll      bool     `json:"acceptAll"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeJSON(w, 400, map[string]string{"error": "json inválido"})
		return
	}
	if err := s.cfg.SetAllowed(body.AllowedNumbers, body.AcceptAll); err != nil {
		writeJSON(w, 500, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, 200, map[string]any{"ok": true, "allowedNumbers": s.cfg.Allowed()})
}


// handleContacts devolve contatos e grupos para a lista interativa do app.
func (s *Server) handleContacts(w http.ResponseWriter, r *http.Request) {
	if s.wa == nil {
		writeJSON(w, 503, map[string]string{"error": "daemon não pronto"})
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
	defer cancel()

	contatos, grupos, err := s.wa.Contacts(ctx)
	if err != nil {
		writeJSON(w, 500, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, 200, map[string]any{
		"contatos": contatos,
		"grupos":   grupos,
	})
}

func (s *Server) handleWS(w http.ResponseWriter, r *http.Request) {
	conn, err := s.upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("wabridge: falha no upgrade do websocket: %v", err)
		return
	}
	s.mu.Lock()
	s.clients[conn] = true
	s.mu.Unlock()

	hello, _ := json.Marshal(map[string]any{"type": "HELLO"})
	conn.WriteMessage(websocket.TextMessage, hello)

	defer func() {
		s.mu.Lock()
		delete(s.clients, conn)
		s.mu.Unlock()
		conn.Close()
	}()

	for {
		msgType, data, err := conn.ReadMessage()
		if err != nil {
			return
		}
		if msgType == websocket.TextMessage && strings.TrimSpace(string(data)) == "ping" {
			conn.WriteMessage(websocket.TextMessage, []byte("pong"))
		}
	}
}

/* ------------------------------------------------------------------ *
 * Utilidades
 * ------------------------------------------------------------------ */

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

// dataDir cria (se preciso) o diretório de dados do app.
func dataDir(dir string) (string, error) {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", err
	}
	return filepath.Abs(dir)
}

func itoa(v int) string { return strconv.Itoa(v) }
