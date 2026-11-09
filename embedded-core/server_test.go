package main

import (
	"bytes"
	"context"
	"encoding/json"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// fakeWhatsApp é um motor de mentira: os testes não tocam no WhatsApp.
type fakeWhatsApp struct {
	events    chan Event
	sentAudio []struct {
		number string
		mime   string
		size   int
	}
	sentText []struct {
		number string
		text   string
	}
	code    string
	pairErr error
	logged  bool
}

func newFake() *fakeWhatsApp {
	return &fakeWhatsApp{events: make(chan Event, 8), code: "ABCD-1234"}
}

func (f *fakeWhatsApp) Connect(context.Context) error { return nil }
func (f *fakeWhatsApp) Status() Status {
	return Status{Connected: true, LoggedIn: f.logged, JID: "5511999999999@s.whatsapp.net", Phone: "5511999999999"}
}
func (f *fakeWhatsApp) PairCode(_ context.Context, _ string) (string, error) {
	if f.pairErr != nil {
		return "", f.pairErr
	}
	return f.code, nil
}
func (f *fakeWhatsApp) Logout(context.Context) error { f.logged = false; return nil }
func (f *fakeWhatsApp) Events() <-chan Event          { return f.events }
func (f *fakeWhatsApp) DownloadAudio(_ context.Context, _ AudioMessage) ([]byte, error) {
	return []byte("opus-falso"), nil
}
func (f *fakeWhatsApp) SendAudio(_ context.Context, number string, data []byte, mime string) error {
	f.sentAudio = append(f.sentAudio, struct {
		number string
		mime   string
		size   int
	}{number, mime, len(data)})
	return nil
}
func (f *fakeWhatsApp) SendText(_ context.Context, number string, text string) error {
	f.sentText = append(f.sentText, struct {
		number string
		text   string
	}{number, text})
	return nil
}

func newTestServer(t *testing.T) (*Server, *fakeWhatsApp, *httptest.Server) {
	t.Helper()
	fake := newFake()
	cfg, err := NewConfigStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	s := NewServer(fake, cfg, t.TempDir(), 0)
	ts := httptest.NewServer(s.routes())
	return s, fake, ts
}

func TestHealth(t *testing.T) {
	_, _, ts := newTestServer(t)
	defer ts.Close()

	res, err := http.Get(ts.URL + "/health")
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()

	var body map[string]any
	if err := json.NewDecoder(res.Body).Decode(&body); err != nil {
		t.Fatal(err)
	}
	if body["ok"] != true {
		t.Fatalf("esperava ok=true, veio %v", body["ok"])
	}
}

func TestAudioViraChamadaEresponde(t *testing.T) {
	s, fake, ts := newTestServer(t)
	defer ts.Close()

	fake.events <- Event{Kind: "audio", Audio: &AudioMessage{
		ID: "MSG1", FromNumber: "5511999999999", FromName: "Sara", Seconds: 12,
	}}

	// o payload precisa aparecer em /pending
	deadline := time.Now().Add(3 * time.Second)
	var pend []PendingCall
	for time.Now().Before(deadline) {
		res, _ := http.Get(ts.URL + "/pending")
		var list []PendingCall
		json.NewDecoder(res.Body).Decode(&list)
		res.Body.Close()
		if len(list) > 0 {
			pend = list
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	if len(pend) != 1 {
		t.Fatalf("esperava 1 pendência, veio %d", len(pend))
	}
	if pend[0].SenderName != "Sara" || pend[0].SenderNumber != "5511999999999" {
		t.Fatalf("pendência errada: %+v", pend[0])
	}

	// e o áudio tem que estar disponível em /media
	res, err := http.Get(ts.URL + "/media/MSG1.ogg")
	if err != nil {
		t.Fatal(err)
	}
	body := make([]byte, 10)
	res.Body.Read(body)
	res.Body.Close()
	if string(body) != "opus-falso" {
		t.Fatalf("áudio inesperado: %q", body)
	}
	_ = s
}

func TestReplyAudioEnviaParaORemendente(t *testing.T) {
	_, fake, ts := newTestServer(t)
	defer ts.Close()

	fake.events <- Event{Kind: "audio", Audio: &AudioMessage{ID: "MSG2", FromNumber: "5511888888888", Seconds: 3}}
	time.Sleep(300 * time.Millisecond)

	// o endpoint recebe multipart (como no servidor Node)
	var buf bytes.Buffer
	w := multipart.NewWriter(&buf)
	w.WriteField("audioId", "MSG2")
	w.WriteField("to", "5511888888888")
	fw, _ := w.CreateFormFile("file", "reply.m4a")
	fw.Write([]byte("audio-gravado"))
	w.Close()

	res, err := http.Post(ts.URL+"/reply/audio", w.FormDataContentType(), &buf)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()

	if len(fake.sentAudio) != 1 {
		t.Fatalf("esperava 1 áudio enviado, veio %d", len(fake.sentAudio))
	}
	if fake.sentAudio[0].number != "5511888888888" {
		t.Fatalf("número errado: %s", fake.sentAudio[0].number)
	}
}

func TestReplyTextEnviaMensagem(t *testing.T) {
	_, fake, ts := newTestServer(t)
	defer ts.Close()

	res, err := http.Post(ts.URL+"/reply/text", "application/json",
		strings.NewReader(`{"to":"5511999999999","text":"Não posso atender"}`))
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()

	if len(fake.sentText) != 1 || fake.sentText[0].text != "Não posso atender" {
		t.Fatalf("texto não enviado: %+v", fake.sentText)
	}
}

func TestReplyTextSemTextoERecusado(t *testing.T) {
	_, _, ts := newTestServer(t)
	defer ts.Close()

	res, err := http.Post(ts.URL+"/reply/text", "application/json", strings.NewReader(`{"to":"5511999999999"}`))
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	if res.StatusCode != 400 {
		t.Fatalf("esperava 400, veio %d", res.StatusCode)
	}
}

func TestPairCode(t *testing.T) {
	_, _, ts := newTestServer(t)
	defer ts.Close()

	res, err := http.Post(ts.URL+"/auth/pair-code", "application/json",
		strings.NewReader(`{"phoneNumber":"5511999999999"}`))
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()

	var body map[string]string
	json.NewDecoder(res.Body).Decode(&body)
	if body["code"] != "ABCD-1234" {
		t.Fatalf("código errado: %v", body)
	}
}

func TestPairCodeTelefoneInvalido(t *testing.T) {
	_, _, ts := newTestServer(t)
	defer ts.Close()

	res, err := http.Post(ts.URL+"/auth/pair-code", "application/json",
		strings.NewReader(`{"phoneNumber":"123"}`))
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	if res.StatusCode != 400 {
		t.Fatalf("esperava 400, veio %d", res.StatusCode)
	}
}

func TestAuthStatus(t *testing.T) {
	_, _, ts := newTestServer(t)
	defer ts.Close()

	res, _ := http.Get(ts.URL + "/auth/status")
	defer res.Body.Close()
	var st Status
	json.NewDecoder(res.Body).Decode(&st)
	if !st.Connected || st.JID != "5511999999999@s.whatsapp.net" {
		t.Fatalf("status errado: %+v", st)
	}
}

func TestAllowedNumbers(t *testing.T) {
	_, _, ts := newTestServer(t)
	defer ts.Close()

	res, err := http.Post(ts.URL+"/config/allowed-numbers", "application/json",
		strings.NewReader(`{"allowedNumbers":["5511999999999"]}`))
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()

	res, _ = http.Get(ts.URL + "/config/allowed-numbers")
	defer res.Body.Close()
	var body map[string]any
	json.NewDecoder(res.Body).Decode(&body)
	if body["acceptAll"] != false {
		t.Fatalf("deveria filtrar: %v", body)
	}
}

func TestRemetenteBloqueadoNaoEntra(t *testing.T) {
	s, fake, ts := newTestServer(t)
	defer ts.Close()

	s.cfg.SetAllowed([]string{"5511999999999"}, false)
	fake.events <- Event{Kind: "audio", Audio: &AudioMessage{ID: "MSG3", FromNumber: "5511888888888"}}
	time.Sleep(300 * time.Millisecond)

	res, _ := http.Get(ts.URL + "/pending")
	defer res.Body.Close()
	var list []PendingCall
	json.NewDecoder(res.Body).Decode(&list)
	if len(list) != 0 {
		t.Fatalf("remetente bloqueado entrou: %+v", list)
	}
}

func TestMediaTestWav(t *testing.T) {
	_, _, ts := newTestServer(t)
	defer ts.Close()

	res, err := http.Get(ts.URL + "/media/test.wav")
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	buf := make([]byte, 44)
	res.Body.Read(buf)
	if string(buf[0:4]) != "RIFF" {
		t.Fatalf("não é um wav: %q", buf[0:4])
	}
}
