package main

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"
)

// fakeWhatsApp is a fake engine: tests do not interact with WhatsApp.
type fakeWhatsApp struct {
	events    chan Event
	sentAudio []struct {
		number  string
		mime    string
		size    int
		seconds uint32
		data    []byte
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
func (f *fakeWhatsApp) Events() <-chan Event         { return f.events }
func (f *fakeWhatsApp) DownloadAudio(_ context.Context, _ AudioMessage) ([]byte, error) {
	return []byte("opus-falso"), nil
}
func (f *fakeWhatsApp) SendAudio(_ context.Context, number string, data []byte, mime string, seconds uint32) error {
	f.sentAudio = append(f.sentAudio, struct {
		number  string
		mime    string
		size    int
		seconds uint32
		data    []byte
	}{number, mime, len(data), seconds, append([]byte(nil), data...)})
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

	// The payload must appear in /pending
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

	// And the audio must be available at /media
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

	// The endpoint receives multipart (as in the Node server)
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

	// The JSON must expose lastActivity (0 when there is no data)
	res2, err := http.Get(ts.URL + "/contacts")
	if err != nil {
		t.Fatal(err)
	}
	defer res2.Body.Close()
	raw, err := io.ReadAll(res2.Body)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(raw), "lastActivity") {
		t.Fatalf("JSON de /contacts não tem lastActivity: %s", raw)
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

func buildTestOGG(packets ...[]byte) []byte {
	writePage := func(sequence uint32, flags byte, granule uint64, packets [][]byte) []byte {
		header := make([]byte, 27)
		copy(header, "OggS")
		header[5] = flags
		binary.LittleEndian.PutUint64(header[6:14], granule)
		binary.LittleEndian.PutUint32(header[14:18], 42)
		binary.LittleEndian.PutUint32(header[18:22], sequence)
		var table []byte
		var body bytes.Buffer
		for _, packet := range packets {
			for length := len(packet); length >= 255; length -= 255 {
				table = append(table, 255)
			}
			table = append(table, byte(len(packet)%255))
			body.Write(packet)
		}
		header[26] = byte(len(table))
		page := append(header, table...)
		page = append(page, body.Bytes()...)
		binary.LittleEndian.PutUint32(page[22:26], oggCRC(page))
		return page
	}
	head := []byte("OpusHead\x01\x01\x38\x01\x80\xbb\x00\x00\x00\x00\x00\x00\x00")
	tags := []byte("OpusTags\x00\x00\x00\x00")
	audio := make([][]byte, 0, len(packets))
	granule := uint64(0)
	for _, packet := range packets {
		samples, err := opusPacketSamples(packet)
		if err != nil {
			panic(err)
		}
		granule += uint64(samples)
		audio = append(audio, packet)
	}
	out := writePage(0, 2, 0, [][]byte{head})
	out = append(out, writePage(1, 0, 0, [][]byte{tags})...)
	out = append(out, writePage(2, 4, granule, audio)...)
	return out
}

// miniOGG is a structurally valid OGG/Opus generated in tests.
// The audio packets are not decodable; they are used to test framing,
// normalization, MIME, and duration without recording real audio.
var miniOGG = buildTestOGG(
	bytes.Repeat([]byte{0xFC}, 20),
	bytes.Repeat([]byte{0x81}, 40),
	bytes.Repeat([]byte{0x81}, 40),
)

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
		t.Errorf("mime mudou no caminho: %q", got.mime)
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
	if fake.sentAudio[0].seconds != 1 {
		t.Errorf("seconds errado: %d (esperava 1 derivado dos pacotes)", fake.sentAudio[0].seconds)
	}
}

func TestReplyAudioFallbackDuracao(t *testing.T) {
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
		t.Errorf("duração OGG não pode ser 0")
	}
}

func TestReplyAudioNormalizaOGG(t *testing.T) {
	_, fake, ts := newTestServer(t)
	defer ts.Close()

	fake.events <- Event{Kind: "audio", Audio: &AudioMessage{ID: "MSG13", FromNumber: "5511555555555", Seconds: 2}}
	time.Sleep(200 * time.Millisecond)

	buf, ct := multipartOGG(t, "MSG13", "5511555555555", "audio/ogg; codecs=opus", 2, miniOGG)
	res, err := http.Post(ts.URL+"/reply/audio", ct, buf)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()

	if len(fake.sentAudio) != 1 {
		t.Fatalf("esperava 1 áudio, veio %d", len(fake.sentAudio))
	}
	normalized, seconds, err := normalizeOGGOpus(miniOGG)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(fake.sentAudio[0].data, normalized) {
		t.Fatal("áudio enviado divergiu da normalização OGG")
	}
	if fake.sentAudio[0].seconds != seconds {
		t.Errorf("duração enviada=%d, normalizada=%d", fake.sentAudio[0].seconds, seconds)
	}
}

func TestReplyAudioDefaultMimeSemCampo(t *testing.T) {
	// Without mimeType in the form → server uses "audio/mp4" as fallback (legacy).
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

	// Inject and wait for it to be listed
	res1, _ := http.Get(ts.URL + "/pending")
	res1.Body.Close()

	// Send reply → pending should be removed
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

	res, err := http.Get(ts.URL + "/auth/status")
	if err != nil {
		t.Fatal(err)
	}
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

	res, err = http.Get(ts.URL + "/config/allowed-numbers")
	if err != nil {
		t.Fatal(err)
	}
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

	res, err := http.Get(ts.URL + "/pending")
	if err != nil {
		t.Fatal(err)
	}
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

// HTTP integration with fake engine; does not validate codec, upload, or WhatsApp delivery.

func integraOGGReal() []byte {
	return buildTestOGG(bytes.Repeat([]byte{0xFC}, 20), bytes.Repeat([]byte{0x81}, 40))
}

func TestIntegrationReplyAudioFullPipeline(t *testing.T) {
	oggBytes := integraOGGReal()
	expectedAudio, expectedSeconds, err := normalizeOGGOpus(oggBytes)
	if err != nil {
		t.Fatal(err)
	}
	_, fake, ts := newTestServer(t)
	defer ts.Close()

	// 1) Inject the received audio event (as if from the sender).
	fake.events <- Event{Kind: "audio", Audio: &AudioMessage{
		ID: "INT-1", FromNumber: "5511999990001", FromName: "Sara", Seconds: 4,
	}}

	// 2) Wait for the daemon to process it and make it available at /pending.
	deadline := time.Now().Add(3 * time.Second)
	var pend []PendingCall
	for time.Now().Before(deadline) {
		res, _ := http.Get(ts.URL + "/pending")
		json.NewDecoder(res.Body).Decode(&pend)
		res.Body.Close()
		if len(pend) == 1 && pend[0].AudioID == "INT-1" {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	if len(pend) != 1 || pend[0].AudioID != "INT-1" {
		t.Fatalf("pendência não apareceu em /pending: %+v", pend)
	}

	// 3) Download the audio from the daemon (should be the same bytes injected above).
	resMedia, err := http.Get(ts.URL + "/media/INT-1.ogg")
	if err != nil {
		t.Fatal(err)
	}
	downloaded, _ := io.ReadAll(resMedia.Body)
	resMedia.Body.Close()
	if len(downloaded) == 0 {
		t.Fatal("áudio veio vazio em /media")
	}

	// Send the reply with the MIME received from the app unchanged.
	var buf bytes.Buffer
	w := multipart.NewWriter(&buf)
	w.WriteField("audioId", "INT-1")
	w.WriteField("to", "5511999990001")
	w.WriteField("mimeType", "audio/ogg; codecs=opus")
	w.WriteField("durationSec", "5")
	fw, _ := w.CreateFormFile("file", "reply.ogg")
	fw.Write(oggBytes)
	w.Close()

	resPost, err := http.Post(ts.URL+"/reply/audio", w.FormDataContentType(), &buf)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resPost.Body)
	resPost.Body.Close()
	if resPost.StatusCode != 200 {
		t.Fatalf("POST /reply/audio retornou %d: %s", resPost.StatusCode, body)
	}

	// 5) Main assertion: the engine received EXACTLY the original bytes and MIME.
	if len(fake.sentAudio) != 1 {
		t.Fatalf("esperava 1 SendAudio, veio %d", len(fake.sentAudio))
	}
	got := fake.sentAudio[0]
	if got.number != "5511999990001" {
		t.Errorf("número errado: %s", got.number)
	}
	if got.mime != "audio/ogg; codecs=opus" {
		t.Errorf("mime mudou no caminho: %q", got.mime)
	}
	if got.seconds != expectedSeconds {
		t.Errorf("seconds errado: %d (esperava %d)", got.seconds, expectedSeconds)
	}
	if !bytes.Equal(got.data, expectedAudio) {
		t.Fatal("áudio enviado divergiu da normalização OGG")
	}
	if got.size != len(expectedAudio) {
		t.Errorf("tamanho dos bytes mudou no caminho: %d != %d", got.size, len(expectedAudio))
	}

	// 6) The pending item should have been removed after the reply.
	resPend, _ := http.Get(ts.URL + "/pending")
	var after []PendingCall
	json.NewDecoder(resPend.Body).Decode(&after)
	resPend.Body.Close()
	for _, p := range after {
		if p.AudioID == "INT-1" {
			t.Error("pendência INT-1 ainda presente depois do reply")
		}
	}
}

// Regression: even without explicit durationSec, the fallback must NEVER return zero.
func TestIntegrationFallbackDuracaoNuncaZero(t *testing.T) {
	_, fake, ts := newTestServer(t)
	defer ts.Close()

	fake.events <- Event{Kind: "audio", Audio: &AudioMessage{ID: "INT-2", FromNumber: "5511999990002", Seconds: 3}}
	time.Sleep(250 * time.Millisecond)

	var buf bytes.Buffer
	w := multipart.NewWriter(&buf)
	w.WriteField("audioId", "INT-2")
	w.WriteField("to", "5511999990002")
	w.WriteField("mimeType", "audio/ogg; codecs=opus")
	// WITHOUT durationSec
	fw, _ := w.CreateFormFile("file", "reply.ogg")
	fw.Write(integraOGGReal())
	w.Close()

	res, err := http.Post(ts.URL+"/reply/audio", w.FormDataContentType(), &buf)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()

	if len(fake.sentAudio) != 1 {
		t.Fatalf("esperava 1 SendAudio, veio %d", len(fake.sentAudio))
	}
	if fake.sentAudio[0].seconds == 0 {
		t.Error("fallback de duração resultou em 0 — WhatsApp rejeita PTT com seconds=0")
	}
}

func TestReplyAudioNormalizaFixtureOpusRealNoHTTP(t *testing.T) {
	data, err := os.ReadFile("../server/test/sample.ogg")
	if err != nil {
		t.Fatal(err)
	}
	expected, expectedSeconds, err := normalizeOGGOpus(data)
	if err != nil {
		t.Fatal(err)
	}
	_, fake, ts := newTestServer(t)
	defer ts.Close()
	body, contentType := multipartOGG(t, "", "5511999990001", "audio/ogg; codecs=opus", 3, data)
	response, err := http.Post(ts.URL+"/reply/audio", contentType, body)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK || len(fake.sentAudio) != 1 {
		t.Fatalf("status=%d envios=%d", response.StatusCode, len(fake.sentAudio))
	}
	if !bytes.Equal(expected, fake.sentAudio[0].data) {
		t.Fatal("arquivo Opus divergiu da normalização")
	}
	if fake.sentAudio[0].seconds != expectedSeconds {
		t.Fatalf("seconds=%d esperava=%d", fake.sentAudio[0].seconds, expectedSeconds)
	}
}

func TestReplyAudioRejeitaArquivoVazioSemChamarMotor(t *testing.T) {
	_, fake, ts := newTestServer(t)
	defer ts.Close()
	var body bytes.Buffer
	form := multipart.NewWriter(&body)
	form.WriteField("to", "5511999990001")
	form.CreateFormFile("file", "empty.ogg")
	form.Close()
	response, err := http.Post(ts.URL+"/reply/audio", form.FormDataContentType(), &body)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusBadRequest || len(fake.sentAudio) != 0 {
		t.Fatalf("arquivo vazio: status=%d envios=%d", response.StatusCode, len(fake.sentAudio))
	}
}

func TestMediaTestWavAindaFunciona(t *testing.T) {
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
		t.Fatalf("tom sintético corrompido")
	}
}
