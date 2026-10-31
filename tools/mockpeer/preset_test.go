package main

import (
	"bytes"
	"testing"
)

func TestAudioFrameBytesMatchesTheFrozenWireShape(t *testing.T) {
	if audioFrameBytes != 640 {
		t.Fatalf("audioFrameBytes = %d, want 640 (320 samples * 2)", audioFrameBytes)
	}
	if audioFrameBytes != audioSamplesPerFrame*2 {
		t.Fatalf("audioFrameBytes = %d, want %d", audioFrameBytes, audioSamplesPerFrame*2)
	}
}

func TestChunkPCM16SplitsInto640ByteFrames(t *testing.T) {
	pcm := make([]byte, audioFrameBytes*2+60)
	for i := range pcm {
		pcm[i] = byte(i)
	}

	frames := chunkPCM16(pcm)
	if len(frames) != 3 {
		t.Fatalf("chunkPCM16 returned %d frames, want 3 (640+640+60)", len(frames))
	}
	if len(frames[0]) != audioFrameBytes || len(frames[1]) != audioFrameBytes || len(frames[2]) != 60 {
		t.Fatalf("frame lengths = %d/%d/%d, want 640/640/60", len(frames[0]), len(frames[1]), len(frames[2]))
	}
	if !bytes.Equal(frames[0], pcm[:audioFrameBytes]) {
		t.Fatal("first frame payload changed")
	}
	if !bytes.Equal(frames[2], pcm[2*audioFrameBytes:]) {
		t.Fatal("trailing partial frame payload changed")
	}
}

func TestChunkPCM16HandlesEmptyAndExactFrames(t *testing.T) {
	if frames := chunkPCM16(nil); frames != nil {
		t.Fatalf("chunkPCM16(nil) = %v, want nil", frames)
	}
	if frames := chunkPCM16([]byte{}); frames != nil {
		t.Fatalf("chunkPCM16(empty) = %v, want nil", frames)
	}

	exact := make([]byte, audioFrameBytes)
	frames := chunkPCM16(exact)
	if len(frames) != 1 {
		t.Fatalf("chunkPCM16(exact frame) returned %d frames, want 1", len(frames))
	}
	if len(frames[0]) != audioFrameBytes {
		t.Fatalf("exact frame length = %d, want %d", len(frames[0]), audioFrameBytes)
	}
}

func TestBuildImageFrameCarriesBytesAndMetadata(t *testing.T) {
	jpeg := []byte{0xFF, 0xD8, 0xFF, 0xD9}
	frame := buildImageFrame(jpeg, 42, 320, 240)

	if !bytes.Equal(frame.GetJpeg(), jpeg) {
		t.Fatalf("jpeg = %v, want %v", frame.GetJpeg(), jpeg)
	}
	if frame.GetPtsMs() != 42 {
		t.Fatalf("ptsMs = %d, want 42", frame.GetPtsMs())
	}
	if frame.GetWidth() != 320 || frame.GetHeight() != 240 {
		t.Fatalf("size = %dx%d, want 320x240", frame.GetWidth(), frame.GetHeight())
	}
}
