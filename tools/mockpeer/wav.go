package main

import (
	"encoding/binary"
	"fmt"
	"io"
	"os"
)

// maxWAVChunkBytes bounds an untrusted chunk length so a corrupt header cannot
// make the peer allocate an arbitrary amount of memory.
const maxWAVChunkBytes = 1 << 28

const (
	wavFormatPCM        = 1
	wavFormatExtensible = 0xFFFE
)

// readWAVFile loads a WAV file and returns its raw little-endian PCM16 payload.
func readWAVFile(path string) ([]byte, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer file.Close()
	return parseWAV(file)
}

// parseWAV walks the RIFF chunks of a WAV stream and returns the `data` payload.
//
// It requires 16 kHz mono PCM16 because that is the ONLY audio shape the frozen
// `audio_pcm16_16k` frame carries. Chunks are parsed (not assumed to start at
// byte 44), unknown chunks are skipped, and odd-sized chunks are padded.
func parseWAV(r io.Reader) ([]byte, error) {
	var header [12]byte
	if _, err := io.ReadFull(r, header[:]); err != nil {
		return nil, fmt.Errorf("read RIFF header: %w", err)
	}
	if string(header[0:4]) != "RIFF" {
		return nil, fmt.Errorf("not a RIFF file: magic %q", header[0:4])
	}
	if string(header[8:12]) != "WAVE" {
		return nil, fmt.Errorf("not a WAVE file: form %q", header[8:12])
	}

	var (
		formatFound   bool
		dataFound     bool
		audioFormat   uint16
		channels      uint16
		sampleRateHz  uint32
		bitsPerSample uint16
		pcm           []byte
	)

	for {
		var chunkHeader [8]byte
		if _, err := io.ReadFull(r, chunkHeader[:]); err != nil {
			if err == io.EOF {
				break
			}
			if err == io.ErrUnexpectedEOF {
				return nil, fmt.Errorf("truncated chunk header")
			}
			return nil, fmt.Errorf("read chunk header: %w", err)
		}

		chunkID := string(chunkHeader[0:4])
		chunkSize := binary.LittleEndian.Uint32(chunkHeader[4:8])
		if chunkSize > maxWAVChunkBytes {
			return nil, fmt.Errorf("chunk %q size %d exceeds the %d-byte limit", chunkID, chunkSize, maxWAVChunkBytes)
		}

		body := make([]byte, chunkSize)
		if _, err := io.ReadFull(r, body); err != nil {
			return nil, fmt.Errorf("read chunk %q: %w", chunkID, err)
		}

		switch chunkID {
		case "fmt ":
			if len(body) < 16 {
				return nil, fmt.Errorf("fmt chunk is %d bytes, want at least 16", len(body))
			}
			audioFormat = binary.LittleEndian.Uint16(body[0:2])
			channels = binary.LittleEndian.Uint16(body[2:4])
			sampleRateHz = binary.LittleEndian.Uint32(body[4:8])
			bitsPerSample = binary.LittleEndian.Uint16(body[14:16])
			formatFound = true
			if audioFormat == wavFormatExtensible {
				if len(body) < 26 {
					return nil, fmt.Errorf("extensible fmt chunk is %d bytes, want at least 26", len(body))
				}
				audioFormat = binary.LittleEndian.Uint16(body[24:26])
			}
		case "data":
			dataFound = true
			pcm = body
		}

		if chunkSize%2 == 1 {
			var pad [1]byte
			if _, err := io.ReadFull(r, pad[:]); err != nil && err != io.EOF {
				return nil, fmt.Errorf("read padding for chunk %q: %w", chunkID, err)
			}
		}
	}

	if !formatFound {
		return nil, fmt.Errorf("missing fmt chunk")
	}
	if audioFormat != wavFormatPCM {
		return nil, fmt.Errorf("unsupported audio format %d, want PCM (%d)", audioFormat, wavFormatPCM)
	}
	if channels != 1 {
		return nil, fmt.Errorf("unsupported channel count %d, want mono (1)", channels)
	}
	if sampleRateHz != audioSampleRateHz {
		return nil, fmt.Errorf("unsupported sample rate %d Hz, want %d Hz", sampleRateHz, audioSampleRateHz)
	}
	if bitsPerSample != 16 {
		return nil, fmt.Errorf("unsupported sample width %d bits, want 16", bitsPerSample)
	}
	if !dataFound {
		return nil, fmt.Errorf("missing data chunk")
	}
	if len(pcm)%2 != 0 {
		return nil, fmt.Errorf("PCM16 data chunk has an odd byte length %d", len(pcm))
	}
	return pcm, nil
}
