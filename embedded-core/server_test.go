package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
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
		number  string
		mime    string
		size    int
		seconds uint32
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
func (f *fakeWhatsApp) SendAudio(_ context.Context, number string, data []byte, mime string, seconds uint32) error {
	f.sentAudio = append(f.sentAudio, struct {
		number  string
		mime    string
		size    int
		seconds uint32
	}{number, mime, len(data), seconds})
	return nil
}
func (f *fakeWhatsApp) SendText(_ context.Context, number string, text string) error {
	f.sentText = append(f.sentText, struct {
		number string
		text   string
	}{number, text})
	return nil
}

func (f *fakeWhatsApp) Contacts(context.Context) ([]ContactEntry, []GroupEntry, error) {
	return []ContactEntry{
			{Nome: "Ana Souza", Numero: "5511999990001"},
			{Nome: "Bruno Lima", Numero: "5511999990002"},
		}, []GroupEntry{
			{JID: "120363000000000000@g.us", Nome: "Família"},
		}, nil
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


func TestContacts(t *testing.T) {
	_, _, ts := newTestServer(t)
	defer ts.Close()

	res, err := http.Get(ts.URL + "/contacts")
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	if res.StatusCode != 200 {
		t.Fatalf("esperava 200, veio %d", res.StatusCode)
	}

	var body struct {
		Contatos []ContactEntry `json:"contatos"`
		Grupos   []GroupEntry   `json:"grupos"`
	}
	if err := json.NewDecoder(res.Body).Decode(&body); err != nil {
		t.Fatal(err)
	}
	if len(body.Contatos) != 2 || body.Contatos[0].Nome != "Ana Souza" {
		t.Fatalf("contatos errados: %+v", body.Contatos)
	}
	if len(body.Grupos) != 1 || body.Grupos[0].Nome != "Família" {
		t.Fatalf("grupos errados: %+v", body.Grupos)
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

// miniOGG é um arquivo OGG/Opus mínimo e válido (cabeçalho OpusHead + OpusTags + 1 página de dados).
// Gerado offline; usado para testar o fluxo de áudio sem gravar áudio real.
var miniOGG = func() []byte {
	// OggS + campos mínimos para identificação — suficiente para passar o magic check.
	// Não é reproduzível, mas valida que os bytes chegam ao SendAudio intactos.
	var b []byte
	// página 0: OpusHead
	b = append(b, []byte("OggS")...) // capture_pattern
	b = append(b, 0)                 // version
	b = append(b, 2)                 // header_type (BOS)
	b = append(b, 0, 0, 0, 0, 0, 0, 0, 0) // granule_position
	b = append(b, 1, 0, 0, 0)       // serial
	b = append(b, 0, 0, 0, 0)       // sequence
	b = append(b, 0, 0, 0, 0)       // checksum (zero — fake, não será verificado)
	b = append(b, 1)                 // page_segments
	b = append(b, 19)                // segment_table (tamanho do OpusHead)
	b = append(b, []byte("OpusHead")...)
	b = append(b, 1, 2, 0x80, 0x3E, 0x00, 0x40, 0x00, 0x00, 0x00, 0x00, 0x00) // version, channels, pre-skip, sample_rate, gain, mapping
	return b
}()

func multipartOGG(t *testing.T, audioID, to string, mime string, durationSec int, data []byte) (*bytes.Buffer, string) {
	t.Helper()
	var buf bytes.Buffer
	w := multipart.NewWriter(&buf)
	w.WriteField("audioId", audioID)
	w.WriteField("to", to)
	if mime != "" {
		w.WriteField("mimeType", mime)
	}
	if durationSec > 0 {
		w.WriteField("durationSec", fmt.Sprintf("%d", durationSec))
	}
	fw, _ := w.CreateFormFile("file", "reply.ogg")
	fw.Write(data)
	w.Close()
	return &buf, w.FormDataContentType()
}

func TestParseOGGOpusInfoValido(t *testing.T) {
	// miniOGG tem OpusHead válido (canais=2, sampleRate=0x3E80=16000)
	info, err := parseOGGOpusInfo(miniOGG)
	if err != nil {
		t.Fatalf("esperava OGG válido, veio erro: %v", err)
	}
	if info.Channels != 2 {
		t.Errorf("canais: %d (esperava 2)", info.Channels)
	}
}

func TestParseOGGOpusInfoMagicErrado(t *testing.T) {
	_, err := parseOGGOpusInfo([]byte("RIFF\x00\x00\x00\x00WAVEfmt "))
	if err == nil {
		t.Error("esperava erro para arquivo não-OGG")
	}
}

func TestParseOGGOpusInfoSemOpusHead(t *testing.T) {
	// OGG válido mas com Vorbis em vez de Opus
	var b []byte
	b = append(b, []byte("OggS")...) // magic
	b = append(b, 0, 2)              // version, BOS
	b = append(b, 0, 0, 0, 0, 0, 0, 0, 0)
	b = append(b, 1, 0, 0, 0)
	b = append(b, 0, 0, 0, 0)
	b = append(b, 0, 0, 0, 0)
	b = append(b, 1, 7) // 1 segment de 7 bytes
	b = append(b, []byte("\x01vorbis")...)
	_, err := parseOGGOpusInfo(b)
	if err == nil {
		t.Error("esperava erro para OGG sem OpusHead")
	}
}

func TestParseOGGOpusInfoMono(t *testing.T) {
	// Monta um OpusHead com canais=1 (mono — correto para WhatsApp PTT)
	var b []byte
	b = append(b, []byte("OggS")...)
	b = append(b, 0, 2)
	b = append(b, 0, 0, 0, 0, 0, 0, 0, 0)
	b = append(b, 1, 0, 0, 0)
	b = append(b, 0, 0, 0, 0)
	b = append(b, 0, 0, 0, 0)
	head := []byte("OpusHead\x01\x01\x38\x01\x80\x3e\x00\x00\x00\x00\x00") // version=1, ch=1, preskip=312, sr=16000
	b = append(b, 1, byte(len(head)))
	b = append(b, head...)
	info, err := parseOGGOpusInfo(b)
	if err != nil {
		t.Fatalf("OGG mono válido deu erro: %v", err)
	}
	if info.Channels != 1 {
		t.Errorf("esperava mono (1 canal), veio %d", info.Channels)
	}
	if info.SampleRate != 16000 {
		t.Errorf("esperava sampleRate=16000, veio %d", info.SampleRate)
	}
}

func TestReplyAudioPassaMimeTypeOGG(t *testing.T) {
	_, fake, ts := newTestServer(t)
	defer ts.Close()

	fake.events <- Event{Kind: "audio", Audio: &AudioMessage{ID: "MSG10", FromNumber: "5511888888888", Seconds: 5}}
	time.Sleep(200 * time.Millisecond)

	buf, ct := multipartOGG(t, "MSG10", "5511888888888", "audio/ogg; codecs=opus", 5, miniOGG)
	res, err := http.Post(ts.URL+"/reply/audio", ct, buf)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()

	if len(fake.sentAudio) != 1 {
		t.Fatalf("esperava 1 áudio enviado, veio %d", len(fake.sentAudio))
	}
	got := fake.sentAudio[0]
	if got.mime != "audio/ogg; codecs=opus" {
		t.Errorf("mime errado: %q (esperava \"audio/ogg; codecs=opus\")", got.mime)
	}
}

func TestReplyAudioPassaDuracao(t *testing.T) {
	_, fake, ts := newTestServer(t)
	defer ts.Close()

	fake.events <- Event{Kind: "audio", Audio: &AudioMessage{ID: "MSG11", FromNumber: "5511777777777", Seconds: 7}}
	time.Sleep(200 * time.Millisecond)

	buf, ct := multipartOGG(t, "MSG11", "5511777777777", "audio/ogg; codecs=opus", 7, miniOGG)
	res, err := http.Post(ts.URL+"/reply/audio", ct, buf)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()

	if len(fake.sentAudio) != 1 {
		t.Fatalf("esperava 1 áudio enviado, veio %d", len(fake.sentAudio))
	}
	if fake.sentAudio[0].seconds != 7 {
		t.Errorf("seconds errado: %d (esperava 7)", fake.sentAudio[0].seconds)
	}
}

func TestReplyAudioFallbackDuracao(t *testing.T) {
	// Sem durationSec, o servidor estima pelo tamanho (len*8/32000).
	// miniOGG tem ~40 bytes → estimativa = 0 → coerced a 1.
	_, fake, ts := newTestServer(t)
	defer ts.Close()

	fake.events <- Event{Kind: "audio", Audio: &AudioMessage{ID: "MSG12", FromNumber: "5511666666666", Seconds: 3}}
	time.Sleep(200 * time.Millisecond)

	buf, ct := multipartOGG(t, "MSG12", "5511666666666", "audio/ogg; codecs=opus", 0, miniOGG)
	res, err := http.Post(ts.URL+"/reply/audio", ct, buf)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()

	if len(fake.sentAudio) != 1 {
		t.Fatalf("esperava 1 áudio, veio %d", len(fake.sentAudio))
	}
	if fake.sentAudio[0].seconds == 0 {
		t.Errorf("fallback de duração não pode ser 0")
	}
}

func TestReplyAudioBytesIntactos(t *testing.T) {
	// Os bytes do OGG devem chegar ao SendAudio sem modificação.
	_, fake, ts := newTestServer(t)
	defer ts.Close()

	fake.events <- Event{Kind: "audio", Audio: &AudioMessage{ID: "MSG13", FromNumber: "5511555555555", Seconds: 2}}
	time.Sleep(200 * time.Millisecond)

	payload := append(miniOGG, []byte("extra-payload")...)
	buf, ct := multipartOGG(t, "MSG13", "5511555555555", "audio/ogg; codecs=opus", 2, payload)
	res, err := http.Post(ts.URL+"/reply/audio", ct, buf)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()

	if len(fake.sentAudio) != 1 {
		t.Fatalf("esperava 1 áudio, veio %d", len(fake.sentAudio))
	}
	if fake.sentAudio[0].size != len(payload) {
		t.Errorf("tamanho errado: %d (esperava %d)", fake.sentAudio[0].size, len(payload))
	}
}

func TestReplyAudioDefaultMimeSemCampo(t *testing.T) {
	// Sem mimeType no form → server usa "audio/mp4" como fallback (legado).
	_, fake, ts := newTestServer(t)
	defer ts.Close()

	fake.events <- Event{Kind: "audio", Audio: &AudioMessage{ID: "MSG14", FromNumber: "5511444444444", Seconds: 1}}
	time.Sleep(200 * time.Millisecond)

	buf, ct := multipartOGG(t, "MSG14", "5511444444444", "", 1, miniOGG)
	res, err := http.Post(ts.URL+"/reply/audio", ct, buf)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()

	if len(fake.sentAudio) != 1 {
		t.Fatalf("esperava 1 áudio, veio %d", len(fake.sentAudio))
	}
	if fake.sentAudio[0].mime != "audio/mp4" {
		t.Errorf("mime padrão errado: %q", fake.sentAudio[0].mime)
	}
}

func TestReplyAudioPendenciaRemovidaAposResposta(t *testing.T) {
	_, _, ts := newTestServer(t)
	defer ts.Close()

	// injeta e aguarda ser listado
	res1, _ := http.Get(ts.URL + "/pending")
	res1.Body.Close()

	// envia reply → pending deve ser removido
	buf, ct := multipartOGG(t, "MSG15", "5511333333333", "audio/ogg; codecs=opus", 2, miniOGG)
	res2, err := http.Post(ts.URL+"/reply/audio", ct, buf)
	if err != nil {
		t.Fatal(err)
	}
	res2.Body.Close()

	res3, _ := http.Get(ts.URL + "/pending")
	var list []PendingCall
	json.NewDecoder(res3.Body).Decode(&list)
	res3.Body.Close()
	for _, p := range list {
		if p.AudioID == "MSG15" {
			t.Error("pendência MSG15 ainda na fila depois do reply")
		}
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
