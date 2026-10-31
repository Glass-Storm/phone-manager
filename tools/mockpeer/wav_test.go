package main

import (
	"bytes"
	"encoding/binary"
	"testing"
)

type wavTestChunk struct {
	id   string
	body []byte
}

func buildTestWAV(chunks ...wavTestChunk) []byte {
	var payload bytes.Buffer
	payload.WriteString("WAVE")
	for _, chunk := range chunks {
		payload.WriteString(chunk.id)
		var size [4]byte
		binary.LittleEndian.PutUint32(size[:], uint32(len(chunk.body)))
		payload.Write(size[:])
		payload.Write(chunk.body)
		if len(chunk.body)%2 == 1 {
			payload.WriteByte(0)
		}
	}

	var out bytes.Buffer
	out.WriteString("RIFF")
	binary.Write(&out, binary.LittleEndian, uint32(payload.Len()))
	out.Write(payload.Bytes())
	return out.Bytes()
}

func fmtChunkBody(format, channels uint16, rate uint32, bits uint16) []byte {
	var body bytes.Buffer
	binary.Write(&body, binary.LittleEndian, format)
	binary.Write(&body, binary.LittleEndian, channels)
	binary.Write(&body, binary.LittleEndian, rate)
	binary.Write(&body, binary.LittleEndian, rate*uint32(channels)*uint32(bits)/8)
	binary.Write(&body, binary.LittleEndian, channels*bits/8)
	binary.Write(&body, binary.LittleEndian, bits)
	return body.Bytes()
}

func extensibleFmtChunkBody(channels uint16, rate uint32, bits uint16) []byte {
	body := fmtChunkBody(wavFormatExtensible, channels, rate, bits)
	body = append(body, 22, 0)
	body = append(body, byte(bits), 0)
	body = append(body, 0, 0, 0, 0)
	body = append(body, byte(wavFormatPCM), 0)
	body = append(body, make([]byte, 14)...)
	return body
}

func TestParseWAVWalksChunksInsteadOfAssumingA44ByteHeader(t *testing.T) {
	pcm := []byte{1, 0, 2, 0, 3, 0}
	wav := buildTestWAV(
		wavTestChunk{id: "LIST", body: []byte("odd")},
		wavTestChunk{id: "fmt ", body: fmtChunkBody(wavFormatPCM, 1, audioSampleRateHz, 16)},
		wavTestChunk{id: "fact", body: []byte{0, 0, 0, 0}},
		wavTestChunk{id: "data", body: pcm},
	)

	got, err := parseWAV(bytes.NewReader(wav))
	if err != nil {
		t.Fatalf("parseWAV returned %v", err)
	}
	if !bytes.Equal(got, pcm) {
		t.Fatalf("parseWAV payload = %v, want %v", got, pcm)
	}
}

func TestParseWAVAcceptsExtensiblePCM16(t *testing.T) {
	pcm := []byte{9, 0, 8, 0}
	wav := buildTestWAV(
		wavTestChunk{id: "fmt ", body: extensibleFmtChunkBody(1, audioSampleRateHz, 16)},
		wavTestChunk{id: "data", body: pcm},
	)

	got, err := parseWAV(bytes.NewReader(wav))
	if err != nil {
		t.Fatalf("parseWAV returned %v", err)
	}
	if !bytes.Equal(got, pcm) {
		t.Fatalf("parseWAV payload = %v, want %v", got, pcm)
	}
}

func TestParseWAVAcceptsEmptyDataChunk(t *testing.T) {
	wav := buildTestWAV(
		wavTestChunk{id: "fmt ", body: fmtChunkBody(wavFormatPCM, 1, audioSampleRateHz, 16)},
		wavTestChunk{id: "data", body: nil},
	)

	got, err := parseWAV(bytes.NewReader(wav))
	if err != nil {
		t.Fatalf("parseWAV returned %v", err)
	}
	if len(got) != 0 {
		t.Fatalf("parseWAV payload = %v, want empty", got)
	}
}

func TestParseWAVRejectsUnsupportedShapes(t *testing.T) {
	const wantPCM = "unsupported audio format"
	truncated := append(buildTestWAV(), []byte("ab")...)
	oversized := append(buildTestWAV(), []byte("data\xff\xff\xff\xff")...)

	tests := []struct {
		name    string
		wav     []byte
		wantErr string
	}{
		{
			name:    "not riff",
			wav:     []byte("NOPE\x00\x00\x00\x00WAVE"),
			wantErr: "not a RIFF file",
		},
		{
			name:    "not wave",
			wav:     []byte("RIFF\x00\x00\x00\x00AVI "),
			wantErr: "not a WAVE file",
		},
		{
			name:    "truncated chunk header",
			wav:     truncated,
			wantErr: "truncated chunk header",
		},
		{
			name:    "oversized chunk",
			wav:     oversized,
			wantErr: "exceeds",
		},
		{
			name:    "missing fmt",
			wav:     buildTestWAV(wavTestChunk{id: "data", body: []byte{1, 0}}),
			wantErr: "missing fmt chunk",
		},
		{
			name:    "missing data",
			wav:     buildTestWAV(wavTestChunk{id: "fmt ", body: fmtChunkBody(wavFormatPCM, 1, audioSampleRateHz, 16)}),
			wantErr: "missing data chunk",
		},
		{
			name:    "stereo",
			wav:     buildTestWAV(wavTestChunk{id: "fmt ", body: fmtChunkBody(wavFormatPCM, 2, audioSampleRateHz, 16)}),
			wantErr: "unsupported channel count",
		},
		{
			name:    "wrong sample rate",
			wav:     buildTestWAV(wavTestChunk{id: "fmt ", body: fmtChunkBody(wavFormatPCM, 1, 44100, 16)}),
			wantErr: "unsupported sample rate",
		},
		{
			name:    "eight bit",
			wav:     buildTestWAV(wavTestChunk{id: "fmt ", body: fmtChunkBody(wavFormatPCM, 1, audioSampleRateHz, 8)}),
			wantErr: "unsupported sample width",
		},
		{
			name:    "float samples",
			wav:     buildTestWAV(wavTestChunk{id: "fmt ", body: fmtChunkBody(3, 1, audioSampleRateHz, 32)}),
			wantErr: wantPCM,
		},
		{
			name: "odd data length",
			wav: buildTestWAV(
				wavTestChunk{id: "fmt ", body: fmtChunkBody(wavFormatPCM, 1, audioSampleRateHz, 16)},
				wavTestChunk{id: "data", body: []byte{1, 2, 3}},
			),
			wantErr: "odd byte length",
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if _, err := parseWAV(bytes.NewReader(test.wav)); err == nil {
				t.Fatalf("parseWAV accepted %s", test.name)
			} else if !bytes.Contains([]byte(err.Error()), []byte(test.wantErr)) {
				t.Fatalf("parseWAV error = %q, want it to contain %q", err, test.wantErr)
			}
		})
	}
}
