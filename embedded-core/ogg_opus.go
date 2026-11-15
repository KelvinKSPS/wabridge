package main

import (
	"bytes"
	"encoding/binary"
	"errors"
	"fmt"
	"math"
)

const maxOGGOpusInput = 25 << 20
const maxOpusAudioPacket = 65024
const maxOGGOpusAudioSamplesPerPage = 48000

var (
	errOGGOpusTooLarge    = errors.New("OGG acima de 25 MB")
	errOGGOpusTruncated   = errors.New("página OGG truncada")
	errOGGOpusInvalid     = errors.New("página OGG inválida")
	errOGGOpusUnsupported = errors.New("OGG/Opus não suportado")
	errOGGOpusBadPacket   = errors.New("pacote Opus inválido")
	errOGGOpusBadGranule  = errors.New("granule position OGG inválida")
)

type oggPage struct {
	headerType byte
	granule    uint64
	serial     uint32
	sequence   uint32
	segments   []byte
	body       []byte
}

type opusPacketDuration struct {
	samples int
}

func normalizeOGGOpus(data []byte) ([]byte, uint32, error) {
	if len(data) == 0 || len(data) > maxOGGOpusInput {
		return nil, 0, errOGGOpusTooLarge
	}
	pages, err := parseOGGPages(data)
	if err != nil {
		return nil, 0, err
	}
	packets, err := oggPackets(pages)
	if err != nil {
		return nil, 0, err
	}
	if len(packets) < 3 || !bytes.HasPrefix(packets[0], []byte("OpusHead")) || !bytes.HasPrefix(packets[1], []byte("OpusTags")) {
		return nil, 0, fmt.Errorf("%w: cabeçalhos Opus ausentes", errOGGOpusUnsupported)
	}
	if err := validateOpusHead(packets[0]); err != nil {
		return nil, 0, err
	}
	for _, packet := range packets {
		if len(packet) > maxOpusAudioPacket {
			return nil, 0, fmt.Errorf("%w: pacote excede uma página OGG", errOGGOpusUnsupported)
		}
	}
	durations := make([]opusPacketDuration, len(packets)-2)
	totalSamples := 0
	for index, packet := range packets[2:] {
		samples, err := opusPacketSamples(packet)
		if err != nil {
			return nil, 0, err
		}
		durations[index] = opusPacketDuration{samples: samples}
		totalSamples += samples
	}
	if totalSamples <= 0 {
		return nil, 0, fmt.Errorf("%w: duração Opus ausente", errOGGOpusUnsupported)
	}

	finalGranule, hasEOS := finalAudioGranule(pages)
	if hasEOS && (finalGranule > uint64(totalSamples) || finalGranule < uint64(totalSamples-durations[len(durations)-1].samples)) {
		return nil, 0, errOGGOpusBadGranule
	}
	if !hasEOS {
		finalGranule = uint64(totalSamples)
	}
	preSkip := int(binary.LittleEndian.Uint16(packets[0][10:12]))
	decodedSamples := int(finalGranule) - preSkip
	if decodedSamples < 0 {
		decodedSamples = 0
	}
	seconds := uint32(math.Ceil(float64(decodedSamples) / 48000.0))
	if seconds == 0 {
		seconds = 1
	}

	serial := pages[0].serial
	var out bytes.Buffer
	writeOGGPage(&out, serial, 0, 2, 0, [][]byte{packets[0]})
	writeOGGPage(&out, serial, 1, 0, 0, [][]byte{packets[1]})
	sequence := uint32(2)
	pagePackets := [][]byte{}
	pageSamples := 0
	pageLaces := 0
	granule := uint64(0)
	flushPage := func(final bool) {
		if len(pagePackets) == 0 {
			return
		}
		flags := byte(0)
		if final {
			flags |= 4
		}
		writeOGGPage(&out, serial, sequence, flags, granule, pagePackets)
		sequence++
		pagePackets = pagePackets[:0]
		pageSamples = 0
		pageLaces = 0
	}
	for index, packet := range packets[2:] {
		samples := durations[index].samples
		if len(pagePackets) > 0 && (pageSamples+samples > maxOGGOpusAudioSamplesPerPage || pageLaces+laceCount(len(packet)) > 255) {
			flushPage(false)
		}
		if laceCount(len(packet)) > 255 {
			return nil, 0, fmt.Errorf("%w: pacote Opus maior que 65024 bytes", errOGGOpusUnsupported)
		}
		pagePackets = append(pagePackets, packet)
		pageLaces += laceCount(len(packet))
		pageSamples += samples
		granule += uint64(samples)
		if pageSamples >= maxOGGOpusAudioSamplesPerPage && index < len(durations)-1 {
			flushPage(false)
		}
	}
	granule = finalGranule
	flushPage(true)
	return out.Bytes(), seconds, nil
}

