package main

import "encoding/binary"

// Synthetic media generators for the T6 transport spike.
//
// Both generators are PURE functions of the frame index: two runs of the same
// command produce byte-identical payloads, so an E2E assertion on "N frames
// sent" can never be satisfied by nondeterministic garbage. The payloads are
// deliberately shaped like real media (20 ms of PCM16, an H.264 NAL start code)
// without being decodable — the hub must treat them as opaque bytes.

const (
	// audioSamplesPerFrame is 20 ms of mono audio at the frozen 16 kHz rate.
	// 20 ms is the canonical streaming STT chunk size.
	audioSamplesPerFrame = 320

	// audioSampleRateHz mirrors the frozen `audio_pcm16_16k` wire contract.
	audioSampleRateHz = 16_000
)

// SyntheticAudioFrame returns one deterministic little-endian PCM16 mono frame
// of 20 ms at 16 kHz (640 bytes). The sample value walks with the frame index so
// successive frames differ while staying reproducible.
func SyntheticAudioFrame(index int) []byte {
	buf := make([]byte, audioSamplesPerFrame*2)
	for i := 0; i < audioSamplesPerFrame; i++ {
		sample := int16((index*31 + i*7) % 32767)
		binary.LittleEndian.PutUint16(buf[i*2:], uint16(sample))
	}
	return buf
}

// SyntheticVideoNAL returns one deterministic H.264-NAL-shaped blob: a 4-byte
// start code plus an IDR-like header byte, then a reproducible body. The hub
// NEVER decodes or re-encodes it, so the exact byte length is the contract.
func SyntheticVideoNAL(index int) []byte {
	nal := []byte{0x00, 0x00, 0x00, 0x01, 0x65}
	body := make([]byte, 24)
	for i := range body {
		body[i] = byte((index*17 + i) % 251)
	}
	return append(nal, body...)
}
