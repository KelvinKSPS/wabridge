package main

import (
	"bytes"
	"encoding/binary"
	"testing"
)

func TestGenerateToneWavFormato(t *testing.T) {
	data := generateToneWav(440, 1)
	if len(data) < 44 {
		t.Fatal("wav curto demais")
	}
	if string(data[0:4]) != "RIFF" || string(data[8:12]) != "WAVE" {
		t.Fatalf("cabeçalho inválido: %q %q", data[0:4], data[8:12])
	}
	if binary.LittleEndian.Uint16(data[22:24]) != 1 {
		t.Fatal("não é mono")
	}
	if binary.LittleEndian.Uint32(data[24:28]) != 16000 {
		t.Fatal("não é 16 kHz")
	}
	if binary.LittleEndian.Uint16(data[34:36]) != 16 {
		t.Fatal("não é 16 bits")
	}
	// tamanho do bloco de dados bate com o arquivo
	size := binary.LittleEndian.Uint32(data[40:44])
	if int(size)+44 != len(data) {
		t.Fatalf("tamanho dos dados não bate: %d vs %d", size, len(data)-44)
	}
}

func TestGenerateToneWavNaoEhSilencio(t *testing.T) {
	data := generateToneWav(440, 1)
	amostras := data[44:]
	var maior int16
	for i := 0; i+1 < len(amostras); i += 2 {
		v := int16(binary.LittleEndian.Uint16(amostras[i : i+2]))
		if v < 0 {
			v = -v
		}
		if v > maior {
			maior = v
		}
	}
	if maior < 1000 {
		t.Fatalf("o tom saiu mudo (amplitude %d)", maior)
	}
}

func TestWavDuration(t *testing.T) {
	data := generateToneWav(440, 2)
	d := wavDuration(data)
	if d < 1.9 || d > 2.1 {
		t.Fatalf("duração errada: %v", d)
	}
}

func TestWavDurationDeArquivoQueNaoEWav(t *testing.T) {
	if wavDuration([]byte("nao é wav")) != 0 {
		t.Fatal("deveria devolver 0")
	}
}

func TestWavDurationWavVazio(t *testing.T) {
	if wavDuration(nil) != 0 {
		t.Fatal("deveria devolver 0")
	}
}

func TestWavDurationCanais(t *testing.T) {
	// monta um wav estéreo de 1 segundo
	const rate = 16000
	const channels = 2
	const bits = 16
	dados := make([]byte, rate*channels*bits/8)
	h := make([]byte, 44)
	copy(h[0:], "RIFF")
	binary.LittleEndian.PutUint32(h[4:], uint32(36+len(dados)))
	copy(h[8:], "WAVE")
	copy(h[12:], "fmt ")
	binary.LittleEndian.PutUint32(h[16:], 16)
	binary.LittleEndian.PutUint16(h[20:], 1)
	binary.LittleEndian.PutUint16(h[22:], channels)
	binary.LittleEndian.PutUint32(h[24:], rate)
	binary.LittleEndian.PutUint32(h[28:], rate*channels*bits/8)
	binary.LittleEndian.PutUint16(h[32:], channels*bits/8)
	binary.LittleEndian.PutUint16(h[34:], bits)
	copy(h[36:], "data")
	binary.LittleEndian.PutUint32(h[40:], uint32(len(dados)))

	wav := append(bytes.Clone(h), dados...)
	d := wavDuration(wav)
	if d < 0.9 || d > 1.1 {
		t.Fatalf("duração estéreo errada: %v", d)
	}
}
