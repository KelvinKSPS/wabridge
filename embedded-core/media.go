package main

import (
	"encoding/binary"
	"math"
)

// generateToneWav cria um wav PCM 16 kHz mono com um tom (mesma ideia do
// servidor Node: `server/src/media.ts` → generateToneWav).
func generateToneWav(frequency float64, seconds float64) []byte {
	const sampleRate = 16000
	samples := int(sampleRate * seconds)
	data := make([]byte, samples*2)
	for i := 0; i < samples; i++ {
		// desce de 0.8 para 0 nos últimos 10%: evita estalo no fim
		fade := 0.8
		if i > int(float64(samples)*0.9) {
			fade = 0.8 * (1 - float64(i-int(float64(samples)*0.9))/float64(int(float64(samples)*0.1)))
		}
		v := math.Sin(2*math.Pi*frequency*float64(i)/sampleRate) * fade
		binary.LittleEndian.PutUint16(data[i*2:], uint16(int16(v*32767)))
	}

	header := make([]byte, 44)
	copy(header[0:], "RIFF")
	binary.LittleEndian.PutUint32(header[4:], uint32(36+len(data)))
	copy(header[8:], "WAVE")
	copy(header[12:], "fmt ")
	binary.LittleEndian.PutUint32(header[16:], 16)
	binary.LittleEndian.PutUint16(header[20:], 1)  // PCM
	binary.LittleEndian.PutUint16(header[22:], 1)  // mono
	binary.LittleEndian.PutUint32(header[24:], sampleRate)
	binary.LittleEndian.PutUint32(header[28:], sampleRate*2)
	binary.LittleEndian.PutUint16(header[32:], 2)
	binary.LittleEndian.PutUint16(header[34:], 16)
	copy(header[36:], "data")
	binary.LittleEndian.PutUint32(header[40:], uint32(len(data)))

	out := make([]byte, 0, 44+len(data))
	out = append(out, header...)
	out = append(out, data...)
	return out
}

// wavDuration devolve a duração de um wav PCM (segundos), 0 se não der.
func wavDuration(data []byte) float64 {
	if len(data) < 44 || string(data[0:4]) != "RIFF" {
		return 0
	}
	rate := binary.LittleEndian.Uint32(data[24:28])
	bits := binary.LittleEndian.Uint16(data[34:36])
	size := binary.LittleEndian.Uint32(data[40:44])
	if rate == 0 || bits == 0 {
		return 0
	}
	bytesPerSample := bits / 8
	channels := uint16(1)
	if len(data) >= 34 {
		channels = binary.LittleEndian.Uint16(data[22:24])
	}
	if channels == 0 {
		channels = 1
	}
	samples := float64(size) / float64(bytesPerSample*channels)
	return samples / float64(rate)
}