func parseOGGPages(data []byte) ([]oggPage, error) {
	var pages []oggPage
	offset := 0
	seenSerial := uint32(0)
	haveSerial := false
	var previousSequence uint32
	haveSequence := false
	for offset < len(data) {
		if len(data)-offset < 27 {
			return nil, errOGGOpusTruncated
		}
		if string(data[offset:offset+4]) != "OggS" || data[offset+4] != 0 {
			return nil, fmt.Errorf("%w: captura/versão inválida no byte %d", errOGGOpusInvalid, offset)
		}
		segments := int(data[offset+26])
		if len(data)-offset < 27+segments {
			return nil, errOGGOpusTruncated
		}
		table := data[offset+27 : offset+27+segments]
		bodyLength := 0
		for _, segment := range table {
			bodyLength += int(segment)
		}
		end := offset + 27 + segments + bodyLength
		if end > len(data) {
			return nil, errOGGOpusTruncated
		}
		granule := binary.LittleEndian.Uint64(data[offset+6 : offset+14])
		serial := binary.LittleEndian.Uint32(data[offset+14 : offset+18])
		sequence := binary.LittleEndian.Uint32(data[offset+18 : offset+22])
		checksum := binary.LittleEndian.Uint32(data[offset+22 : offset+26])
		if !haveSerial {
			seenSerial = serial
			haveSerial = true
		} else if serial != seenSerial {
			return nil, fmt.Errorf("%w: mais de um serial", errOGGOpusUnsupported)
		}
		if haveSequence && sequence != previousSequence+1 {
			return nil, fmt.Errorf("%w: sequence %d após %d", errOGGOpusInvalid, sequence, previousSequence)
		}
		page := make([]byte, end-offset)
		copy(page, data[offset:end])
		binary.LittleEndian.PutUint32(page[22:26], 0)
		if oggCRC(page) != checksum {
			return nil, fmt.Errorf("%w: CRC inválido no byte %d", errOGGOpusInvalid, offset)
		}
		pages = append(pages, oggPage{
			headerType: data[offset+5],
			granule:    granule,
			serial:     serial,
			sequence:   sequence,
			segments:   append([]byte(nil), table...),
			body:       append([]byte(nil), data[offset+27+segments:end]...),
		})
		previousSequence = sequence
		haveSequence = true
		offset = end
	}
	if len(pages) == 0 || pages[0].headerType&2 == 0 {
		return nil, fmt.Errorf("%w: primeira página não é BOS", errOGGOpusInvalid)
	}
	return pages, nil
}

func oggPackets(pages []oggPage) ([][]byte, error) {
	var packets [][]byte
	var packet []byte
	continued := false
	for _, page := range pages {
		if (page.headerType&1 != 0) != continued {
			return nil, fmt.Errorf("%w: continuação de pacote inconsistente", errOGGOpusInvalid)
		}
		offset := 0
		for _, segment := range page.segments {
			packet = append(packet, page.body[offset:offset+int(segment)]...)
			offset += int(segment)
			if segment < 255 {
				packets = append(packets, append([]byte(nil), packet...))
				packet = packet[:0]
			}
			continued = segment == 255
		}
	}
	if continued {
		return nil, fmt.Errorf("%w: último pacote sem lacing final", errOGGOpusInvalid)
	}
	return packets, nil
}

