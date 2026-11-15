package main

import (
	"bytes"
	"encoding/binary"
	"os"
	"path/filepath"
	"testing"
)

func testOGGPage(sequence uint32, flags byte, granule uint64, payload []byte, laces []byte) []byte {
	header := make([]byte, 27)
	copy(header, "OggS")
	header[5] = flags
	binary.LittleEndian.PutUint64(header[6:14], granule)
	binary.LittleEndian.PutUint32(header[14:18], 42)
	binary.LittleEndian.PutUint32(header[18:22], sequence)
	header[26] = byte(len(laces))
	page := append(header, laces...)
	page = append(page, payload...)
	page[22], page[23], page[24], page[25] = 0, 0, 0, 0
	binary.LittleEndian.PutUint32(page[22:26], oggCRC(page))
	return page
}

func TestNormalizeOGGOpusPreservaPacotesDivididos(t *testing.T) {
	head := make([]byte, 19)
	copy(head, "OpusHead")
	head[8], head[9] = 1, 1
	binary.LittleEndian.PutUint16(head[10:12], 312)
	binary.LittleEndian.PutUint32(head[12:16], 48000)
	tags := []byte("OpusTags\x00\x00\x00\x00")
	packet := bytes.Repeat([]byte{0xFC}, 500)
	input := testOGGPage(0, 2, 0, head, []byte{19})
	input = append(input, testOGGPage(1, 0, 0, tags, []byte{12})...)
	input = append(input, testOGGPage(2, 0, 960, packet[:255], []byte{255})...)
	input = append(input, testOGGPage(3, 1, 1920, append(append([]byte(nil), packet[255:]...), bytes.Repeat([]byte{0xFC}, 20)...), []byte{245, 20})...)

	output, seconds, err := normalizeOGGOpus(input)
	if err != nil {
		t.Fatal(err)
	}
	if seconds != 1 {
		t.Fatalf("seconds=%d", seconds)
	}
	pages, err := parseOGGPages(output)
	if err != nil {
		t.Fatal(err)
	}
	packets, err := oggPackets(pages)
	if err != nil {
		t.Fatal(err)
	}
	if len(packets) != 4 {
		t.Fatalf("packets=%d", len(packets))
	}
	if !bytes.Equal(packets[2], packet) {
		t.Fatal("pacote dividido não foi reconstituído")
	}
	if pages[len(pages)-1].headerType&4 == 0 {
		t.Fatal("última página sem EOS")
	}
}

func TestNormalizeOGGOpusSamplePreservaAudio(t *testing.T) {
	data, err := os.ReadFile(filepath.Join("..", "server", "test", "sample.ogg"))
	if err != nil {
		t.Skip(err)
	}
	output, seconds, err := normalizeOGGOpus(data)
	if err != nil {
		t.Fatal(err)
	}
	if seconds != 3 {
		t.Fatalf("seconds=%d", seconds)
	}
	inputPackets, err := oggPackets(mustTestOGGPages(t, data))
	if err != nil {
		t.Fatal(err)
	}
	outputPackets, err := oggPackets(mustTestOGGPages(t, output))
	if err != nil {
		t.Fatal(err)
	}
	if len(inputPackets) != len(outputPackets) {
		t.Fatalf("packets %d != %d", len(inputPackets), len(outputPackets))
	}
	for index := range inputPackets {
		if !bytes.Equal(inputPackets[index], outputPackets[index]) {
			t.Fatalf("pacote %d divergiu", index)
		}
	}
}

func TestNormalizeOGGOpusRejeitaCRC(t *testing.T) {
	data, err := os.ReadFile(filepath.Join("..", "server", "test", "sample.ogg"))
	if err != nil {
		t.Skip(err)
	}
	data[30] ^= 0xff
	if _, _, err := normalizeOGGOpus(data); err == nil {
		t.Fatal("CRC inválido foi aceito")
	}
}

func mustTestOGGPages(t *testing.T, data []byte) []oggPage {
	t.Helper()
	pages, err := parseOGGPages(data)
	if err != nil {
		t.Fatal(err)
	}
	return pages
}
