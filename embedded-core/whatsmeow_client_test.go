package main

import (
	"bytes"
	"fmt"
	"log"
	"strings"
	"sync"
	"testing"
	"time"

	"go.mau.fi/whatsmeow"
	waE2E "go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	"google.golang.org/protobuf/proto"
)

func TestBuildAudioMessagePreservaUploadNoProtobuf(t *testing.T) {
	for _, sidecar := range [][]byte{nil, bytes.Repeat([]byte{0xAB}, 10)} {
		t.Run(fmt.Sprintf("sidecar=%d", len(sidecar)), func(t *testing.T) {
			resp := whatsmeow.UploadResponse{
				URL:              "https://cdn.example/m",
				DirectPath:       "/mms/audio",
				MediaKey:         bytes.Repeat([]byte{1}, 32),
				FileEncSHA256:    bytes.Repeat([]byte{2}, 32),
				FileSHA256:       bytes.Repeat([]byte{3}, 32),
				FileLength:       41025,
				StreamingSidecar: sidecar,
			}
			before := time.Now().Unix()
			msg := buildAudioMessage(resp, "audio/ogg; codecs=opus", 5)
			encoded, err := proto.Marshal(&waE2E.Message{AudioMessage: msg})
			if err != nil {
				t.Fatal(err)
			}
			var decoded waE2E.Message
			if err := proto.Unmarshal(encoded, &decoded); err != nil {
				t.Fatal(err)
			}
			got := decoded.GetAudioMessage()
			if got == nil {
				t.Fatal("AudioMessage ausente")
			}
			if !proto.Equal(msg, got) {
				t.Fatal("envelope mudou no roundtrip protobuf")
			}
			if got.GetURL() != resp.URL || got.GetDirectPath() != resp.DirectPath ||
				got.GetFileLength() != resp.FileLength || got.GetSeconds() != 5 ||
				got.GetMimetype() != "audio/ogg; codecs=opus" || !got.GetPTT() {
				t.Fatalf("metadados do envelope não preservados: %v", got)
			}
			if !bytes.Equal(got.MediaKey, resp.MediaKey) ||
				!bytes.Equal(got.FileSHA256, resp.FileSHA256) ||
				!bytes.Equal(got.FileEncSHA256, resp.FileEncSHA256) ||
				!bytes.Equal(got.StreamingSidecar, resp.StreamingSidecar) {
				t.Fatal("chave, hashes ou sidecar alterados")
			}
			if got.GetMediaKeyTimestamp() < before || got.GetMediaKeyTimestamp() > time.Now().Unix() {
				t.Fatal("MediaKeyTimestamp não está em segundos Unix atuais")
			}
			if len(got.GetWaveform()) != 0 {
				t.Fatal("waveform não deve ser inventada a partir de dados criptográficos")
			}
		})
	}
}

func TestTrackAudioLimitaHistoricoAos32MaisRecentes(t *testing.T) {
	w := &whatsmeowClient{}
	start := time.Unix(100, 0)
	for i := 0; i < 40; i++ {
		w.trackAudio(types.MessageID(fmt.Sprintf("ID-%02d", i)), start.Add(time.Duration(i)*time.Second))
		if len(w.sentAudio) > 32 {
			t.Fatalf("histórico ultrapassou limite: %d", len(w.sentAudio))
		}
	}
	if len(w.sentAudio) != 32 {
		t.Fatalf("histórico contém %d IDs", len(w.sentAudio))
	}
	for i := 0; i < 40; i++ {
		id := types.MessageID(fmt.Sprintf("ID-%02d", i))
		timestamp, exists := w.sentAudio[id]
		if exists != (i >= 8) {
			t.Fatalf("retenção errada para %s", id)
		}
		if exists && !timestamp.Equal(start.Add(time.Duration(i)*time.Second)) {
			t.Fatalf("timestamp errado para %s", id)
		}
	}
	w.trackAudio("ID-08", start.Add(time.Hour))
	if len(w.sentAudio) != 32 {
		t.Fatal("atualizar ID existente não deve remover outro")
	}
	w.trackAudio("ID-40", start.Add(2*time.Hour))
	if _, exists := w.sentAudio["ID-08"]; !exists {
		t.Fatal("ID atualizado foi removido")
	}
	if _, exists := w.sentAudio["ID-09"]; exists {
		t.Fatal("ID mais antigo não foi removido")
	}
}

func TestTrackAudioConcorrente(t *testing.T) {
	w := &whatsmeowClient{}
	var workers sync.WaitGroup
	for i := 0; i < 100; i++ {
		workers.Add(1)
		go func(i int) {
			defer workers.Done()
			w.trackAudio(types.MessageID(fmt.Sprintf("ID-%03d", i)), time.Unix(int64(i), 0))
		}(i)
	}
	workers.Wait()
	if len(w.sentAudio) != 32 {
		t.Fatalf("histórico concorrente contém %d IDs", len(w.sentAudio))
	}
}

type receiptLogBuffer struct {
	mu sync.Mutex
	bytes.Buffer
}

func (b *receiptLogBuffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.Buffer.Write(p)
}

func (b *receiptLogBuffer) text() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.Buffer.String()
}

func TestReceiptFiltraIDsSemExporDadosDaMensagem(t *testing.T) {
	var output receiptLogBuffer
	previous := log.Writer()
	log.SetOutput(&output)
	t.Cleanup(func() { log.SetOutput(previous) })
	w := &whatsmeowClient{}
	w.trackAudio("PTT-ANTES-DO-ACK", time.Unix(100, 0))
	for _, receiptType := range []types.ReceiptType{
		types.ReceiptTypeDelivered, types.ReceiptTypeRead, types.ReceiptTypePlayed,
		types.ReceiptTypeSender, types.ReceiptTypeRetry, types.ReceiptTypeServerError,
	} {
		w.onEvent(&events.Receipt{
			MessageSource: types.MessageSource{
				Chat: types.JID{User: "DADO-PRIVADO", Server: types.DefaultUserServer},
			},
			MessageIDs: []types.MessageID{"NAO-RASTREADO", "PTT-ANTES-DO-ACK"},
			Timestamp:  time.Unix(110, 0),
			Type:       receiptType,
		})
		if !strings.Contains(output.text(), "msgID=PTT-ANTES-DO-ACK tipo="+receiptType.GoString()+" ts=110") {
			t.Fatalf("recibo %s ausente: %s", receiptType.GoString(), output.text())
		}
	}
	text := output.text()
	if strings.Contains(text, "NAO-RASTREADO") || strings.Contains(text, "DADO-PRIVADO") ||
		strings.Contains(text, "entrega confirmada") {
		t.Fatalf("log expõe mensagem não rastreada/dado privado ou confunde tipo de recibo: %s", text)
	}
	if strings.Count(text, "wabridge: PTT recibo") != 6 {
		t.Fatalf("quantidade de recibos errada: %s", text)
	}
	if _, exists := w.sentAudio["PTT-ANTES-DO-ACK"]; !exists {
		t.Fatal("recibo de entrega não deve impedir correlação de leitura/reprodução posteriores")
	}
	(&whatsmeowClient{}).onEvent(&events.Receipt{MessageIDs: []types.MessageID{"SEM-ENVIO"}})
	if output.text() != text {
		t.Fatal("cliente sem envios registrou recibo não relacionado")
	}
}