func validateOpusHead(head []byte) error {
	if len(head) < 19 || !bytes.HasPrefix(head, []byte("OpusHead")) || head[8] != 1 {
		return fmt.Errorf("%w: OpusHead inválido", errOGGOpusUnsupported)
	}
	family := head[18]
	if family != 0 {
		return fmt.Errorf("%w: mapping family %d não suportada", errOGGOpusUnsupported, family)
	}
	if head[9] != 1 && head[9] != 2 {
		return fmt.Errorf("%w: número de canais %d não suportado", errOGGOpusUnsupported, head[9])
	}
	return nil
}

func opusPacketSamples(packet []byte) (int, error) {
	if len(packet) == 0 {
		return 0, fmt.Errorf("%w: pacote vazio", errOGGOpusBadPacket)
	}
	toc := packet[0]
	config := int(toc >> 3)
	frameCountCode := int(toc & 3)
	var frameCount int
	switch frameCountCode {
	case 0:
		frameCount = 1
	case 1, 2:
		frameCount = 2
	case 3:
		if len(packet) < 2 {
			return 0, fmt.Errorf("%w: TOC indica frame count sem segundo byte", errOGGOpusBadPacket)
		}
		frameCount = int(packet[1] & 0x3f)
		if frameCount == 0 {
			return 0, fmt.Errorf("%w: frame count zero", errOGGOpusBadPacket)
		}
	}
	var samplesPerFrame int
	switch {
	case config < 12:
		samplesPerFrame = []int{480, 960, 1920, 2880}[config&3]
	case config < 16:
		samplesPerFrame = []int{480, 960}[config&1]
	case config < 32:
		samplesPerFrame = []int{120, 240, 480, 960}[config&3]
	default:
		return 0, fmt.Errorf("%w: config %d reservado", errOGGOpusBadPacket, config)
	}
	samples := frameCount * samplesPerFrame
	if samples <= 0 || samples > 5760 {
		return 0, fmt.Errorf("%w: duração %d fora do limite", errOGGOpusBadPacket, samples)
	}
	return samples, nil
}

func finalAudioGranule(pages []oggPage) (uint64, bool) {
	for index := len(pages) - 1; index >= 2; index-- {
		if pages[index].granule != 0 || pages[index].headerType&4 != 0 {
			return pages[index].granule, pages[index].headerType&4 != 0
		}
	}
	return 0, false
}

func laceCount(length int) int {
	if length > maxOpusAudioPacket {
		return 0
	}
	return length/255 + 1
}

func writeOGGPage(out *bytes.Buffer, serial, sequence uint32, flags byte, granule uint64, packets [][]byte) {
	var body bytes.Buffer
	var table []byte
	for _, packet := range packets {
		laces := laceCount(len(packet))
		for index := 0; index < laces-1; index++ {
			table = append(table, 255)
		}
		table = append(table, byte(len(packet)%255))
		body.Write(packet)
	}
	header := make([]byte, 27)
	copy(header[0:4], "OggS")
	header[4] = 0
	header[5] = flags
	binary.LittleEndian.PutUint64(header[6:14], granule)
	binary.LittleEndian.PutUint32(header[14:18], serial)
	binary.LittleEndian.PutUint32(header[18:22], sequence)
	header[26] = byte(len(table))
	binary.LittleEndian.PutUint32(header[22:26], 0)
	full := append(header, table...)
	full = append(full, body.Bytes()...)
	binary.LittleEndian.PutUint32(full[22:26], oggCRC(full))
	out.Write(full)
}

func oggCRC(data []byte) uint32 {
	var crc uint32
	for _, value := range data {
		crc ^= uint32(value) << 24
		for bit := 0; bit < 8; bit++ {
			if crc&0x80000000 != 0 {
				crc = (crc << 1) ^ 0x04c11db7
			} else {
				crc <<= 1
			}
		}
	}
	return crc
}
