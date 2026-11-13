package main

import (
	"bytes"
	"testing"

	"go.mau.fi/whatsmeow"
)

// O StreamingSidecar vem do CDN e precisa ser copiado pro AudioMessage.
// Sem ele o WhatsApp aceita a mensagem (msgID) mas o aparelho não reproduz.
func TestBuildAudioMessageCopiaStreamingSidecar(t *testing.T) {
	sidecar := bytes.Repeat([]byte{0xAB, 0xCD}, 8)
	resp := whatsmeow.UploadResponse{
		URL:              "https://cdn.example/m",
		DirectPath:       "/mms/audio",
		MediaKey:         bytes.Repeat([]byte{1}, 32),
		FileEncSHA256:    bytes.Repeat([]byte{2}, 32),
		FileSHA256:       bytes.Repeat([]byte{3}, 32),
		FileLength:       41025,
		StreamingSidecar: sidecar,
	}
	msg := buildAudioMessage(resp, "audio/ogg", 5)

	if msg.StreamingSidecar == nil {
		t.Fatal("StreamingSidecar não foi copiado do upload — nota de voz não vai reproduzir")
	}
	if !bytes.Equal(msg.StreamingSidecar, sidecar) {
		t.Errorf("StreamingSidecar divergiu do upload: %d bytes", len(msg.StreamingSidecar))
	}
	if msg.Mimetype == nil || *msg.Mimetype != "audio/ogg" {
		t.Errorf("mimetype errado: %v", msg.Mimetype)
	}
	if msg.PTT == nil || !*msg.PTT {
		t.Error("PTT precisa estar true")
	}
	if msg.Seconds == nil || *msg.Seconds != 5 {
		t.Errorf("seconds errado: %v", msg.Seconds)
	}
	if msg.FileLength == nil || *msg.FileLength != 41025 {
		t.Errorf("fileLength errado: %v", msg.FileLength)
	}
}

// O app oficial exige waveform para tratar a mensagem como nota de voz (PTT).
func TestBuildAudioMessageSempreTemWaveform(t *testing.T) {
	resp := whatsmeow.UploadResponse{
		URL:              "https://cdn.example/m",
		DirectPath:       "/mms/audio",
		MediaKey:         bytes.Repeat([]byte{1}, 32),
		FileEncSHA256:    bytes.Repeat([]byte{2}, 32),
		FileSHA256:       bytes.Repeat([]byte{3}, 32),
		FileLength:       1000,
		StreamingSidecar: bytes.Repeat([]byte{9}, 64),
	}
	msg := buildAudioMessage(resp, "audio/ogg", 2)
	if len(msg.Waveform) != 64 {
		t.Fatalf("waveform deveria ter 64 bytes, veio %d", len(msg.Waveform))
	}
	for i, v := range msg.Waveform {
		if v < 1 || v > 100 {
			t.Errorf("waveform[%d]=%d fora da faixa 1..100", i, v)
		}
	}
}

// Sem sidecar no upload (CDN antigo), o envelope continua válido e o
// waveform não quebra.
func TestBuildAudioMessageSemSidecar(t *testing.T) {
	resp := whatsmeow.UploadResponse{
		URL:        "https://cdn.example/m",
		DirectPath: "/mms/audio",
		MediaKey:   bytes.Repeat([]byte{1}, 32),
		FileLength: 500,
	}
	msg := buildAudioMessage(resp, "audio/ogg", 1)
	if msg.StreamingSidecar != nil {
		t.Error("sidecar vazio deveria virar nil")
	}
	if len(msg.Waveform) != 64 {
		t.Errorf("waveform errado: %d", len(msg.Waveform))
	}
}

// Waveform vazio quando n <= 0.
func TestMakeWaveformVazio(t *testing.T) {
	if makeWaveform([]byte{1, 2, 3}, 0) != nil {
		t.Error("n=0 deveria devolver nil")
	}
}
