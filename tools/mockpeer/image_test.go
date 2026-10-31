package main

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func writeTempMedia(t *testing.T, name string, data []byte) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), name)
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatalf("write %s: %v", path, err)
	}
	return path
}

func TestLoadImageFrameAcceptsAJPEGAndCarriesPts(t *testing.T) {
	jpeg := []byte{0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0x00, 0xFF, 0xD9}
	path := writeTempMedia(t, "still.jpg", jpeg)

	frame, err := loadImageFrame(path, 1250, 320, 240)
	if err != nil {
		t.Fatalf("loadImageFrame returned %v", err)
	}
	if !bytes.Equal(frame.GetJpeg(), jpeg) {
		t.Fatalf("jpeg payload changed")
	}
	if frame.GetPtsMs() != 1250 {
		t.Fatalf("ptsMs = %d, want 1250", frame.GetPtsMs())
	}
	if frame.GetWidth() != 320 || frame.GetHeight() != 240 {
		t.Fatalf("size = %dx%d, want 320x240", frame.GetWidth(), frame.GetHeight())
	}
}

func TestLoadImageFrameRejectsMissingJPEGMarkers(t *testing.T) {
	tests := []struct {
		name    string
		jpeg    []byte
		wantErr string
	}{
		{
			name:    "missing SOI",
			jpeg:    []byte{0x00, 0x00, 0xFF, 0xD9},
			wantErr: "SOI",
		},
		{
			name:    "missing EOI",
			jpeg:    []byte{0xFF, 0xD8, 0x00, 0x00},
			wantErr: "EOI",
		},
		{
			name:    "short SOI only",
			jpeg:    []byte{0xFF, 0xD8},
			wantErr: "too short",
		},
		{
			name:    "empty",
			jpeg:    []byte{},
			wantErr: "too short",
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			path := writeTempMedia(t, "still.jpg", test.jpeg)
			_, err := loadImageFrame(path, 0, 320, 240)
			if err == nil {
				t.Fatalf("loadImageFrame accepted a non-JPEG")
			}
			if !strings.Contains(err.Error(), test.wantErr) {
				t.Fatalf("loadImageFrame error = %q, want it to contain %q", err, test.wantErr)
			}
		})
	}
}

func TestLoadImageFrameRejectsNonPositiveDimensions(t *testing.T) {
	path := writeTempMedia(t, "still.jpg", []byte{0xFF, 0xD8, 0xFF, 0xD9})
	if _, err := loadImageFrame(path, 0, 0, 240); err == nil {
		t.Fatal("loadImageFrame accepted a zero width")
	}
	if _, err := loadImageFrame(path, 0, 320, -1); err == nil {
		t.Fatal("loadImageFrame accepted a negative height")
	}
}
